from typing import List

from fastapi import APIRouter, Depends, Request
from sqlalchemy.orm import Session

from .. import models, schemas
from ..auth import get_current_admin_user
from ..database import get_db
from ..limiting import limiter


router = APIRouter()


@router.post("/api/crashes", response_model=schemas.CrashReportDTO)
@limiter.limit("5/minute")
def report_crash(
    request: Request,
    crash: schemas.CrashReportCreate,
    db: Session = Depends(get_db)
):
    """Ingest a crash report from the Android app (no auth — the app may not
    be logged in when it crashes; rate limit + size caps bound abuse)."""
    db_crash = models.CrashReport(**crash.model_dump())
    db.add(db_crash)
    db.commit()
    db.refresh(db_crash)
    return db_crash


@router.get("/api/admin/crashes", response_model=List[schemas.CrashReportDTO])
def get_crashes(
    limit: int = 50,
    current_user: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db)
):
    """Latest crash reports, newest first (admin only)."""
    capped = min(max(limit, 1), 200)
    return (
        db.query(models.CrashReport)
        .order_by(models.CrashReport.id.desc())
        .limit(capped)
        .all()
    )
