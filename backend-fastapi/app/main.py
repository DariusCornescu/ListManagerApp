# app/main.py
import os
import uuid
from datetime import datetime, timezone
from fastapi import (
    FastAPI,
    Depends,
    HTTPException,
    WebSocket,
    WebSocketDisconnect,
    Request,
    UploadFile,
    File,
    Form,
)
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.util import get_remote_address
from slowapi.errors import RateLimitExceeded
from .websocket_manager import manager
from .auth import decode_access_token
from sqlalchemy.orm import Session
from typing import List
from .auth import (
    get_password_hash,
    verify_password,
    create_access_token,
    get_current_user,
    get_current_admin_user
)
from .config import settings
from .database import engine, get_db
from . import models, schemas
from . import authz
from . import sync_ops
from .schemas_sync import OpDTO
from .schemas_speech import TranscriptionResponse
from .transcription import Transcriber, get_transcriber
from .routers import teams
from typing import Optional
import logging

# Optional Phase 2 clean-cutover migration, gated behind an env flag so normal
# startup is unaffected. Must run BEFORE create_all so the recreated session
# tables have the new owner_user_id / team_id columns.
if os.getenv("PHASE2_CUTOVER") == "run":
    from .migrations.phase2_cutover import run_phase2_cutover
    run_phase2_cutover(engine)

models.Base.metadata.create_all(bind=engine)
app = FastAPI(
    title="List Manager API",
    description="Voice-enabled list management backend with WebSocket support",
    version="1.0.0"
)

# ===== RATE LIMITING =====
limiter = Limiter(key_func=get_remote_address)
# Disable in tests (or any env) via RATE_LIMIT_ENABLED=false
limiter.enabled = settings.RATE_LIMIT_ENABLED
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# Mount the teams router (Phase 2)
app.include_router(teams.router)

logger = logging.getLogger(__name__)

# ===== AUTO-SEED ON STARTUP =====
@app.on_event("startup")
def startup_event():
    """Seed database with initial data on startup"""
    from .seed import seed_database
    from .seed_admin import create_admin_user
    
    logger.info("Running database seed...")
    seed_database()
    
    logger.info("Creating admin user...")
    create_admin_user()
    
    logger.info("Startup complete!")


# ==================== ROOT & HEALTH ====================

@app.get("/")
def root():
    """Root endpoint"""
    return {"message": "List Manager API is running!"}


@app.get("/health")
def health_check():
    """Health check endpoint"""
    return {"status": "healthy"}


# ==================== AUTHENTICATION ====================

@app.post("/api/auth/register", response_model=schemas.UserDTO)
@limiter.limit("5/minute")
def register(request: Request, user_data: schemas.UserCreate, db: Session = Depends(get_db)):
    """
    Register new user
    Public endpoint - anyone can register
    """
    # Check if username exists
    existing_user = db.query(models.User).filter(
        models.User.username == user_data.username
    ).first()

    if existing_user:
        raise HTTPException(
            status_code=400,
            detail="Username already registered"
        )

    # Check if email exists
    existing_email = db.query(models.User).filter(
        models.User.email == user_data.email
    ).first()

    if existing_email:
        raise HTTPException(
            status_code=400,
            detail="Email already registered"
        )

    # Check password length before hashing (bcrypt limit: 72 bytes)
    password_bytes = user_data.password.encode('utf-8')
    if len(password_bytes) > 72:
        raise HTTPException(
            status_code=400,
            detail="Password is too long (maximum 72 bytes allowed). Please use a shorter password."
        )

    # Create new user
    hashed_password = get_password_hash(user_data.password)

    new_user = models.User(
        username=user_data.username,
        email=user_data.email,
        hashed_password=hashed_password,
        role="USER",
        is_active=True
    )

    db.add(new_user)
    db.commit()
    db.refresh(new_user)

    return new_user


