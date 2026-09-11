from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from .. import models
from ..database import get_db


router = APIRouter()


@router.get("/")
def root():
    """Root endpoint"""
    return {"message": "List Manager API is running!"}


@router.get("/health")
def health_check():
    """Health check endpoint"""
    return {"status": "healthy"}


@router.get("/api/stats")
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

    # SECURITY: this endpoint is anonymous (no auth). It must not disclose any
    # tenant-identifying detail of a specific session. With multi-tenant team
    # sessions, returning the globally-most-recent active session's
    # user-controlled `name` (and id) leaked one tenant's data to anonymous
    # callers. Only non-identifying aggregate counts above are exposed; the
    # per-session object is intentionally null here.
    stats["active_session"] = None

    return stats
