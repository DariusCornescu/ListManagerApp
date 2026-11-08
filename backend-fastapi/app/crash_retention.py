"""Delete crash reports older than CRASH_REPORT_RETENTION_DAYS.

Reports can be sent without an account and carry a username, and the table would
otherwise grow for ever on a disk that can only get bigger. The deployment has no
scheduled jobs, so the purge runs at startup and at most once a day when a new
report arrives. A retention of 0 days turns the purge off.
"""

import logging
import time
from datetime import datetime, timedelta, timezone
from typing import Optional

from sqlalchemy.orm import Session

from . import models
from .config import settings


logger = logging.getLogger(__name__)

_PURGE_INTERVAL_SECONDS = 24 * 60 * 60
_last_purge: Optional[float] = None


def _cutoff(days: int) -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(days=days)


def purge_old(db: Session) -> int:
    """Delete reports past the retention period and return how many. Caller commits."""
    global _last_purge
    _last_purge = time.monotonic()
    days = settings.CRASH_REPORT_RETENTION_DAYS
    if days <= 0:
        return 0
    removed = (
        db.query(models.CrashReport)
        .filter(models.CrashReport.created_at < _cutoff(days))
        .delete(synchronize_session=False)
    )
    if removed:
        logger.info("Purged %d crash reports older than %d days", removed, days)
    return removed


def purge_if_due(db: Session) -> int:
    """Purge at most once a day per process. Caller commits."""
    if _last_purge is not None and time.monotonic() - _last_purge <= _PURGE_INTERVAL_SECONDS:
        return 0
    return purge_old(db)


def purge_at_startup() -> None:
    """Run one purge in its own session when the app starts."""
    from .database import SessionLocal

    db = SessionLocal()
    try:
        purge_old(db)
        db.commit()
    except Exception:
        db.rollback()
        logger.exception("Crash report purge failed")
    finally:
        db.close()
