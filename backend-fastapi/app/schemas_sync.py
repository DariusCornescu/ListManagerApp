# app/schemas_sync.py
"""Pydantic v2 DTOs for the backend op-log (Option C, see docs/SYNC_DESIGN.md)."""
from datetime import datetime
from typing import Optional

from pydantic import BaseModel, ConfigDict


class OpDTO(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    session_id: int
    seq: int
    op_type: str
    item_uuid: Optional[str] = None
    product_id: Optional[int] = None
    qty_delta: Optional[int] = None
    actor_user_id: Optional[int] = None
    created_at: datetime
