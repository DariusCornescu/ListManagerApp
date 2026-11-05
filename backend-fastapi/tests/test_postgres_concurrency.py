"""Concurrency acceptance gate that must run against real PostgreSQL.

Set POSTGRES_TEST_URL to an isolated database whose name ends in ``_test``.
The fixture recreates that database's public schema, so production URLs are
intentionally rejected.
"""

import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Barrier

import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import sessionmaker

from app import models
from app.auth import create_access_token, get_password_hash
from app.database import get_db
from app.main import app


POSTGRES_TEST_URL = os.environ.get("POSTGRES_TEST_URL")
pytestmark = pytest.mark.skipif(
    not POSTGRES_TEST_URL,
    reason="POSTGRES_TEST_URL is required for the real PostgreSQL concurrency gate",
)


@pytest.fixture(scope="module")
def postgres_engine():
    url = make_url(POSTGRES_TEST_URL)
    if url.get_backend_name() != "postgresql":
        pytest.fail("POSTGRES_TEST_URL must use PostgreSQL")
    if not (url.database or "").endswith("_test"):
        pytest.fail("POSTGRES_TEST_URL database name must end with '_test'")

    engine = create_engine(POSTGRES_TEST_URL, pool_size=12, max_overflow=4)
    alembic_cfg = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
    alembic_cfg.set_main_option("sqlalchemy.url", POSTGRES_TEST_URL)

    def recreate_public_schema():
        with engine.begin() as connection:
            connection.execute(text("DROP SCHEMA IF EXISTS public CASCADE"))
            connection.execute(text("CREATE SCHEMA public"))

    recreate_public_schema()
    command.upgrade(alembic_cfg, "head")
    try:
        yield engine
    finally:
        recreate_public_schema()
        engine.dispose()


@pytest.fixture(scope="module")
def postgres_api(postgres_engine):
    session_factory = sessionmaker(
        autocommit=False,
        autoflush=False,
        bind=postgres_engine,
    )

    with session_factory() as db:
        owner = models.User(
            username="postgres-writer",
            email="postgres-writer@example.com",
            hashed_password=get_password_hash("test-only-password"),
            role="USER",
            is_active=True,
        )
        distributor = models.Distributor(distributor_name="Postgres Distributor")
        db.add_all([owner, distributor])
        db.flush()
        product = models.Product(
            name="Concurrent Product",
            distributor_id=distributor.id,
        )
        db.add(product)
        db.flush()
        session = models.GlobalSession(
            name="Postgres Concurrency",
            owner_user_id=owner.id,
            is_active=True,
        )
        db.add(session)
        db.flush()
        item = models.GlobalSessionItem(
            session_id=session.id,
            product_id=product.id,
            quantity=1,
            version=0,
            item_uuid="11111111-1111-1111-1111-111111111111",
        )
        db.add(item)
        db.commit()
        ids = {
            "session": session.id,
            "product": product.id,
            "item": item.id,
        }

    def override_get_db():
        db = session_factory()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override_get_db
    headers = {
        "Authorization": f"Bearer {create_access_token({'sub': 'postgres-writer'})}"
    }
    try:
        yield TestClient(app), session_factory, ids, headers
    finally:
        app.dependency_overrides.clear()


def _concurrent(count, action):
    barrier = Barrier(count)

    def synchronized(index):
        barrier.wait(timeout=10)
        return action(index)

    with ThreadPoolExecutor(max_workers=count) as executor:
        return list(executor.map(synchronized, range(count)))


def test_concurrent_mutations_are_serialized(postgres_api):
    client, session_factory, ids, headers = postgres_api
    session_id = ids["session"]

    add_responses = _concurrent(
        8,
        lambda index: client.post(
            f"/api/session/{session_id}/items",
            headers=headers,
            json={
                "product_id": ids["product"],
                "quantity": 2,
                "idempotency_key": f"concurrent-add-{index}",
            },
        ),
    )
    assert [response.status_code for response in add_responses] == [200] * 8

    duplicate_responses = _concurrent(
        6,
        lambda _: client.post(
            f"/api/session/{session_id}/items",
            headers=headers,
            json={
                "product_id": ids["product"],
                "quantity": 3,
                "idempotency_key": "same-operation-from-two-workers",
            },
        ),
    )
    assert [response.status_code for response in duplicate_responses] == [200] * 6

    with session_factory() as db:
        item = db.get(models.GlobalSessionItem, ids["item"])
        assert item.quantity == 20
        assert item.version == 9

    update_responses = _concurrent(
        2,
        lambda index: client.put(
            f"/api/session/items/{ids['item']}",
            headers=headers,
            json={
                "quantity": 30 + index,
                "version": 9,
                "idempotency_key": f"competing-put-{index}",
            },
        ),
    )
    assert sorted(response.status_code for response in update_responses) == [200, 409]

    with session_factory() as db:
        item = db.get(models.GlobalSessionItem, ids["item"])
        operations = (
            db.query(models.SessionOp)
            .filter(models.SessionOp.session_id == session_id)
            .order_by(models.SessionOp.seq)
            .all()
        )
        session = db.get(models.GlobalSession, session_id)

    assert item.quantity in {30, 31}
    assert item.version == 10
    assert [operation.seq for operation in operations] == list(range(1, 11))
    assert session.next_op_seq == 11
    assert len({operation.seq for operation in operations}) == len(operations)


def test_two_writers_converge_through_a_real_409_storm(postgres_api):
    """EX-25 on real connections: read, write, and retry on 409 until applied."""
    client, session_factory, ids, headers = postgres_api
    item_id = ids["item"]
    session_id = ids["session"]
    writes_per_writer = 15

    with session_factory() as db:
        item = db.get(models.GlobalSessionItem, item_id)
        start_quantity, start_version = item.quantity, item.version

    def writer(index):
        conflicts = 0
        for write in range(writes_per_writer):
            for attempt in range(200):
                items = client.get(f"/api/session/{session_id}/items", headers=headers).json()
                current = next(row for row in items if row["id"] == item_id)
                response = client.put(
                    f"/api/session/items/{item_id}",
                    headers=headers,
                    json={
                        "quantity": current["quantity"] + 1,
                        "version": current["version"],
                        "idempotency_key": f"storm-{index}-{write}",
                    },
                )
                if response.status_code == 200:
                    break
                assert response.status_code == 409, response.text
                conflicts += 1
            else:
                pytest.fail("Writer never got through")
        return conflicts

    conflicts = _concurrent(2, writer)

    with session_factory() as db:
        item = db.get(models.GlobalSessionItem, item_id)
        sequences = [
            row.seq
            for row in db.query(models.SessionOp)
            .filter(models.SessionOp.session_id == session_id)
            .order_by(models.SessionOp.seq)
        ]

    # Every increment read the value it replaced, so none can be lost.
    assert item.quantity == start_quantity + 2 * writes_per_writer
    assert item.version == start_version + 2 * writes_per_writer
    assert sequences == list(range(1, len(sequences) + 1))
    print(f"409 responses under real contention: {sum(conflicts)}")
