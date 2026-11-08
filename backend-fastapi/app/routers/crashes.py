from typing import List

from fastapi import APIRouter, Depends, Request
from sqlalchemy import String, cast, func
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .. import crash_retention, models, schemas
from ..auth import get_current_admin_user
from ..crash_signatures import crash_signature
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
    if crash.event_id:
        existing = (
            db.query(models.CrashReport)
            .filter(models.CrashReport.event_id == crash.event_id)
            .first()
        )
        if existing:
            return existing

    db_crash = models.CrashReport(
        **crash.model_dump(),
        signature=crash_signature(crash.stacktrace),
    )
    db.add(db_crash)
    # The service has no scheduled jobs, so a new report also clears out old ones.
    crash_retention.purge_if_due(db)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        if crash.event_id:
            existing = (
                db.query(models.CrashReport)
                .filter(models.CrashReport.event_id == crash.event_id)
                .first()
            )
            if existing:
                return existing
        raise
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


@router.get("/api/admin/crashes/groups", response_model=List[schemas.CrashGroupDTO])
def get_crash_groups(
    limit: int = 50,
    instance_limit: int = 20,
    current_user: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Crash signatures ranked by frequency, with recent instances."""

    group_limit = min(max(limit, 1), 100)
    report_limit = min(max(instance_limit, 1), 100)
    report_count = func.count(models.CrashReport.id)
    affected_identity = func.coalesce(
        models.CrashReport.username,
        models.CrashReport.device,
        cast(models.CrashReport.id, String),
    )
    affected_users = func.count(func.distinct(affected_identity))
    triage_score = report_count * affected_users
    aggregate_rows = (
        db.query(
            models.CrashReport.signature,
            report_count.label("count"),
            affected_users.label("affected_users"),
            triage_score.label("triage_score"),
            func.min(models.CrashReport.created_at).label("first_seen"),
            func.max(models.CrashReport.created_at).label("last_seen"),
        )
        .filter(models.CrashReport.signature.isnot(None))
        .group_by(models.CrashReport.signature)
        .order_by(
            triage_score.desc(),
            report_count.desc(),
            func.max(models.CrashReport.created_at).desc(),
        )
        .limit(group_limit)
        .all()
    )
    if not aggregate_rows:
        return []

    signatures = [row.signature for row in aggregate_rows]
    all_reports = (
        db.query(models.CrashReport)
        .filter(models.CrashReport.signature.in_(signatures))
        .order_by(models.CrashReport.created_at.desc(), models.CrashReport.id.desc())
        .all()
    )
    reports_by_signature = {signature: [] for signature in signatures}
    versions_by_signature = {signature: set() for signature in signatures}
    devices_by_signature = {signature: set() for signature in signatures}
    for report in all_reports:
        signature = report.signature
        if report.app_version:
            versions_by_signature[signature].add(report.app_version)
        if report.device:
            devices_by_signature[signature].add(report.device)
        if len(reports_by_signature[signature]) < report_limit:
            reports_by_signature[signature].append(report)

    return [
        schemas.CrashGroupDTO(
            signature=row.signature,
            count=row.count,
            affected_users=row.affected_users,
            triage_score=row.triage_score,
            first_seen=row.first_seen,
            last_seen=row.last_seen,
            app_versions=sorted(versions_by_signature[row.signature]),
            devices=sorted(devices_by_signature[row.signature]),
            reports=reports_by_signature[row.signature],
        )
        for row in aggregate_rows
    ]
