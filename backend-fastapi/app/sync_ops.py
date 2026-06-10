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
    op = models.SessionOp(
        session_id=session_id,
        seq=next_seq(db, session_id),
        op_type=op_type,
        item_uuid=item_uuid,
        product_id=product_id,
        qty_delta=qty_delta,
        idempotency_key=idempotency_key,
        actor_user_id=actor_user_id,
    )
    db.add(op)
    return op


def check_idempotent(db, key: str | None) -> dict | None:
    """If key is set and an AppliedOp with it exists, return its stored result
    (parsed JSON, or {} if empty); otherwise None."""
    if key is None:
        return None
    row = db.query(models.AppliedOp).filter(models.AppliedOp.key == key).first()
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
    else:
        row.session_id = session_id
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
