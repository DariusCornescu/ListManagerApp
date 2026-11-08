"""Record sign-in, sign-out and admin account events for the admin activity view.

Nothing here stores an IP address, a typed username or a token. Rows older than
AUTH_EVENT_RETENTION_DAYS are purged at startup and at most once a day on write,
because the deployment has no scheduled jobs.
"""

import logging
import time
from datetime import datetime, timedelta, timezone
from typing import Optional

from sqlalchemy.orm import Session

from . import models
from .config import settings


logger = logging.getLogger(__name__)

LOGIN = "login"
LOGIN_FAILED = "login_failed"
LOGOUT = "logout"
LOGOUT_ALL = "logout_all"
PASSWORD_CHANGED = "password_changed"
TOKEN_REUSE = "token_reuse"
ADMIN_REVOKE = "admin_revoke"
ADMIN_DEACTIVATE = "admin_deactivate"
ADMIN_REACTIVATE = "admin_reactivate"
ADMIN_ROLE_CHANGE = "admin_role_change"

EVENT_TYPES = (
    LOGIN, LOGIN_FAILED, LOGOUT, LOGOUT_ALL, PASSWORD_CHANGED,
    TOKEN_REUSE, ADMIN_REVOKE, ADMIN_DEACTIVATE, ADMIN_REACTIVATE,
    ADMIN_ROLE_CHANGE,
)

_PURGE_INTERVAL_SECONDS = 24 * 60 * 60
_last_purge: Optional[float] = None


def client_from(request) -> str:
    """Name the kind of client from its User-Agent; the header itself is not kept."""
    if request is None:
        return "other"
    agent = (request.headers.get("user-agent") or "").lower()
    if "okhttp" in agent:
        return "android"
    if "dart" in agent:
        return "flutter"
    if "mozilla" in agent:
        return "web"
    return "other"


def _cutoff() -> datetime:
    days = max(1, settings.AUTH_EVENT_RETENTION_DAYS)
    return datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(days=days)


def purge_old(db: Session) -> int:
    """Delete events past the retention period. Caller commits."""
    global _last_purge
    _last_purge = time.monotonic()
    return (
        db.query(models.AuthEvent)
        .filter(models.AuthEvent.created_at < _cutoff())
        .delete(synchronize_session=False)
    )


def record(
    db: Session,
    event_type: str,
    *,
    user_id: Optional[int] = None,
    actor_user_id: Optional[int] = None,
    reason: Optional[str] = None,
    request=None,
) -> models.AuthEvent:
    """Add one event to the caller's transaction. Caller commits."""
    if event_type not in EVENT_TYPES:
        raise ValueError(f"Unknown auth event type: {event_type}")
    event = models.AuthEvent(
        user_id=user_id,
        actor_user_id=actor_user_id,
        event_type=event_type,
        reason=reason,
        client=client_from(request),
        created_at=datetime.now(timezone.utc).replace(tzinfo=None),
    )
    db.add(event)
    if _last_purge is None or time.monotonic() - _last_purge > _PURGE_INTERVAL_SECONDS:
        purge_old(db)
    return event


def purge_at_startup() -> None:
    """Run one purge in its own session when the app starts."""
    from .database import SessionLocal

    db = SessionLocal()
    try:
        removed = purge_old(db)
        db.commit()
        if removed:
            logger.info("Purged %d auth events past retention", removed)
    except Exception:
        db.rollback()
        logger.exception("Auth event purge failed")
    finally:
        db.close()
