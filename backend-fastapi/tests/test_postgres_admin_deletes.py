"""Admin deletes against real PostgreSQL, which enforces foreign keys.

SQLite in the main suite does not, so only this file can prove these answers.
Set POSTGRES_TEST_URL to an isolated database whose name ends in ``_test``. The
fixture recreates that database's public schema, so production URLs are rejected.
"""

import os
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import sessionmaker

from app import models
from app.auth import create_user_access_token, get_password_hash
from app.database import get_db
from app.main import app


POSTGRES_TEST_URL = os.environ.get("POSTGRES_TEST_URL")
pytestmark = pytest.mark.skipif(
    not POSTGRES_TEST_URL,
    reason="POSTGRES_TEST_URL is required for the real PostgreSQL delete checks",
)


@pytest.fixture()
def pg_session():
    url = make_url(POSTGRES_TEST_URL)
    if url.get_backend_name() != "postgresql":
        pytest.fail("POSTGRES_TEST_URL must use PostgreSQL")
    if not (url.database or "").endswith("_test"):
        pytest.fail("POSTGRES_TEST_URL database name must end with '_test'")

    engine = create_engine(POSTGRES_TEST_URL)
    config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
    config.set_main_option("sqlalchemy.url", POSTGRES_TEST_URL)

    def recreate_public_schema():
        with engine.begin() as connection:
            connection.execute(text("DROP SCHEMA IF EXISTS public CASCADE"))
            connection.execute(text("CREATE SCHEMA public"))

    recreate_public_schema()
    command.upgrade(config, "head")
    session = sessionmaker(autocommit=False, autoflush=False, bind=engine)()
    try:
        yield session
    finally:
        session.close()
        recreate_public_schema()
        engine.dispose()


@pytest.fixture()
def pg_client(pg_session):
    def override_get_db():
        yield pg_session

    app.dependency_overrides[get_db] = override_get_db
    yield TestClient(app, raise_server_exceptions=False)
    app.dependency_overrides.clear()


def _user(session, name, role="USER"):
    user = models.User(
        username=name,
        email=f"{name}@example.com",
        hashed_password=get_password_hash("test-only-password"),
        role=role,
        is_active=True,
    )
    session.add(user)
    session.commit()
    return user


@pytest.fixture()
def admin_headers(pg_session):
    admin = _user(pg_session, "pg-admin", role="ADMIN")
    return {"Authorization": f"Bearer {create_user_access_token(admin)}"}


def test_delete_user_in_a_team_answers_409(pg_client, pg_session, admin_headers):
    user = _user(pg_session, "pg-member")
    team = models.Team(name="PG Team")
    pg_session.add(team)
    pg_session.commit()
    pg_session.add(models.TeamMember(team_id=team.id, user_id=user.id, role="member"))
    pg_session.commit()

    response = pg_client.delete(f"/api/admin/users/{user.id}", headers=admin_headers)

    assert response.status_code == 409
    assert response.json()["detail"]["blockers"] == {"team_memberships": 1}


def test_delete_user_with_a_list_answers_409(pg_client, pg_session, admin_headers):
    user = _user(pg_session, "pg-owner")
    pg_session.add(models.GlobalSession(name="PG List", owner_user_id=user.id))
    pg_session.commit()

    response = pg_client.delete(f"/api/admin/users/{user.id}", headers=admin_headers)

    assert response.status_code == 409
    assert response.json()["detail"]["blockers"] == {"lists": 1}


def test_delete_user_without_data_cascades_their_tokens(
    pg_client, pg_session, admin_headers
):
    user = _user(pg_session, "pg-free")
    user_id = user.id
    login = pg_client.post(
        "/api/auth/login",
        json={"username": "pg-free", "password": "test-only-password"},
    )
    assert login.status_code == 200

    response = pg_client.delete(f"/api/admin/users/{user_id}", headers=admin_headers)

    assert response.status_code == 200
    pg_session.expire_all()
    assert pg_session.get(models.User, user_id) is None
    assert (
        pg_session.query(models.RefreshToken)
        .filter(models.RefreshToken.user_id == user_id)
        .count()
        == 0
    )


def test_delete_race_answers_409_not_500(
    pg_client, pg_session, admin_headers, monkeypatch
):
    """A foreign key the blocker check missed still ends in 409."""
    from app.routers import admin as admin_router

    user = _user(pg_session, "pg-race")
    pg_session.add(models.GlobalSession(name="PG Race", owner_user_id=user.id))
    pg_session.commit()
    monkeypatch.setattr(admin_router, "_user_delete_blockers", lambda *_: {})

    response = pg_client.delete(f"/api/admin/users/{user.id}", headers=admin_headers)

    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "user_has_linked_data"
