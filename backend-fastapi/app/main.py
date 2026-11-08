import logging
import os
from datetime import datetime, timezone

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded

from .auth import resolve_token_user
from .config import parse_cors_origins, settings
from .limiting import limiter
from .logging_setup import configure_logging
from .routers import admin, auth, catalog, crashes, inventory, presence, sessions, speech, stats, teams
from .websocket_manager import manager


logger = logging.getLogger(__name__)


def _run_migrations_to_head() -> None:
    from alembic.config import Config
    from alembic import command
    from .database import DATABASE_URL

    backend_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    alembic_cfg = Config(os.path.join(backend_root, "alembic.ini"))
    alembic_cfg.set_main_option("sqlalchemy.url", DATABASE_URL)
    # alembic.ini's logging setup would disable every logger that already exists, uvicorn's included.
    alembic_cfg.attributes["configure_logger"] = False
    command.upgrade(alembic_cfg, "head")


configure_logging()
_run_migrations_to_head()
app = FastAPI(
    title="List Manager API",
    description="Voice-enabled list management backend with WebSocket support",
    version="1.0.0",
)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)
# Bearer tokens only, no cookies, so credentials stay off.
app.add_middleware(
    CORSMiddleware,
    allow_origins=parse_cors_origins(settings.CORS_ORIGINS),
    allow_credentials=False,
    allow_methods=["GET", "POST", "PUT", "DELETE"],
    allow_headers=["Authorization", "Content-Type"],
)
# The full catalog is over 600 KB, and phones download it on mobile data.
app.add_middleware(GZipMiddleware, minimum_size=1024)

for router in (
    teams.router,
    stats.router,
    auth.router,
    catalog.router,
    sessions.router,
    crashes.router,
    admin.router,
    presence.router,
    speech.router,
    inventory.router,
):
    app.include_router(router)


@app.on_event("startup")
def startup_event():
    """Seed database with initial data on startup"""
    from .seed import seed_database
    from .seed_admin import create_admin_user
    
    logger.info("Running database seed...")
    seed_database()
    
    logger.info("Creating admin user...")
    create_admin_user()

    from .auth_events import purge_at_startup
    purge_at_startup()

    logger.info("Startup complete!")


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
        from .database import SessionLocal

        db = SessionLocal()
        try:
            user = resolve_token_user(token, db)
            if user is None:
                await websocket.close(code=4002, reason="User not found")
                return
            user_id = user.id
            username = user.username
        finally:
            db.close()

    except Exception as e:
        logger.error(f"WebSocket auth failed: {e}")
        await websocket.close(code=4003, reason="Invalid token")
        return

    # Connect user
    await manager.connect(websocket, user_id, username)

    try:
        # Send welcome message
        await websocket.send_json({
            "type": "connection",
            "status": "connected",
            "message": f"Welcome {username}!",
            "user_id": user_id
        })

        # Everyone (including the newcomer) learns who is online now
        await manager.broadcast_presence()

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
        await manager.broadcast_presence()

    except Exception as e:
        logger.error(f"WebSocket error for user {user_id}: {e}")
        manager.disconnect(websocket, user_id)
        await manager.broadcast_presence()
