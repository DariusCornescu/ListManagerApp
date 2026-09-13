import uuid
from datetime import datetime, timezone
from typing import List

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .. import authz, models, schemas, sync_ops
from ..auth import get_current_user
from ..database import get_db
from ..schemas_sync import OpDTO
from ..websocket_manager import manager


router = APIRouter()


@router.get("/api/session/active", response_model=schemas.GlobalSessionDTO)
def get_active_session(
    team_id: int = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """Get the caller's most-recent active session.

    Returns the most recently created active session the caller owns (personal)
    or that belongs to a team they are a member of. An optional team_id query
    param disambiguates to a specific team's active session. 404 if none.
    """
    query = db.query(models.GlobalSession).filter(
        models.GlobalSession.is_active == True
    )

    if team_id is not None:
        # Caller must be a member of the requested team (404 otherwise).
        authz.require_team_member(team_id, current_user, db)
        query = query.filter(models.GlobalSession.team_id == team_id)
    else:
        team_ids = authz.get_user_team_ids(current_user, db)
        ownership = [models.GlobalSession.owner_user_id == current_user.id]
        if team_ids:
            ownership.append(models.GlobalSession.team_id.in_(team_ids))
        from sqlalchemy import or_
        query = query.filter(or_(*ownership))

    session = query.order_by(models.GlobalSession.created_at.desc()).first()

    if not session:
        raise HTTPException(status_code=404, detail="No active session")

    return session


@router.post("/api/session/create", response_model=schemas.GlobalSessionDTO)
async def create_session(
    session_data: schemas.GlobalSessionCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """Create new session with real-time notification.

    Defaults to a personal session owned by the caller. If team_id is provided,
    the caller must be a member of that team and the session is owned by the
    team (owner_user_id=None). Deactivation is scoped to the same owner
    dimension (only the caller's other personal sessions, or only that team's
    other sessions).
    """
    team_id = getattr(session_data, "team_id", None)
    client_uuid = getattr(session_data, "client_uuid", None)

    if client_uuid is not None:
        existing_query = db.query(models.GlobalSession).filter(
            models.GlobalSession.client_uuid == client_uuid
        )
        if team_id is not None:
            authz.require_team_member(team_id, current_user, db)
            existing_query = existing_query.filter(models.GlobalSession.team_id == team_id)
        else:
            existing_query = existing_query.filter(
                models.GlobalSession.owner_user_id == current_user.id
            )
        existing = existing_query.first()
        if existing is not None:
            return existing

    if team_id is not None:
        # Caller must be a member of the team (404 otherwise).
        authz.require_team_member(team_id, current_user, db)
        # Deactivate only this team's other active sessions.
        db.query(models.GlobalSession).filter(
            models.GlobalSession.team_id == team_id
        ).update({"is_active": False})
        new_session = models.GlobalSession(
            name=session_data.name,
            is_active=True,
            owner_user_id=None,
            team_id=team_id,
            client_uuid=client_uuid,
        )
    else:
        # Deactivate only the caller's other personal active sessions.
        db.query(models.GlobalSession).filter(
            models.GlobalSession.owner_user_id == current_user.id
        ).update({"is_active": False})
        new_session = models.GlobalSession(
            name=session_data.name,
            is_active=True,
            owner_user_id=current_user.id,
            team_id=None,
            client_uuid=client_uuid,
        )

    db.add(new_session)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        if client_uuid is None:
            raise
        collision = db.query(models.GlobalSession).filter(
            models.GlobalSession.client_uuid == client_uuid
        ).first()
        authorized_collision = collision is not None and (
            (team_id is None and collision.owner_user_id == current_user.id) or
            (team_id is not None and collision.team_id == team_id)
        )
        if not authorized_collision:
            raise HTTPException(status_code=409, detail="Session identity already exists")
        return collision
    db.refresh(new_session)

    # Notify clients authorized for this session
    await manager.broadcast_to_session({
        "type": "session_created",
        "data": {
            "session_id": new_session.id,
            "session_name": new_session.name,
            "user_id": current_user.id,
            "username": current_user.username
        }
    }, new_session.id, db)

    return new_session


@router.post("/api/session/{session_id}/complete")
async def complete_session(
    session_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """
    Complete session and return items grouped by distributor
    This data is used for PDF generation (one PDF per distributor)
    """
    # Enforce access (404 if missing or caller has no relationship).
    session = authz.require_session_access(session_id, current_user, db, write=True)

    already_completed = not session.is_active

    # Get all items with products and distributors (JOIN)
    items = db.query(models.GlobalSessionItem).filter(
        models.GlobalSessionItem.session_id == session_id
    ).all()

    if not items:
        raise HTTPException(status_code=400, detail="Session has no items")

    # Group items by distributor
    items_by_distributor = {}

    for item in items:
        distributor_name = item.product.distributor.distributor_name

        if distributor_name not in items_by_distributor:
            items_by_distributor[distributor_name] = []

        # Create enriched item DTO
        item_dto = schemas.GlobalSessionItemDTO(
            id=item.id,
            session_id=item.session_id,
            product_id=item.product_id,
            quantity=item.quantity,
            version=item.version,
            created_at=item.created_at,
            updated_at=item.updated_at,
            product_name=item.product.name,
            distributor_name=distributor_name,
            item_uuid=item.item_uuid,
        )

        items_by_distributor[distributor_name].append(item_dto)

    # Mark session as completed
    if not already_completed:
        session.is_active = False
        session.completed_at = datetime.now(timezone.utc)
        db.commit()
        db.refresh(session)

    # Notify clients authorized for this session
    if not already_completed:
        await manager.broadcast_to_session({
            "type": "session_completed",
            "data": {
                "session_id": session_id,
                "total_items": sum(item.quantity for item in items),
                "distributor_count": len(items_by_distributor),
                "pdf_generated": True,
                "user_id": current_user.id,
                "username": current_user.username
            }
        }, session_id, db)

    # Return grouped data
    return {
        "session": schemas.GlobalSessionDTO.model_validate(session),
        "items_by_distributor": items_by_distributor,
        "distributor_count": len(items_by_distributor),
        "total_items": sum(item.quantity for item in items)
    }


@router.delete("/api/session/{session_id}/items")
async def clear_session(
    session_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """Clear all items from a session with real-time notification"""
    # Enforce access (404 if missing or caller has no relationship).
    session = authz.require_session_access(session_id, current_user, db, write=True)
    if not session.is_active:
        raise HTTPException(status_code=400, detail="Session is no longer active.")

    deleted_count = db.query(models.GlobalSessionItem).filter(
        models.GlobalSessionItem.session_id == session_id
    ).delete()

    sync_ops.record_op(
        db,
        session_id,
        "ClearSession",
        actor_user_id=current_user.id,
    )

    db.commit()

    # Notify clients authorized for this session
    await manager.broadcast_to_session({
        "type": "session_cleared",
        "data": {
            "session_id": session_id,
            "items_cleared": deleted_count,
            "user_id": current_user.id,
            "username": current_user.username
        }
    }, session_id, db)

    return {
        "message": f"Cleared {deleted_count} items from session",
        "count": deleted_count
    }


@router.get("/api/session/{session_id}/items", response_model=List[schemas.GlobalSessionItemDTO])
def get_session_items(
    session_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """Get all items in a session"""
    # Enforce access (404 if missing or caller has no relationship).
    authz.require_session_access(session_id, current_user, db)

    items = db.query(models.GlobalSessionItem).filter(
        models.GlobalSessionItem.session_id == session_id
    ).all()

    result = []
    for item in items:
        item_dict = {
            "id": item.id,
            "session_id": item.session_id,
            "product_id": item.product_id,
            "quantity": item.quantity,
            "version": item.version,
            "created_at": item.created_at,
            "updated_at": item.updated_at,
            "item_uuid": item.item_uuid,
            "product_name": item.product.name,
            "distributor_name": item.product.distributor.distributor_name
        }
        result.append(schemas.GlobalSessionItemDTO(**item_dict))

    return result


@router.get("/api/session/{session_id}/ops", response_model=List[OpDTO])
def get_session_ops(
    session_id: int,
    since: int = 0,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """Pull the per-session op-log entries newer than `since` (ordered by seq).

    Scoped by require_session_access: non-members get 404 (hidden);
    unauthenticated callers get 403 from HTTPBearer.
    """
    authz.require_session_access(session_id, current_user, db)
    return sync_ops.get_ops_since(db, session_id, since)


@router.post("/api/session/{session_id}/items", response_model=schemas.GlobalSessionItemDTO)
async def add_session_item(
    session_id: int,
    item_data: schemas.GlobalSessionItemCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """Add item to session (or increment if exists) with real-time notification"""
    # Validate session exists and the caller has access (404 otherwise).
    session = authz.require_session_access(session_id, current_user, db, write=True)
    key = item_data.idempotency_key
    # Idempotency: a retried add with the same key returns the stored result
    # without re-applying, even if the session has since completed. Access is
    # still checked first; a replay must never bypass authorization.
    stored = sync_ops.check_idempotent(db, key, session_id)
    if stored is not None:
        return stored

    if not session.is_active:
        raise HTTPException(
            status_code=400,
            detail="Session is no longer active.",
        )

    # Validate before writing: SQLite may accept an orphan without FK checks,
    # while PostgreSQL would fail at flush with an unhandled IntegrityError.
    product = db.query(models.Product).filter(
        models.Product.id == item_data.product_id
    ).first()
    if product is None:
        raise HTTPException(status_code=404, detail="Product not found")

    existing = db.query(models.GlobalSessionItem).filter(
        models.GlobalSessionItem.session_id == session_id,
        models.GlobalSessionItem.product_id == item_data.product_id
    ).first()

    if existing:
        # Valid increments can overflow the stored total. Reject before changing
        # the item or reserving the idempotency key.
        if existing.quantity + item_data.quantity > schemas.SESSION_INT_MAX:
            raise HTTPException(status_code=422, detail="Quantity exceeds the supported maximum")
        if existing.version >= schemas.SESSION_INT_MAX:
            raise HTTPException(status_code=409, detail="Item version limit reached")
        # Item exists - increment quantity
        existing.quantity += item_data.quantity
        existing.version += 1
        existing.updated_at = datetime.now(timezone.utc)
        result_item = existing
        action = "updated"
        op_type = "ChangeQty"
    else:
        # New item - create it
        new_item = models.GlobalSessionItem(
            session_id=session_id,
            product_id=item_data.product_id,
            quantity=item_data.quantity,
            item_uuid=str(uuid.uuid4()),
        )
        db.add(new_item)
        result_item = new_item
        action = "added"
        op_type = "AddItem"

    db.flush()
    db.refresh(result_item)

    # Record the op in the per-session op-log.
    sync_ops.record_op(
        db,
        session_id,
        op_type,
        actor_user_id=current_user.id,
        item_uuid=result_item.item_uuid,
        product_id=result_item.product_id,
        qty_delta=item_data.quantity,
        idempotency_key=key,
    )

    # Build the response DTO and store it for idempotent retries.
    response_dto = schemas.GlobalSessionItemDTO(
        id=result_item.id,
        session_id=result_item.session_id,
        product_id=result_item.product_id,
        quantity=result_item.quantity,
        version=result_item.version,
        created_at=result_item.created_at,
        updated_at=result_item.updated_at,
        product_name=product.name if product else None,
        distributor_name=(
            product.distributor.distributor_name
            if product and product.distributor else None
        ),
        item_uuid=result_item.item_uuid,
    )
    sync_ops.store_idempotent(
        db, key, session_id, result_item.id, response_dto.model_dump(mode="json")
    )

    db.commit()
    db.refresh(result_item)

    # Notify clients authorized for this session
    await manager.broadcast_to_session({
        "type": f"session_item_{action}",
        "data": {
            "session_id": session_id,
            "item_id": result_item.id,
            "product_id": result_item.product_id,
            "product_name": product.name if product else "Unknown",
            "quantity": result_item.quantity,
            "version": result_item.version,
            "user_id": current_user.id,
            "username": current_user.username
        }
    }, session_id, db)

    return response_dto


@router.put("/api/session/items/{item_id}", response_model=schemas.GlobalSessionItemDTO)
async def update_session_item(
    item_id: int,
    update_data: schemas.GlobalSessionItemUpdate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """
    Update session item quantity with optimistic locking

    Optimistic Locking Flow:
    1. Client sends current version
    2. Server checks if version matches
    3. If match: update and increment version
    4. If no match: return 409 Conflict
    """
    item = db.query(models.GlobalSessionItem).filter(
        models.GlobalSessionItem.id == item_id
    ).first()

    if not item:
        raise HTTPException(status_code=404, detail="Item not found")

    # Enforce access to the item's session (404 if no access; do not leak).
    session = authz.require_session_access(item.session_id, current_user, db, write=True)
    if not session.is_active:
        raise HTTPException(status_code=400, detail="Session is no longer active.")

    key = update_data.idempotency_key
    # Idempotency: a retried update with the same key returns the stored result.
    # Scope the lookup to the item's session so a key reused across sessions
    # cannot return another session's stored DTO (cross-tenant leak).
    stored = sync_ops.check_idempotent(db, key, item.session_id)
    if stored is not None:
        return stored

    # Check version for optimistic locking
    if item.version != update_data.version:
        raise HTTPException(
            status_code=409,
            detail={
                "error": "Conflict",
                "message": "Item was modified by another user. Please refresh and try again.",
                "current_version": item.version,
                "your_version": update_data.version
            }
        )

    if item.version >= schemas.SESSION_INT_MAX:
        raise HTTPException(status_code=409, detail="Item version limit reached")

    # Update item
    old_quantity = item.quantity
    item.quantity = update_data.quantity
    item.version += 1
    item.updated_at = datetime.now(timezone.utc)
    db.flush()
    db.refresh(item)

    session_id = item.session_id

    # Get product name for notification
    product = db.query(models.Product).filter(
        models.Product.id == item.product_id
    ).first()

    # Record the op in the per-session op-log.
    sync_ops.record_op(
        db,
        session_id,
        "ChangeQty",
        actor_user_id=current_user.id,
        item_uuid=item.item_uuid,
        product_id=item.product_id,
        qty_delta=item.quantity - old_quantity,
        idempotency_key=key,
    )

    # Build response DTO and store it for idempotent retries.
    response_dto = schemas.GlobalSessionItemDTO(
        id=item.id,
        session_id=item.session_id,
        product_id=item.product_id,
        quantity=item.quantity,
        version=item.version,
        created_at=item.created_at,
        updated_at=item.updated_at,
        product_name=product.name if product else None,
        distributor_name=(
            product.distributor.distributor_name
            if product and product.distributor else None
        ),
        item_uuid=item.item_uuid,
    )
    sync_ops.store_idempotent(
        db, key, session_id, item.id, response_dto.model_dump(mode="json")
    )

    db.commit()
    db.refresh(item)

    # Notify clients authorized for this session
    await manager.broadcast_to_session({
        "type": "session_item_updated",
        "data": {
            "session_id": item.session_id,
            "item_id": item.id,
            "product_id": item.product_id,
            "product_name": product.name if product else "Unknown",
            "old_quantity": old_quantity,
            "new_quantity": item.quantity,
            "version": item.version,
            "user_id": current_user.id,
            "username": current_user.username
        }
    }, session_id, db)

    return response_dto


@router.delete("/api/session/items/{item_id}")
async def delete_session_item(
    item_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """Delete session item with real-time notification"""
    item = db.query(models.GlobalSessionItem).filter(
        models.GlobalSessionItem.id == item_id
    ).first()

    if not item:
        raise HTTPException(status_code=404, detail="Item not found")

    # Enforce access to the item's session (404 if no access; do not leak).
    session = authz.require_session_access(item.session_id, current_user, db, write=True)
    if not session.is_active:
        raise HTTPException(status_code=400, detail="Session is no longer active.")

    # Get product name before deleting
    product = db.query(models.Product).filter(
        models.Product.id == item.product_id
    ).first()

    session_id = item.session_id
    product_id = item.product_id
    product_name = product.name if product else "Unknown"
    item_uuid = item.item_uuid

    # Delete item
    db.delete(item)

    # Record the removal as a tombstone op.
    sync_ops.record_op(
        db,
        session_id,
        "RemoveItem",
        actor_user_id=current_user.id,
        item_uuid=item_uuid,
        product_id=product_id,
    )

    db.commit()

    # Notify clients authorized for this session
    await manager.broadcast_to_session({
        "type": "session_item_deleted",
        "data": {
            "session_id": session_id,
            "item_id": item_id,
            "product_id": product_id,
            "product_name": product_name,
            "user_id": current_user.id,
            "username": current_user.username
        }
    }, session_id, db)

    return {"message": "Item deleted successfully", "id": item_id}