@app.post("/api/auth/login", response_model=schemas.Token)
@limiter.limit("5/minute")
def login(request: Request, login_data: schemas.UserLogin, db: Session = Depends(get_db)):
    """
    Login and get JWT token
    Public endpoint
    """
    # Find user
    user = db.query(models.User).filter(
        models.User.username == login_data.username
    ).first()

    if not user:
        raise HTTPException(
            status_code=401,
            detail="Incorrect username or password"
        )

    # Check password length before verification (bcrypt limit: 72 bytes)
    password_bytes = login_data.password.encode('utf-8')
    if len(password_bytes) > 72:
        raise HTTPException(
            status_code=400,
            detail="Password is too long (maximum 72 bytes allowed). Please use a shorter password."
        )
    
    # Verify password
    if not verify_password(login_data.password, user.hashed_password):
        raise HTTPException(
            status_code=401,
            detail="Incorrect username or password"
        )

    # Check if user is active
    if not user.is_active:
        raise HTTPException(
            status_code=403,
            detail="User account is inactive"
        )

    # Create access token
    access_token = create_access_token(
        data={"sub": user.username, "role": user.role}
    )

    return {"access_token": access_token, "token_type": "bearer"}


@app.get("/api/auth/me", response_model=schemas.UserDTO)
def get_current_user_info(current_user: models.User = Depends(get_current_user)):
    """
    Get current user info
    Protected endpoint - requires authentication
    """
    return current_user


@app.put("/api/auth/me", response_model=schemas.UserDTO)
def update_current_user_info(
    update_data: schemas.UserUpdate,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    """
    Update current user's email and/or password
    Protected endpoint - requires authentication
    """
    # Update email if provided (uniqueness check; format validated by EmailStr)
    if update_data.email is not None and update_data.email != current_user.email:
        existing_email = db.query(models.User).filter(
            models.User.email == update_data.email,
            models.User.id != current_user.id
        ).first()
        if existing_email:
            raise HTTPException(
                status_code=400,
                detail="Email already registered"
            )
        current_user.email = update_data.email

    # Update password if provided (enforce bcrypt 72-byte limit, re-hash)
    if update_data.password is not None:
        password_bytes = update_data.password.encode('utf-8')
        if len(password_bytes) > 72:
            raise HTTPException(
                status_code=400,
                detail="Password is too long (maximum 72 bytes allowed). Please use a shorter password."
            )
        current_user.hashed_password = get_password_hash(update_data.password)

    db.commit()
    db.refresh(current_user)

    return current_user


# ==================== DISTRIBUTORS (CRUD) ====================

@app.get("/api/catalog/distributors", response_model=List[schemas.DistributorDTO])
def get_distributors(db: Session = Depends(get_db)):
    """Get all distributors"""
    distributors = db.query(models.Distributor).all()
    return distributors


@app.get("/api/catalog/distributors/{distributor_id}", response_model=schemas.DistributorDTO)
def get_distributor(distributor_id: int, db: Session = Depends(get_db)):
    """Get distributor by ID"""
    distributor = db.query(models.Distributor).filter(
        models.Distributor.id == distributor_id
    ).first()

    if not distributor:
        raise HTTPException(status_code=404, detail="Distributor not found")

    return distributor


@app.post("/api/catalog/distributors", response_model=schemas.DistributorDTO)
async def create_distributor(
    distributor: schemas.DistributorCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user)
):
    """Create new distributor with real-time notification (admin only)"""
    # Check for duplicates
    existing = db.query(models.Distributor).filter(
        models.Distributor.distributor_name == distributor.distributor_name
    ).first()

    if existing:
        raise HTTPException(
            status_code=400,
            detail=f"Distributor '{distributor.distributor_name}' already exists"
        )

    # Create distributor
    db_distributor = models.Distributor(**distributor.model_dump())
    db.add(db_distributor)
    db.commit()
    db.refresh(db_distributor)

    # Notify all connected clients
    await manager.broadcast({
        "type": "distributor_created",
        "data": {
            "distributor_id": db_distributor.id,
            "distributor_name": db_distributor.distributor_name,
            "user_id": 0,
            "username": "anonymous"
        }
    })

    return db_distributor


@app.put("/api/catalog/distributors/{distributor_id}", response_model=schemas.DistributorDTO)
async def update_distributor(
    distributor_id: int,
    distributor_update: schemas.DistributorCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user)
):
    """Update distributor with real-time notification (admin only)"""
    distributor = db.query(models.Distributor).filter(
        models.Distributor.id == distributor_id
    ).first()

    if not distributor:
        raise HTTPException(status_code=404, detail="Distributor not found")

    # Update distributor
    distributor.distributor_name = distributor_update.distributor_name
    db.commit()
    db.refresh(distributor)

    # Notify all connected clients
    await manager.broadcast({
        "type": "distributor_updated",
        "data": {
            "distributor_id": distributor.id,
            "distributor_name": distributor.distributor_name,
            "user_id": 0,
            "username": "anonymous"
        }
    })

    return distributor


