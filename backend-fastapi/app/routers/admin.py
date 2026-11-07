import logging
import os
import uuid
from datetime import datetime, timedelta, timezone
from typing import List, Optional

from fastapi import APIRouter, Depends, File, HTTPException, Query, Request, UploadFile
from fastapi.responses import HTMLResponse
from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .. import auth_events, models, schemas
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

    lists_by_team = dict(
        db.query(models.GlobalSession.team_id, func.count())
        .filter(
            models.GlobalSession.team_id.isnot(None),
            models.GlobalSession.completed_at.isnot(None),
        )
        .group_by(models.GlobalSession.team_id)
        .all()
    )
    stores = [
        schemas.StoreDTO(
            id=team.id,
            name=team.name,
            member_count=members,
            created_at=team.created_at,
            lists_completed=lists_by_team.get(team.id, 0),
        )
        for team, members in (
            db.query(models.Team, func.count(models.TeamMember.id))
            .outerjoin(models.TeamMember, models.TeamMember.team_id == models.Team.id)
            .group_by(models.Team.id)
            .order_by(models.Team.name)
            .all()
        )
    ]

    def count_by_day(timestamp, counted=None):
        # func.date() returns 'YYYY-MM-DD' text on SQLite but a date object on
        # Postgres — normalize keys through str() so both backends bucket alike.
        day = func.date(timestamp)
        rows = (
            db.query(day, func.count() if counted is None else func.count(counted.distinct()))
            .filter(timestamp.isnot(None), day >= since.isoformat())
            .group_by(day)
            .all()
        )
        return {str(row_day)[:10]: count for row_day, count in rows}

    # One PDF is made per distributor on a completed list.
    completed_documents = (
        db.query(
            models.GlobalSession.id,
            models.GlobalSession.completed_at,
            models.Product.distributor_id,
        )
        .join(models.GlobalSessionItem, models.GlobalSessionItem.session_id == models.GlobalSession.id)
        .join(models.Product, models.Product.id == models.GlobalSessionItem.product_id)
        .filter(models.GlobalSession.completed_at.isnot(None))
        .distinct()
        .subquery()
    )

    completed_by_day = count_by_day(models.GlobalSession.completed_at)
    items_by_day = count_by_day(models.GlobalSessionItem.created_at)
    users_by_day = count_by_day(models.User.created_at)
    teams_by_day = count_by_day(models.Team.created_at)
    active_by_day = count_by_day(models.SessionOp.created_at, models.SessionOp.actor_user_id)
    crashes_by_day = count_by_day(models.CrashReport.created_at)
    documents_by_day = count_by_day(completed_documents.c.completed_at)

    activity = []
    for offset in range(days):
        key = (since + timedelta(days=offset)).isoformat()
        activity.append(
            schemas.ActivityDayDTO(
                date=key,
                lists_completed=completed_by_day.get(key, 0),
                items_added=items_by_day.get(key, 0),
                users_registered=users_by_day.get(key, 0),
                teams_created=teams_by_day.get(key, 0),
                active_users=active_by_day.get(key, 0),
                crash_reports=crashes_by_day.get(key, 0),
                documents=documents_by_day.get(key, 0),
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
        documents_count=db.query(completed_documents).count(),
        crashes_count=db.query(models.CrashReport).count(),
        devices_count=db.query(models.CrashReport.device)
        .filter(models.CrashReport.device.isnot(None))
        .distinct()
        .count(),
        activity=activity,
    )


@router.get("/api/admin/orders", response_model=schemas.AdminOrderPageDTO)
def list_orders(
    limit: int = Query(default=25, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    current_user: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Completed lists, newest first: who, when and how much, not which products."""
    totals = (
        db.query(
            models.GlobalSessionItem.session_id.label("session_id"),
            func.count(models.GlobalSessionItem.id).label("item_lines"),
            func.sum(models.GlobalSessionItem.quantity).label("total_quantity"),
            func.count(models.Product.distributor_id.distinct()).label("documents"),
        )
        .join(models.Product, models.Product.id == models.GlobalSessionItem.product_id)
        .group_by(models.GlobalSessionItem.session_id)
        .subquery()
    )
    completed = db.query(models.GlobalSession).filter(
        models.GlobalSession.completed_at.isnot(None)
    )
    rows = (
        completed.outerjoin(totals, totals.c.session_id == models.GlobalSession.id)
        .outerjoin(models.Team, models.Team.id == models.GlobalSession.team_id)
        .outerjoin(models.User, models.User.id == models.GlobalSession.owner_user_id)
        .with_entities(
            models.GlobalSession,
            models.Team.name,
            models.User.username,
            totals.c.item_lines,
            totals.c.total_quantity,
            totals.c.documents,
        )
        .order_by(models.GlobalSession.completed_at.desc(), models.GlobalSession.id.desc())
        .limit(limit)
        .offset(offset)
        .all()
    )
    return schemas.AdminOrderPageDTO(
        items=[
            schemas.AdminOrderDTO(
                session_id=session.id,
                name=session.name,
                team_name=team_name,
                owner_username=owner_username,
                completed_at=session.completed_at,
                item_lines=item_lines or 0,
                total_quantity=total_quantity or 0,
                documents=documents or 0,
            )
            for session, team_name, owner_username, item_lines, total_quantity, documents in rows
        ],
        total=completed.count(),
        limit=limit,
        offset=offset,
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


@router.get("/api/admin/users/details", response_model=List[schemas.AdminUserDetailDTO])
def get_user_details(
    current_user: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Every user with teams and recent activity, for the Users section."""
    teams_by_user: dict[int, list[schemas.AdminUserTeamDTO]] = {}
    for user_id, team_id, team_name, role in (
        db.query(
            models.TeamMember.user_id,
            models.Team.id,
            models.Team.name,
            models.TeamMember.role,
        )
        .join(models.Team, models.Team.id == models.TeamMember.team_id)
        .order_by(models.Team.name)
        .all()
    ):
        teams_by_user.setdefault(user_id, []).append(
            schemas.AdminUserTeamDTO(id=team_id, name=team_name, role=role)
        )

    last_activity = dict(
        db.query(models.SessionOp.actor_user_id, func.max(models.SessionOp.created_at))
        .filter(models.SessionOp.actor_user_id.isnot(None))
        .group_by(models.SessionOp.actor_user_id)
        .all()
    )

    # A login is one token family, and only its newest token is still unused.
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    active_logins = dict(
        db.query(
            models.RefreshToken.user_id,
            func.count(models.RefreshToken.family_id.distinct()),
        )
        .filter(
            models.RefreshToken.used_at.is_(None),
            models.RefreshToken.revoked_at.is_(None),
            models.RefreshToken.expires_at > now,
        )
        .group_by(models.RefreshToken.user_id)
        .all()
    )

    return [
        schemas.AdminUserDetailDTO(
            id=user.id,
            username=user.username,
            email=user.email,
            full_name=user.full_name,
            company=user.company,
            role=user.role,
            is_active=user.is_active,
            created_at=user.created_at,
            teams=teams_by_user.get(user.id, []),
            last_activity_at=last_activity.get(user.id),
            active_logins=active_logins.get(user.id, 0),
        )
        for user in db.query(models.User).order_by(func.lower(models.User.username)).all()
    ]


@router.post("/api/admin/users/{user_id}/revoke-tokens")
async def revoke_user_tokens(
    user_id: int,
    request: Request,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user),
):
    """Force-logout a user: every access and refresh token stops working now."""
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")
    revoke_all_user_tokens(user, db)
    auth_events.record(
        db, auth_events.ADMIN_REVOKE, user_id=user_id, actor_user_id=current_user.id, request=request
    )
    db.commit()
    await manager.close_user(user_id)
    return {"message": "Tokens revoked", "user_id": user_id}


@router.post("/api/admin/users/{user_id}/deactivate")
async def deactivate_user(
    user_id: int,
    request: Request,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user),
):
    """Block sign-in and end every login of a user, keeping their data."""
    if user_id == current_user.id:
        raise HTTPException(status_code=400, detail="Cannot deactivate yourself")
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")
    user.is_active = False
    revoke_all_user_tokens(user, db)
    auth_events.record(
        db, auth_events.ADMIN_DEACTIVATE, user_id=user_id, actor_user_id=current_user.id, request=request
    )
    db.commit()
    await manager.close_user(user_id)
    return {"message": "User deactivated", "user_id": user_id, "is_active": False}


@router.post("/api/admin/users/{user_id}/reactivate")
def reactivate_user(
    user_id: int,
    request: Request,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user),
):
    """Allow a deactivated user to sign in again."""
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")
    user.is_active = True
    auth_events.record(
        db, auth_events.ADMIN_REACTIVATE, user_id=user_id, actor_user_id=current_user.id, request=request
    )
    db.commit()
    return {"message": "User reactivated", "user_id": user_id, "is_active": True}


