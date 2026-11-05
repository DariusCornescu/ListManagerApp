"""EX-25: two writers racing one item through the optimistic lock.

Each writer works like a client: read the item, compute the new absolute quantity,
PUT with the version it read, and on 409 read again and retry. The interleaving is
driven by a seeded generator so a failure reproduces exactly.

A real-connection version of this race is in test_postgres_concurrency.py.
"""
import random

import pytest

from app import models


class Writer:
    """Client-side retry path: read, change, write; a 409 means start over."""

    def __init__(self, client, headers, name, session_id, item_id):
        self.client = client
        self.headers = headers
        self.name = name
        self.session_id = session_id
        self.item_id = item_id
        self.snapshot = None
        self.wanted_delta = None
        self.applied = 0
        self.conflicts = 0
        self.total_delta = 0

    def read(self):
        items = self.client.get(
            f"/api/session/{self.session_id}/items", headers=self.headers
        ).json()
        self.snapshot = next(item for item in items if item["id"] == self.item_id)

    def want(self, delta):
        self.wanted_delta = delta
        self.read()

    def write(self, key):
        response = self.client.put(
            f"/api/session/items/{self.item_id}",
            headers=self.headers,
            json={
                "quantity": self.snapshot["quantity"] + self.wanted_delta,
                "version": self.snapshot["version"],
                "idempotency_key": key,
            },
        )
        if response.status_code == 409:
            self.conflicts += 1
            self.read()
            return False
        assert response.status_code == 200, response.text
        self.applied += 1
        self.total_delta += self.wanted_delta
        self.wanted_delta = None
        return True


@pytest.fixture
def race(client, auth_headers, team_member_headers, team_session_item):
    """Two team members, one item."""
    item = team_session_item
    return (
        Writer(client, auth_headers, "a", item.session_id, item.id),
        Writer(client, team_member_headers, "b", item.session_id, item.id),
        item,
    )


def _run_storm(writer_a, writer_b, rounds, rng, tag="storm"):
    """Both writers read the same version, then both write: one must lose each round."""
    keys = 0
    for _ in range(rounds):
        writer_a.want(rng.randint(1, 5))
        writer_b.want(rng.randint(1, 5))
        # Bounded: each pass, at least one writer holding the latest version succeeds.
        for _ in range(50):
            pending = [w for w in (writer_a, writer_b) if w.wanted_delta is not None]
            if not pending:
                break
            rng.shuffle(pending)
            for writer in pending:
                keys += 1
                writer.write(f"{tag}-{writer.name}-{keys}")
        else:
            pytest.fail("Writers did not converge")


@pytest.mark.parametrize("seed", range(6))
def test_storm_of_409s_converges_without_lost_updates(race, db_session, seed):
    writer_a, writer_b, item = race
    initial_quantity = item.quantity
    initial_version = item.version
    session_id = item.session_id

    _run_storm(writer_a, writer_b, rounds=25, rng=random.Random(seed))

    # Every round has one forced loser, so this really was a storm.
    assert writer_a.conflicts + writer_b.conflicts >= 25

    db_session.expire_all()
    final = db_session.get(models.GlobalSessionItem, item.id)

    # No lost update: every acknowledged delta is present.
    assert final.quantity == initial_quantity + writer_a.total_delta + writer_b.total_delta
    assert writer_a.applied == writer_b.applied == 25

    # One version per successful write; a 409 never moves it.
    assert final.version == initial_version + 50

    # The op-log agrees with the state: no phantom or missing writes.
    ops = (
        db_session.query(models.SessionOp)
        .filter(models.SessionOp.session_id == session_id)
        .order_by(models.SessionOp.seq)
        .all()
    )
    changes = [op for op in ops if op.op_type == "ChangeQty"]
    assert len(changes) == 50
    assert sum(op.qty_delta for op in changes) == writer_a.total_delta + writer_b.total_delta
    assert [op.seq for op in ops] == list(range(ops[0].seq, ops[0].seq + len(ops)))

    # Both members see one identical row.
    views = [
        writer.client.get(f"/api/session/{session_id}/items", headers=writer.headers).json()
        for writer in (writer_a, writer_b)
    ]
    assert views[0] == views[1]
    assert len(views[0]) == 1
    assert views[0][0]["quantity"] == final.quantity


def test_storm_is_deterministic_for_a_seed(client, auth_headers, team_member_headers, db_session, team_session_item):
    """The same seed gives the same history, so a failure can be replayed."""
    item = team_session_item

    def outcome(seed, tag):
        a = Writer(client, auth_headers, "a", item.session_id, item.id)
        b = Writer(client, team_member_headers, "b", item.session_id, item.id)
        _run_storm(a, b, rounds=5, rng=random.Random(seed), tag=tag)
        return a.conflicts, b.conflicts, a.total_delta, b.total_delta

    assert outcome(11, "first") == outcome(11, "second")


def test_a_rejected_write_changes_nothing(race, db_session):
    writer_a, writer_b, item = race
    writer_a.want(1)
    writer_b.want(1)
    assert writer_a.write("winner") is True

    db_session.expire_all()
    before = db_session.get(models.GlobalSessionItem, item.id)
    quantity, version = before.quantity, before.version
    ops_before = db_session.query(models.SessionOp).count()
    applied_before = db_session.query(models.AppliedOp).count()

    assert writer_b.write("loser") is False

    db_session.expire_all()
    after = db_session.get(models.GlobalSessionItem, item.id)
    assert (after.quantity, after.version) == (quantity, version)
    assert db_session.query(models.SessionOp).count() == ops_before
    # The loser's key stays unused, so the same key can be sent again after a rebase.
    assert db_session.query(models.AppliedOp).count() == applied_before


def test_retry_with_the_same_key_after_a_409_applies_once(race, db_session):
    """What Android's Keep mine does: same idempotency key, rebased version."""
    writer_a, writer_b, item = race
    writer_a.want(2)
    writer_b.want(3)
    assert writer_a.write("a-1") is True
    assert writer_b.write("b-1") is False  # 409, and writer_b re-reads.
    assert writer_b.write("b-1") is True

    db_session.expire_all()
    version_after = db_session.get(models.GlobalSessionItem, item.id).version

    # A duplicate delivery of the now-successful key replays and applies nothing.
    response = writer_b.client.put(
        f"/api/session/items/{item.id}",
        headers=writer_b.headers,
        json={"quantity": 999, "version": writer_b.snapshot["version"], "idempotency_key": "b-1"},
    )
    assert response.status_code == 200
    assert response.json()["quantity"] != 999
    db_session.expire_all()
    assert db_session.get(models.GlobalSessionItem, item.id).version == version_after