@app.delete("/api/catalog/distributors/{distributor_id}")
async def delete_distributor(
    distributor_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user)
):
    """Delete distributor with real-time notification (admin only)"""
    distributor = db.query(models.Distributor).filter(
        models.Distributor.id == distributor_id
    ).first()

    if not distributor:
        raise HTTPException(status_code=404, detail="Distributor not found")

    # Delete distributor (CASCADE will delete associated products)
    db.delete(distributor)
    db.commit()

    # Notify all connected clients
    await manager.broadcast({
        "type": "distributor_deleted",
        "data": {
            "distributor_id": distributor_id,
            "user_id": 0,
            "username": "anonymous"
        }
    })

    return {"message": "Distributor deleted successfully", "id": distributor_id}


# ==================== PRODUCTS (CRUD) ====================

@app.get("/api/catalog/products", response_model=List[schemas.ProductDTO])
def get_products(search: str = None, db: Session = Depends(get_db)):
    """Get all products, optionally filter by search query"""
    query = db.query(models.Product)
    if search:
        search_filter = f"%{search}%"
        query = query.filter(
            (models.Product.name.ilike(search_filter)) |
            (models.Product.aliases.ilike(search_filter))
        )

    return query.all()


@app.get("/api/catalog/products/{product_id}", response_model=schemas.ProductDTO)
def get_product(product_id: int, db: Session = Depends(get_db)):
    """Get product by ID"""
    product = db.query(models.Product).filter(
        models.Product.id == product_id
    ).first()

    if not product:
        raise HTTPException(status_code=404, detail="Product not found")

    return product


@app.post("/api/catalog/products", response_model=schemas.ProductDTO)
async def create_product(
    product: schemas.ProductCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user)
):
    """Create new product with real-time notification (admin only)"""
    # Create product
    db_product = models.Product(**product.model_dump())
    db.add(db_product)
    db.commit()
    db.refresh(db_product)

    # Notify all connected clients
    await manager.broadcast({
        "type": "product_created",
        "data": {
            "product_id": db_product.id,
            "product_name": db_product.name,
            "distributor_id": db_product.distributor_id,
            "aliases": db_product.aliases,
            "user_id": 0,
            "username": "anonymous"
        }
    })

    return db_product


@app.put("/api/catalog/products/{product_id}", response_model=schemas.ProductDTO)
async def update_product(
    product_id: int,
    product_update: schemas.ProductCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user)
):
    """Update product with real-time notification (admin only)"""
    product = db.query(models.Product).filter(
        models.Product.id == product_id
    ).first()

    if not product:
        raise HTTPException(status_code=404, detail="Product not found")

    # Update product fields
    product.name = product_update.name
    product.distributor_id = product_update.distributor_id
    product.aliases = product_update.aliases

    db.commit()
    db.refresh(product)

    # Notify all connected clients
    await manager.broadcast({
        "type": "product_updated",
        "data": {
            "product_id": product.id,
            "product_name": product.name,
            "distributor_id": product.distributor_id,
            "aliases": product.aliases,
            "user_id": 0,
            "username": "anonymous"
        }
    })

    return product


@app.delete("/api/catalog/products/{product_id}")
async def delete_product(
    product_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user)
):
    """Delete product with real-time notification (admin only)"""
    product = db.query(models.Product).filter(
        models.Product.id == product_id
    ).first()

    if not product:
        raise HTTPException(status_code=404, detail="Product not found")

    # Delete product
    db.delete(product)
    db.commit()

    # Notify all connected clients
    await manager.broadcast({
        "type": "product_deleted",
        "data": {
            "product_id": product_id,
            "user_id": 0,
            "username": "anonymous"
        }
    })

    return {"message": "Product deleted successfully", "id": product_id}


# ==================== SESSIONS (Management) ====================

@app.get("/api/session/active", response_model=schemas.GlobalSessionDTO)
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


@app.post("/api/session/create", response_model=schemas.GlobalSessionDTO)
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
        )

    db.add(new_session)
    db.commit()
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


