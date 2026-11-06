"""Shared stock-taking inventory (EX-44), with the same guarantees as session items.

Each person or team has one inventory. Writers are serialized by a lock on its row,
updates use an optimistic version, and a replayed request is answered from a ledger.
Rows are soft-deleted: a phone retrying a lost "add" must not bring back a row that
another phone has since removed.

Rows are addressed by the UUID the phone chose, so a queued edit or delete of a row whose
add has not been acknowledged yet needs no server id, and no remapping afterwards.
"""
import json
from datetime import datetime, timezone
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .. import authz, models, schemas, sync_ops
from ..auth import get_current_user
from ..database import get_db
from ..websocket_manager import manager


router = APIRouter()


def _load_for_write(inventory_id: int, user: models.User, db: Session) -> models.Inventory:
    """Authorize, then lock the row for the rest of the transaction."""
    _require_access(inventory_id, user, db)
    sync_ops.limit_lock_wait(db)
    return (
        db.query(models.Inventory)
        .filter(models.Inventory.id == inventory_id)
        .populate_existing()
        .with_for_update()
        .one()
    )


def _require_access(inventory_id: int, user: models.User, db: Session) -> models.Inventory:
    inventory = db.query(models.Inventory).filter(models.Inventory.id == inventory_id).first()
    if inventory is None or not _can_access(inventory, user, db):
        raise HTTPException(status_code=404, detail="Inventory not found")
    return inventory


def _can_access(inventory: models.Inventory, user: models.User, db: Session) -> bool:
    if inventory.owner_user_id == user.id:
        return True
    return inventory.team_id is not None and inventory.team_id in authz.get_user_team_ids(user, db)


def _find_item(inventory_id: int, item_uuid: str, db: Session) -> Optional[models.InventoryItem]:
    return (
        db.query(models.InventoryItem)
        .filter(
            models.InventoryItem.inventory_id == inventory_id,
            models.InventoryItem.item_uuid == item_uuid,
        )
        .populate_existing()
        .first()
    )


def _replay(key: Optional[str], inventory_id: int, db: Session) -> Optional[dict]:
    if key is None:
        return None
    row = db.get(models.InventoryAppliedOp, (key, inventory_id))
    return json.loads(row.result_json) if row is not None else None


def _remember(key: Optional[str], inventory_id: int, result: dict, db: Session) -> None:
    if key is not None:
        db.add(models.InventoryAppliedOp(
            key=key, inventory_id=inventory_id, result_json=json.dumps(result),
        ))


def _dto(item: models.InventoryItem) -> dict:
    return schemas.InventoryItemDTO.model_validate(item).model_dump(mode="json")


def _require_product(product_id: Optional[int], db: Session) -> None:
    if product_id is not None and db.get(models.Product, product_id) is None:
        db.rollback()  # Called under the inventory lock; see sessions._refuse.
        raise HTTPException(status_code=404, detail="Product not found")


async def _announce(inventory: models.Inventory, user: models.User, db: Session) -> None:
    if inventory.owner_user_id is not None:
        recipients = {inventory.owner_user_id}
    else:
        rows = (
            db.query(models.TeamMember.user_id)
            .filter(models.TeamMember.team_id == inventory.team_id)
            .all()
        )
        recipients = {row[0] for row in rows}
    await manager.send_to_users({
        "type": "inventory_changed",
        "data": {"inventory_id": inventory.id, "user_id": user.id, "username": user.username},
    }, recipients)


def _snapshot(inventory: models.Inventory, db: Session) -> dict:
    items = (
        db.query(models.InventoryItem)
        .filter(
            models.InventoryItem.inventory_id == inventory.id,
            models.InventoryItem.deleted_at.is_(None),
        )
        .order_by(models.InventoryItem.id)
        .all()
    )
    return {
        "id": inventory.id,
        "owner_user_id": inventory.owner_user_id,
        "team_id": inventory.team_id,
        "items": items,
    }


