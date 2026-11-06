# app/sync_ops.py
"""Backend op-log helpers (Option C, see docs/SYNC_DESIGN.md).

Plain functions over the SessionOp / AppliedOp models. Callers are responsible
for committing the transaction; these helpers only add rows / read.
"""
import json

from sqlalchemy import text, update

from app import models


def limit_lock_wait(db) -> None:
    """Fail a waiting writer rather than let it wait forever.

    Handlers run database calls on the event loop, so a writer stuck waiting for a row lock
    would freeze the whole process, including the request that would release the lock.
    """
    if db.get_bind().dialect.name == "postgresql":
        db.execute(text("SET LOCAL lock_timeout = '5s'"))


def lock_session(db, session_id: int):
    """Lock and refresh one session for the remainder of the transaction.

    PostgreSQL's row lock serializes all mutations belonging to the session.
    SQLite ignores ``FOR UPDATE``; its write lock plus the atomic sequence
    update still keeps the local/test path compatible.
    """
    limit_lock_wait(db)
    return (
        db.query(models.GlobalSession)
        .filter(models.GlobalSession.id == session_id)
        .populate_existing()
        .with_for_update()
        .one_or_none()
    )


def next_seq(db, session_id: int) -> int:
    """Atomically reserve and return the next per-session sequence."""
    next_value = db.execute(
        update(models.GlobalSession)
        .where(models.GlobalSession.id == session_id)
        .values(next_op_seq=models.GlobalSession.next_op_seq + 1)
        .returning(models.GlobalSession.next_op_seq)
        .execution_options(synchronize_session=False)
    ).scalar_one_or_none()
    if next_value is None:
        raise LookupError(f"Session {session_id} does not exist")
    return int(next_value) - 1


def record_op(
    db,
    session_id: int,
    op_type: str,
    actor_user_id: int | None,
    *,
    item_uuid: str | None = None,
    product_id: int | None = None,
    qty_delta: int | None = None,
    idempotency_key: str | None = None,
):
    """Create + add a SessionOp with the next seq for the session. Returns the op
    (caller commits)."""
    # NOTE: the op-log never reads idempotency_key back (OpDTO does not expose
    # it) — the per-session AppliedOp ledger is the idempotency authority — so
    # we intentionally do not persist the key on the op. (The global unique
    # constraint that previously forced this was dropped in Alembic migration
    # 0002; leaving the column unpopulated is now a deliberate choice, pending
    # any future per-session use.) The parameter is kept for call-site stability.
    op = models.SessionOp(
        session_id=session_id,
        seq=next_seq(db, session_id),
        op_type=op_type,
        item_uuid=item_uuid,
        product_id=product_id,
        qty_delta=qty_delta,
        idempotency_key=None,
        actor_user_id=actor_user_id,
    )
    db.add(op)
    return op


def check_idempotent(db, key: str | None, session_id: int) -> dict | None:
    """If key is set and an AppliedOp with it exists FOR THIS session, return its
    stored result (parsed JSON, or {} if empty); otherwise None.

    The session_id filter is a security boundary: a key stored for another
    session/team must NOT match here, or its stored DTO would be disclosed to a
    caller posting to a different session (cross-tenant leak)."""
    if key is None:
        return None
    row = (
        db.query(models.AppliedOp)
        .filter(
            models.AppliedOp.key == key,
            models.AppliedOp.session_id == session_id,
        )
        .first()
    )
    if row is None:
        return None
    if not row.result_json:
        return {}
    return json.loads(row.result_json)


def store_idempotent(
    db,
    key: str | None,
    session_id: int,
    item_id: int | None,
    result: dict,
) -> None:
    """Upsert an AppliedOp row keyed by `key`. No-op if key is None. Caller commits."""
    if key is None:
        return
    result_json = json.dumps(result)
    # Composite PK (key, session_id): look up the row for THIS session only.
    # A different session reusing the same key is now a distinct row, so each
    # session keeps its own ledger entry (no cross-session collision or leak).
    row = (
        db.query(models.AppliedOp)
        .filter(
            models.AppliedOp.key == key,
            models.AppliedOp.session_id == session_id,
        )
        .first()
    )
    if row is None:
        row = models.AppliedOp(
            key=key,
            session_id=session_id,
            item_id=item_id,
            result_json=result_json,
        )
        db.add(row)
    else:
        # Same session reusing its own key: refresh the stored result.
        row.item_id = item_id
        row.result_json = result_json


def get_ops_since(db, session_id: int, since_seq: int) -> list:
    """Return SessionOp rows for the session with seq > since_seq, ordered by seq asc."""
    return (
        db.query(models.SessionOp)
        .filter(
            models.SessionOp.session_id == session_id,
            models.SessionOp.seq > since_seq,
        )
        .order_by(models.SessionOp.seq.asc())
        .all()
    )