@app.post("/api/session/{session_id}/complete")
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
            distributor_name=distributor_name
        )

        items_by_distributor[distributor_name].append(item_dto)

    # Mark session as completed
    session.is_active = False
    session.completed_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(session)

    # Notify clients authorized for this session
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


@app.delete("/api/session/{session_id}/items")
async def clear_session(
    session_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """Clear all items from a session with real-time notification"""
    # Enforce access (404 if missing or caller has no relationship).
    authz.require_session_access(session_id, current_user, db, write=True)

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


# ==================== SESSION ITEMS (Operations) ====================

@app.get("/api/session/{session_id}/items", response_model=List[schemas.GlobalSessionItemDTO])
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
            "product_name": item.product.name,
            "distributor_name": item.product.distributor.distributor_name
        }
        result.append(schemas.GlobalSessionItemDTO(**item_dict))

    return result


@app.get("/api/session/{session_id}/ops", response_model=List[OpDTO])
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


@app.post("/api/session/{session_id}/items", response_model=schemas.GlobalSessionItemDTO)
async def add_session_item(
    session_id: int,
    item_data: schemas.GlobalSessionItemCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """Add item to session (or increment if exists) with real-time notification"""
    # Validate session exists and the caller has access (404 otherwise).
    authz.require_session_access(session_id, current_user, db, write=True)

    key = item_data.idempotency_key
    # Idempotency: a retried add with the same key returns the stored result
    # without re-applying (no double-counting).
    stored = sync_ops.check_idempotent(db, key)
    if stored is not None:
        return stored

    existing = db.query(models.GlobalSessionItem).filter(
        models.GlobalSessionItem.session_id == session_id,
        models.GlobalSessionItem.product_id == item_data.product_id
    ).first()

    if existing:
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

    # Get product name for notification / response
    product = db.query(models.Product).filter(
        models.Product.id == item_data.product_id
    ).first()

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

    return result_item


@app.put("/api/session/items/{item_id}", response_model=schemas.GlobalSessionItemDTO)
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
    authz.require_session_access(item.session_id, current_user, db, write=True)

    key = update_data.idempotency_key
    # Idempotency: a retried update with the same key returns the stored result.
    stored = sync_ops.check_idempotent(db, key)
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

    return item


@app.delete("/api/session/items/{item_id}")
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
    authz.require_session_access(item.session_id, current_user, db, write=True)

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


# ==================== STATISTICS ====================

@app.get("/api/stats")
def get_stats(db: Session = Depends(get_db)):
    """Get application statistics"""
    stats = {
        "distributors_count": db.query(models.Distributor).count(),
        "products_count": db.query(models.Product).count(),
        "sessions_count": db.query(models.GlobalSession).count(),
        "active_sessions_count": db.query(models.GlobalSession).filter(
            models.GlobalSession.is_active == True
        ).count(),
        "total_session_items": db.query(models.GlobalSessionItem).count(),
    }

    # Get active session if exists
    active_session = db.query(models.GlobalSession).filter(
        models.GlobalSession.is_active == True
    ).first()

    if active_session:
        active_items_count = db.query(models.GlobalSessionItem).filter(
            models.GlobalSessionItem.session_id == active_session.id
        ).count()

        stats["active_session"] = {
            "id": active_session.id,
            "name": active_session.name,
            "items_count": active_items_count,
            "created_at": active_session.created_at.isoformat()
        }
    else:
        stats["active_session"] = None

    return stats


# ==================== ADMIN ENDPOINTS ====================

@app.get("/api/admin/users", response_model=List[schemas.UserDTO])
def get_all_users(
    current_user: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db)
):
    """
    Get all users
    Admin only endpoint
    """
    users = db.query(models.User).all()
    return users


@app.delete("/api/admin/users/{user_id}")
def delete_user(
    user_id: int,
    current_user: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db)
):
    """
    Delete user
    Admin only endpoint
    """
    if user_id == current_user.id:
        raise HTTPException(
            status_code=400,
            detail="Cannot delete yourself"
        )

    user = db.query(models.User).filter(models.User.id == user_id).first()

    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    db.delete(user)
    db.commit()

    return {"message": "User deleted successfully", "id": user_id}


# ==================== PROTECTED ENDPOINT (Example) ====================

