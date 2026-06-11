# app/sync_ops.py
"""Backend op-log helpers (Option C, see docs/SYNC_DESIGN.md).

Plain functions over the SessionOp / AppliedOp models. Callers are responsible
for committing the transaction; these helpers only add rows / read.
"""
import json

from app import models


def next_seq(db, session_id: int) -> int:
    """Return the next per-session monotonic seq (max existing + 1, starting at 1)."""
    last = (
        db.query(models.SessionOp.seq)
        .filter(models.SessionOp.session_id == session_id)
        .order_by(models.SessionOp.seq.desc())
        .first()
    )
    if last is None or last[0] is None:
        return 1
    return last[0] + 1


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
    # NOTE: SessionOp.idempotency_key carries a GLOBAL unique constraint, which
    # collides when the same key is legitimately reused across different
    # sessions (each session has its own key space). The op-log never reads this
    # column back (OpDTO does not expose it) — the per-session AppliedOp ledger
    # is the idempotency authority. Changing the column to a per-session unique
    # constraint is a schema change (no Alembic in this project), so to keep
    # cross-session key reuse working without leaking/colliding we simply do not
    # persist the key on the op. The parameter is kept for call-site stability.
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
    row = db.query(models.AppliedOp).filter(models.AppliedOp.key == key).first()
    if row is None:
        row = models.AppliedOp(
            key=key,
            session_id=session_id,
            item_id=item_id,
            result_json=result_json,
        )
        db.add(row)
    elif row.session_id == session_id:
        # Same session reusing its own key: refresh the stored result.
        row.item_id = item_id
        row.result_json = result_json
    else:
        # `key` is the AppliedOp PK (no schema change / Alembic in this project),
        # so a different session reusing the same key collides on the PK. The
        # idempotency ledger is best-effort: rather than overwrite (and thus leak
        # / clobber) the OTHER session's stored row, we skip the write. This is
        # safe because check_idempotent() is now session-scoped and returns None
        # for the mismatched session, so that endpoint applies the op normally;
        # only the dedupe optimization is lost for the colliding key, not
        # correctness.
        return


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
