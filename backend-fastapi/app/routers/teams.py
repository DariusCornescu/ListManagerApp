# app/routers/teams.py
import secrets
from datetime import datetime, timedelta, timezone
from typing import List

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from ..auth import get_current_user
from ..database import get_db
from .. import models
from ..authz import (
    require_team_member,
    require_team_admin,
    get_user_team_ids,
)
from ..schemas_teams import (
    TeamCreate,
    TeamDTO,
    TeamMemberDTO,
    InviteCreate,
    InviteDTO,
    InviteAccept,
    MemberUpdate,
)

router = APIRouter(prefix="/api")


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _aware(dt: datetime) -> datetime:
    """Treat naive datetimes (SQLite returns naive) as UTC for comparison."""
    if dt is not None and dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt


@router.post("/teams", response_model=TeamDTO)
def create_team(
    payload: TeamCreate,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    team = models.Team(name=payload.name, created_by=current_user.id)
    db.add(team)
    db.flush()

    membership = models.TeamMember(
        team_id=team.id,
        user_id=current_user.id,
        role="admin",
    )
    db.add(membership)
    db.commit()
    db.refresh(team)
    return team


@router.get("/teams", response_model=List[TeamDTO])
def list_teams(
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    team_ids = get_user_team_ids(current_user, db)
    if not team_ids:
        return []
    return (
        db.query(models.Team)
        .filter(models.Team.id.in_(team_ids))
        .all()
    )


@router.get("/teams/{team_id}", response_model=TeamDTO)
def get_team(
    team_id: int,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    require_team_member(team_id, current_user, db)
    team = db.query(models.Team).filter(models.Team.id == team_id).first()
    if team is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Team not found")
    return team


@router.put("/teams/{team_id}", response_model=TeamDTO)
def update_team(
    team_id: int,
    payload: TeamCreate,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    require_team_admin(team_id, current_user, db)
    team = db.query(models.Team).filter(models.Team.id == team_id).first()
    if team is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Team not found")
    team.name = payload.name
    db.commit()
    db.refresh(team)
    return team


@router.delete("/teams/{team_id}")
def delete_team(
    team_id: int,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    require_team_admin(team_id, current_user, db)
    team = db.query(models.Team).filter(models.Team.id == team_id).first()
    if team is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Team not found")
    db.delete(team)
    db.commit()
    return {"message": "Team deleted", "id": team_id}


@router.post(
    "/teams/{team_id}/invites",
    response_model=InviteDTO,
)
def create_invite(
    team_id: int,
    payload: InviteCreate,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    require_team_admin(team_id, current_user, db)

    code = secrets.token_urlsafe(24)
    expires_at = _now() + timedelta(days=7)
    invite = models.TeamInvite(
        team_id=team_id,
        code=code,
        role=payload.role or "member",
        created_by=current_user.id,
        expires_at=expires_at,
    )
    db.add(invite)
    db.commit()
    db.refresh(invite)
    return invite


@router.post("/teams/invites/{code}/accept", response_model=TeamMemberDTO)
def accept_invite(
    code: str,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    invite = (
        db.query(models.TeamInvite)
        .filter(models.TeamInvite.code == code)
        .first()
    )
    if invite is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Invalid invite code")
    if invite.accepted_at is not None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invite already accepted")
    if _aware(invite.expires_at) < _now():
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invite expired")

    existing = (
        db.query(models.TeamMember)
        .filter(
            models.TeamMember.team_id == invite.team_id,
            models.TeamMember.user_id == current_user.id,
        )
        .first()
    )
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Already a member of this team",
        )

    membership = models.TeamMember(
        team_id=invite.team_id,
        user_id=current_user.id,
        role=invite.role or "member",
    )
    db.add(membership)
    invite.accepted_at = _now()
    db.commit()
    db.refresh(membership)
    return membership


@router.get("/teams/{team_id}/members", response_model=List[TeamMemberDTO])
def list_members(
    team_id: int,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    require_team_member(team_id, current_user, db)
    return (
        db.query(models.TeamMember)
        .filter(models.TeamMember.team_id == team_id)
        .all()
    )


@router.put("/teams/{team_id}/members/{user_id}", response_model=TeamMemberDTO)
def update_member(
    team_id: int,
    user_id: int,
    payload: MemberUpdate,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    require_team_admin(team_id, current_user, db)

    member = (
        db.query(models.TeamMember)
        .filter(
            models.TeamMember.team_id == team_id,
            models.TeamMember.user_id == user_id,
        )
        .first()
    )
    if member is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Member not found")

    # If demoting an admin, block when they are the last admin.
    if member.role == "admin" and payload.role != "admin":
        admin_count = (
            db.query(models.TeamMember)
            .filter(
                models.TeamMember.team_id == team_id,
                models.TeamMember.role == "admin",
            )
            .count()
        )
        if admin_count <= 1:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Cannot demote the last admin",
            )

    member.role = payload.role
    db.commit()
    db.refresh(member)
    return member


@router.delete("/teams/{team_id}/members/{user_id}")
def remove_member(
    team_id: int,
    user_id: int,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    require_team_admin(team_id, current_user, db)

    member = (
        db.query(models.TeamMember)
        .filter(
            models.TeamMember.team_id == team_id,
            models.TeamMember.user_id == user_id,
        )
        .first()
    )
    if member is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Member not found")

    if member.role == "admin":
        admin_count = (
            db.query(models.TeamMember)
            .filter(
                models.TeamMember.team_id == team_id,
                models.TeamMember.role == "admin",
            )
            .count()
        )
        if admin_count <= 1:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Cannot remove the last admin",
            )

    db.delete(member)
    db.commit()
    return {"message": "Member removed"}