@app.get("/api/protected/test")
def protected_test(current_user: models.User = Depends(get_current_user)):
    """
    Example protected endpoint
    Requires authentication
    """
    return {
        "message": f"Hello {current_user.username}!",
        "user_id": current_user.id,
        "role": current_user.role
    }


# ==================== SPEECH TRANSCRIPTION ====================

@app.post("/api/speech/transcribe", response_model=TranscriptionResponse)
@limiter.limit("20/minute")
async def transcribe_audio(
    request: Request,
    file: UploadFile = File(...),
    language: Optional[str] = Form(None),
    current_user: models.User = Depends(get_current_user),
    transcriber: Transcriber = Depends(get_transcriber),
):
    """Transcribe an uploaded audio file to text (server-side, audio -> text only).

    Auth required. The provider is injected via `get_transcriber` so it is
    swappable and mockable. Ranking/matching of the text stays on-device.
    """
    # Validate content type (must be some audio/* MIME type).
    if not (file.content_type or "").startswith("audio/"):
        raise HTTPException(
            status_code=415,
            detail="Unsupported media type: an audio/* file is required.",
        )

    audio = await file.read()

    # Empty payload is a client error.
    if not audio:
        raise HTTPException(status_code=400, detail="Empty audio file.")

    # Enforce max size.
    if len(audio) > settings.MAX_AUDIO_BYTES:
        raise HTTPException(
            status_code=413,
            detail=(
                f"Audio file too large (max {settings.MAX_AUDIO_BYTES} bytes)."
            ),
        )

    try:
        result = await transcriber.transcribe(
            audio, file.filename or "audio", language
        )
    except HTTPException:
        raise
    except Exception as exc:
        # Provider/upstream failure (network, non-2xx, missing key, etc.).
        # Never leak the API key or raw upstream internals.
        logger.error("Transcription provider error: %s", type(exc).__name__)
        raise HTTPException(
            status_code=502,
            detail="Transcription provider error.",
        )

    return TranscriptionResponse(
        text=result.text,
        language=result.language,
        model=result.model,
        provider=result.provider,
    )


# ==================== WEBSOCKET ENDPOINT ====================

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket, token: str = None):
    """
    WebSocket endpoint for real-time updates

    Usage:
    ws://localhost:8000/ws?token=<jwt_token>

    Authentication:
    - Token passed as query parameter
    - JWT token validated before connection accepted

    Message Types:
    - connection: Initial connection status
    - pong: Response to ping messages
    - distributor_created/updated/deleted: Distributor changes
    - product_created/updated/deleted: Product changes
    - session_created/completed/cleared: Session lifecycle
    - session_item_added/updated/deleted: Session item changes

    Close Codes:
    - 4001: Authentication required
    - 4002: User not found
    - 4003: Invalid token
    """

    # Authenticate user
    if not token:
        await websocket.close(code=4001, reason="Authentication required")
        return

    try:
        # Decode JWT token
        payload = decode_access_token(token)
        username = payload.get("sub")

        # Get user from database
        from .database import SessionLocal

        db = SessionLocal()
        user = db.query(models.User).filter(models.User.username == username).first()
        db.close()

        if not user:
            await websocket.close(code=4002, reason="User not found")
            return

        user_id = user.id

    except Exception as e:
        logger.error(f"WebSocket auth failed: {e}")
        await websocket.close(code=4003, reason="Invalid token")
        return

    # Connect user
    await manager.connect(websocket, user_id)

    try:
        # Send welcome message
        await websocket.send_json({
            "type": "connection",
            "status": "connected",
            "message": f"Welcome {username}!",
            "user_id": user_id
        })

        # Keep connection alive and handle incoming messages
        while True:
            # Receive messages from client (optional - for ping/pong)
            data = await websocket.receive_text()

            # Handle ping
            if data == "ping":
                await websocket.send_json({
                    "type": "pong",
                    "timestamp": datetime.now(timezone.utc).isoformat()
                })

            logger.debug(f"Received from user {user_id}: {data}")

    except WebSocketDisconnect:
        manager.disconnect(websocket, user_id)
        logger.info(f"User {user_id} disconnected")

    except Exception as e:
        logger.error(f"WebSocket error for user {user_id}: {e}")
        manager.disconnect(websocket, user_id)