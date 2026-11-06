import logging
import os
import uuid
from datetime import datetime, timedelta, timezone
from typing import List, Optional

from fastapi import APIRouter, Depends, File, HTTPException, Query, Request, UploadFile
from fastapi.responses import HTMLResponse
from sqlalchemy import func
from sqlalchemy.orm import Session

from .. import models, schemas
from ..auth import get_current_admin_user, revoke_all_user_tokens
from ..config import settings
from ..database import get_db
from ..limiting import limiter
from ..websocket_manager import manager
from ..services.catalog_import import CatalogImportError, import_catalog_csv


logger = logging.getLogger(__name__)
_ADMIN_HTML_PATH = os.path.join(
    os.path.dirname(os.path.dirname(__file__)), "static", "admin.html"
)


router = APIRouter()


@router.post("/api/admin/catalog/import", response_model=schemas.ImportResultDTO)
@limiter.limit("5/minute")
async def import_catalog(
    request: Request,
    file: UploadFile = File(...),
    dry_run: bool = True,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user),
):
    """Upsert the product catalog from a CSV (admin only).

    dry_run=True (default) returns a preview without writing; dry_run=False commits.
    """
    raw = await file.read()
    if len(raw) > settings.MAX_IMPORT_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"File too large (max {settings.MAX_IMPORT_BYTES // (1024 * 1024)} MB)",
        )
    try:
        content = raw.decode("utf-8")
    except UnicodeDecodeError:
        raise HTTPException(status_code=400, detail="File must be UTF-8 encoded CSV")

    try:
        result = import_catalog_csv(db, content, commit=not dry_run)
    except CatalogImportError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except Exception:
        db.rollback()
        logger.exception("Catalog import failed")
        raise HTTPException(status_code=500, detail="Import failed")

    if result.committed and (result.new or result.updated):
        # A new id each time: the Android socket's StateFlow drops equal values.
        await manager.broadcast(
            {"type": "catalog_changed", "data": {"change_id": uuid.uuid4().hex}}
        )

    return schemas.ImportResultDTO(
        new=result.new,
        updated=result.updated,
        unchanged=result.unchanged,
        committed=result.committed,
        errors=[schemas.ImportRowErrorDTO(line=e.line, reason=e.reason) for e in result.errors],
    )


def _like_contains(term: str) -> str:
    """Build a LIKE pattern that treats the term's own wildcards as text."""
    escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return f"%{escaped}%"


@router.get("/api/admin/catalog/products", response_model=schemas.AdminProductPageDTO)
def list_catalog_products(
    q: Optional[str] = Query(default=None, max_length=200),
    distributor_id: Optional[int] = None,
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user),
):
    """One page of the catalog, with purchase prices (admin only)."""
    query = db.query(models.Product)
    if distributor_id is not None:
        query = query.filter(models.Product.distributor_id == distributor_id)
    term = (q or "").strip()
    if term:
        pattern = _like_contains(term)
        query = query.filter(
            models.Product.name.ilike(pattern, escape="\\")
            | models.Product.aliases.ilike(pattern, escape="\\")
        )

    items = (
        query.order_by(func.lower(models.Product.name), models.Product.id)
        .limit(limit)
        .offset(offset)
        .all()
    )
    return schemas.AdminProductPageDTO(
        items=items, total=query.count(), limit=limit, offset=offset
    )


@router.get("/admin", response_class=HTMLResponse)
def admin_page():
    """Serve the self-contained catalog admin page."""
    with open(_ADMIN_HTML_PATH, encoding="utf-8") as f:
        return f.read()


@router.get("/api/admin/dashboard", response_model=schemas.AdminDashboardDTO)
def get_admin_dashboard(
    days: int = 30,
    current_user: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Overview for the /admin page: stores (= teams) with headcount, global
    counters, and a per-day activity series for the chart. Computed live on
    every call, so the numbers are always current."""
    days = min(max(days, 7), 90)
    since = (datetime.now(timezone.utc) - timedelta(days=days - 1)).date()

    stores = [
        schemas.StoreDTO(id=team.id, name=team.name, member_count=members)
        for team, members in (
            db.query(models.Team, func.count(models.TeamMember.id))
            .outerjoin(models.TeamMember, models.TeamMember.team_id == models.Team.id)
            .group_by(models.Team.id)
            .order_by(models.Team.name)
            .all()
        )
    ]

    # func.date() returns 'YYYY-MM-DD' text on SQLite but a date object on
    # Postgres — normalize keys through str() so both backends bucket alike.
    completed_day = func.date(models.GlobalSession.completed_at)
    completed_by_day = {
        str(day)[:10]: count
        for day, count in (
            db.query(completed_day, func.count())
            .filter(models.GlobalSession.completed_at.isnot(None))
            .filter(completed_day >= since.isoformat())
            .group_by(completed_day)
            .all()
        )
    }
    items_day = func.date(models.GlobalSessionItem.created_at)
    items_by_day = {
        str(day)[:10]: count
        for day, count in (
            db.query(items_day, func.count())
            .filter(items_day >= since.isoformat())
            .group_by(items_day)
            .all()
        )
    }

    activity = []
    for offset in range(days):
        key = (since + timedelta(days=offset)).isoformat()
        activity.append(
            schemas.ActivityDayDTO(
                date=key,
                lists_completed=completed_by_day.get(key, 0),
                items_added=items_by_day.get(key, 0),
            )
        )

    return schemas.AdminDashboardDTO(
        stores=stores,
        users_count=db.query(models.User).count(),
        products_count=db.query(models.Product).count(),
        distributors_count=db.query(models.Distributor).count(),
        lists_completed_count=db.query(models.GlobalSession)
        .filter(models.GlobalSession.completed_at.isnot(None))
        .count(),
        crashes_count=db.query(models.CrashReport).count(),
        devices_count=db.query(models.CrashReport.device)
        .filter(models.CrashReport.device.isnot(None))
        .distinct()
        .count(),
        activity=activity,
    )


@router.get("/api/admin/users", response_model=List[schemas.UserDTO])
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


@router.post("/api/admin/users/{user_id}/revoke-tokens")
async def revoke_user_tokens(
    user_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user),
):
    """Force-logout a user: every access and refresh token stops working now."""
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")
    revoke_all_user_tokens(user, db)
    db.commit()
    await manager.close_user(user_id)
    return {"message": "Tokens revoked", "user_id": user_id}


@router.delete("/api/admin/users/{user_id}")
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