@router.get("/api/inventory/active", response_model=schemas.InventoryDTO)
def get_inventory(
    team_id: Optional[int] = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """The caller's inventory, or their team's, created on first use."""
    if team_id is not None:
        authz.require_team_member(team_id, current_user, db)
        scope = {"team_id": team_id}
    else:
        scope = {"owner_user_id": current_user.id}

    inventory = db.query(models.Inventory).filter_by(**scope).first()
    if inventory is None:
        inventory = models.Inventory(**scope)
        db.add(inventory)
        try:
            db.commit()
        except IntegrityError:
            # Two phones opened it at the same moment; use the one that won.
            db.rollback()
            inventory = db.query(models.Inventory).filter_by(**scope).one()
    return _snapshot(inventory, db)


@router.get("/api/inventory/{inventory_id}/items", response_model=List[schemas.InventoryItemDTO])
def get_inventory_items(
    inventory_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    inventory = _require_access(inventory_id, current_user, db)
    return _snapshot(inventory, db)["items"]


@router.post("/api/inventory/{inventory_id}/items", response_model=schemas.InventoryItemDTO)
async def add_inventory_item(
    inventory_id: int,
    data: schemas.InventoryItemCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """Idempotent on item_uuid. A row deleted since comes back with deleted=true."""
    inventory = _load_for_write(inventory_id, current_user, db)

    existing = _find_item(inventory_id, data.item_uuid, db)
    if existing is not None:
        db.rollback()
        return existing

    _require_product(data.product_id, db)
    item = models.InventoryItem(
        inventory_id=inventory_id,
        item_uuid=data.item_uuid,
        name=data.name,
        product_id=data.product_id,
        quantity=data.quantity,
        price_bani=data.price_bani,
    )
    db.add(item)
    db.commit()
    db.refresh(item)
    await _announce(inventory, current_user, db)
    return item


@router.put("/api/inventory/{inventory_id}/items/{item_uuid}", response_model=schemas.InventoryItemDTO)
async def update_inventory_item(
    inventory_id: int,
    item_uuid: str,
    data: schemas.InventoryItemUpdate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """Replaces the row's editable fields, if the caller holds the current version."""
    inventory = _load_for_write(inventory_id, current_user, db)

    stored = _replay(data.idempotency_key, inventory_id, db)
    if stored is not None:
        db.rollback()
        return stored

    item = _find_item(inventory_id, item_uuid, db)
    if item is None or item.deleted_at is not None:
        db.rollback()
        raise HTTPException(status_code=404, detail="Item not found")
    if item.version != data.version:
        current = item.version
        db.rollback()
        raise HTTPException(status_code=409, detail={
            "error": "Conflict",
            "message": "Item was modified by another user. Please refresh and try again.",
            "current_version": current,
            "your_version": data.version,
        })
    _require_product(data.product_id, db)

    item.name = data.name
    item.product_id = data.product_id
    item.quantity = data.quantity
    item.price_bani = data.price_bani
    item.version += 1
    item.updated_at = datetime.now(timezone.utc)
    db.flush()
    result = _dto(item)
    _remember(data.idempotency_key, inventory_id, result, db)
    db.commit()
    await _announce(inventory, current_user, db)
    return result


@router.delete("/api/inventory/{inventory_id}/items/{item_uuid}")
async def delete_inventory_item(
    inventory_id: int,
    item_uuid: str,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """Deleting a deleted row succeeds: that is what a retry should see."""
    inventory = _load_for_write(inventory_id, current_user, db)
    item = _find_item(inventory_id, item_uuid, db)
    if item is not None and item.deleted_at is None:
        item.deleted_at = datetime.now(timezone.utc)
        db.commit()
        await _announce(inventory, current_user, db)
    else:
        db.rollback()
    return {"message": "Item deleted", "item_uuid": item_uuid}


@router.post("/api/inventory/{inventory_id}/clear")
async def clear_inventory(
    inventory_id: int,
    data: schemas.InventoryClear,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """Starts a new list. The key means a retry cannot erase rows added after the first try."""
    inventory = _load_for_write(inventory_id, current_user, db)
    stored = _replay(data.idempotency_key, inventory_id, db)
    if stored is not None:
        db.rollback()
        return stored

    count = (
        db.query(models.InventoryItem)
        .filter(
            models.InventoryItem.inventory_id == inventory_id,
            models.InventoryItem.deleted_at.is_(None),
        )
        .update({"deleted_at": datetime.now(timezone.utc)}, synchronize_session=False)
    )
    result = {"message": "Inventory cleared", "count": count}
    _remember(data.idempotency_key, inventory_id, result, db)
    db.commit()
    await _announce(inventory, current_user, db)
    return result
