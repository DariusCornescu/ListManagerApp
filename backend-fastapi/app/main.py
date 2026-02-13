# app/main.py
from datetime import datetime, timezone
from fastapi import FastAPI, Depends, HTTPException, WebSocket, WebSocketDisconnect
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
from .database import engine, get_db
from . import models, schemas
import logging

models.Base.metadata.create_all(bind=engine)
app = FastAPI(
    title="List Manager API",
    description="Voice-enabled list management backend with WebSocket support",
    version="1.0.0"
)
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
def register(user_data: schemas.UserCreate, db: Session = Depends(get_db)):
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
def login(login_data: schemas.UserLogin, db: Session = Depends(get_db)):
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
    db: Session = Depends(get_db)
):
    """Create new distributor with real-time notification (no auth required for demo)"""
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
    db: Session = Depends(get_db)
):
    """Update distributor with real-time notification (no auth required for demo)"""
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
    db: Session = Depends(get_db)
):
    """Delete distributor with real-time notification (no auth required for demo)"""
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
    db: Session = Depends(get_db)
):
    """Create new product with real-time notification (no auth required for demo)"""
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
    db: Session = Depends(get_db)
):
    """Update product with real-time notification (no auth required for demo)"""
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
    db: Session = Depends(get_db)
):
    """Delete product with real-time notification (no auth required for demo)"""
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
def get_active_session(db: Session = Depends(get_db)):
    """Get currently active session"""
    session = db.query(models.GlobalSession).filter(
        models.GlobalSession.is_active == True
    ).first()

    if not session:
        raise HTTPException(status_code=404, detail="No active session")

    return session


@app.post("/api/session/create", response_model=schemas.GlobalSessionDTO)
async def create_session(
    session_data: schemas.GlobalSessionCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """Create new session with real-time notification"""
    # Deactivate all existing sessions
    db.query(models.GlobalSession).update({"is_active": False})

    # Create new session
    new_session = models.GlobalSession(
        name=session_data.name,
        is_active=True
    )
    db.add(new_session)
    db.commit()
    db.refresh(new_session)

    # Notify all connected clients
    await manager.broadcast({
        "type": "session_created",
        "data": {
            "session_id": new_session.id,
            "session_name": new_session.name,
            "user_id": current_user.id,
            "username": current_user.username
        }
    })

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
    # Get session
    session = db.query(models.GlobalSession).filter(
        models.GlobalSession.id == session_id
    ).first()

    if not session:
        raise HTTPException(status_code=404, detail="Session not found")

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

    # Notify all connected clients
    await manager.broadcast({
        "type": "session_completed",
        "data": {
            "session_id": session_id,
            "total_items": sum(item.quantity for item in items),
            "distributor_count": len(items_by_distributor),
            "pdf_generated": True,
            "user_id": current_user.id,
            "username": current_user.username
        }
    })

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
    deleted_count = db.query(models.GlobalSessionItem).filter(
        models.GlobalSessionItem.session_id == session_id
    ).delete()

    db.commit()

    # Notify all connected clients
    await manager.broadcast({
        "type": "session_cleared",
        "data": {
            "session_id": session_id,
            "items_cleared": deleted_count,
            "user_id": current_user.id,
            "username": current_user.username
        }
    })

    return {
        "message": f"Cleared {deleted_count} items from session",
        "count": deleted_count
    }


# ==================== SESSION ITEMS (Operations) ====================

@app.get("/api/session/{session_id}/items", response_model=List[schemas.GlobalSessionItemDTO])
def get_session_items(session_id: int, db: Session = Depends(get_db)):
    """Get all items in a session"""
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


@app.post("/api/session/{session_id}/items", response_model=schemas.GlobalSessionItemDTO)
async def add_session_item(
    session_id: int,
    item_data: schemas.GlobalSessionItemCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user)
):
    """Add item to session (or increment if exists) with real-time notification"""
    existing = db.query(models.GlobalSessionItem).filter(
        models.GlobalSessionItem.session_id == session_id,
        models.GlobalSessionItem.product_id == item_data.product_id
    ).first()

    if existing:
        # Item exists - increment quantity
        existing.quantity += item_data.quantity
        existing.version += 1
        existing.updated_at = datetime.now(timezone.utc)
        db.commit()
        db.refresh(existing)
        result_item = existing
        action = "updated"
    else:
        # New item - create it
        new_item = models.GlobalSessionItem(
            session_id=session_id,
            product_id=item_data.product_id,
            quantity=item_data.quantity
        )
        db.add(new_item)
        db.commit()
        db.refresh(new_item)
        result_item = new_item
        action = "added"

    # Get product name for notification
    product = db.query(models.Product).filter(
        models.Product.id == item_data.product_id
    ).first()

    # Notify all connected clients
    await manager.broadcast({
        "type": f"session_item_{action}",
        "data": {
            "session_id": session_id,
            "item_id": result_item.id,
            "product_id": result_item.product_id,
            "product_name": product.name if product else "Unknown",
            "quantity": result_item.quantity,
            "user_id": current_user.id,
            "username": current_user.username
        }
    })

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
    db.commit()
    db.refresh(item)

    # Get product name for notification
    product = db.query(models.Product).filter(
        models.Product.id == item.product_id
    ).first()

    # Notify all connected clients
    await manager.broadcast({
        "type": "session_item_updated",
        "data": {
            "session_id": item.session_id,
            "item_id": item.id,
            "product_id": item.product_id,
            "product_name": product.name if product else "Unknown",
            "old_quantity": old_quantity,
            "new_quantity": item.quantity,
            "user_id": current_user.id,
            "username": current_user.username
        }
    })

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

    # Get product name before deleting
    product = db.query(models.Product).filter(
        models.Product.id == item.product_id
    ).first()

    session_id = item.session_id
    product_id = item.product_id
    product_name = product.name if product else "Unknown"

    # Delete item
    db.delete(item)
    db.commit()

    # Notify all connected clients
    await manager.broadcast({
        "type": "session_item_deleted",
        "data": {
            "session_id": session_id,
            "item_id": item_id,
            "product_id": product_id,
            "product_name": product_name,
            "user_id": current_user.id,
            "username": current_user.username
        }
    })

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