def _user_delete_blockers(user_id: int, db: Session) -> dict[str, int]:
    """Count what deleting this user would break or silently destroy.

    The first four are foreign keys PostgreSQL refuses to break. A personal
    inventory would cascade away with the user, so its live rows block as well.
    """
    counts = {
        "team_memberships": db.query(models.TeamMember)
        .filter(models.TeamMember.user_id == user_id)
        .count(),
        "teams_created": db.query(models.Team)
        .filter(models.Team.created_by == user_id)
        .count(),
        "invites_created": db.query(models.TeamInvite)
        .filter(models.TeamInvite.created_by == user_id)
        .count(),
        "lists": db.query(models.GlobalSession)
        .filter(models.GlobalSession.owner_user_id == user_id)
        .count(),
        "inventory_items": db.query(models.InventoryItem)
        .join(models.Inventory, models.InventoryItem.inventory_id == models.Inventory.id)
        .filter(
            models.Inventory.owner_user_id == user_id,
            models.InventoryItem.deleted_at.is_(None),
        )
        .count(),
    }
    return {name: count for name, count in counts.items() if count}


def _user_has_linked_data(blockers: dict[str, int]) -> HTTPException:
    return HTTPException(
        status_code=409,
        detail={
            "code": "user_has_linked_data",
            "message": "User has linked data. Deactivate the account instead.",
            "blockers": blockers,
        },
    )


@router.delete("/api/admin/users/{user_id}")
def delete_user(
    user_id: int,
    current_user: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db)
):
    """
    Delete a user that has no linked data; answers 409 otherwise
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

    blockers = _user_delete_blockers(user_id, db)
    if blockers:
        raise _user_has_linked_data(blockers)

    db.delete(user)
    try:
        db.commit()
    except IntegrityError:
        # The user gained linked data after the check above.
        db.rollback()
        raise _user_has_linked_data({})

    return {"message": "User deleted successfully", "id": user_id}
