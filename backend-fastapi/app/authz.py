"""Authorization helpers for sessions and teams.

These are plain functions meant to be called *inside* request handlers (they are
NOT FastAPI dependencies). They centralize the Phase 2 status-code conventions:

  * A resource the caller is not allowed to even know about => HTTP 404
    ("Session not found" / team missing), so we never leak the existence of
    sessions or teams the user has no relationship with.
  * An authenticated team member who lacks the required TEAM role (e.g. needs
    "admin" but is only a "member") => HTTP 403, because their membership makes
    the team's existence non-secret; they are simply forbidden from this action.

Note: TeamMember.role ("admin" | "member") is TEAM-scoped and is completely
separate from the system-wide User.role ("USER" | "ADMIN").
"""

from fastapi import HTTPException

from app import models


def require_session_access(session_id: int, user, db, *, write: bool = False):
    """Return the GlobalSession the user may access, else raise 404.

    Access rules:
      * Session does not exist                              -> 404.
      * user is the personal owner (owner_user_id == user.id) -> allowed.
      * Session belongs to a team and the user is a member  -> allowed.
      * Otherwise (no relationship to the session)          -> 404 (hide it).

    The 404 (rather than 403) on the "no relationship" case deliberately hides
    the existence of sessions the caller has nothing to do with.

    ``write`` is accepted for call-site clarity/forward compatibility; both
    read and write currently require the same membership relationship.
    """
    session = (
        db.query(models.GlobalSession)
        .filter(models.GlobalSession.id == session_id)
        .first()
    )
    if session is None:
        raise HTTPException(status_code=404, detail="Session not found")

    if session.owner_user_id == user.id:
        return session

    if session.team_id is not None and session.team_id in get_user_team_ids(user, db):
        return session

    raise HTTPException(status_code=404, detail="Session not found")


def get_user_team_ids(user, db) -> set[int]:
    """Return the set of team ids the user is a member of (possibly empty)."""
    rows = (
        db.query(models.TeamMember.team_id)
        .filter(models.TeamMember.user_id == user.id)
        .all()
    )
    return {row[0] for row in rows}


def require_team_member(team_id: int, user, db):
    """Return the Team if the user is a member, else raise 404.

    Both a missing team and a team the user is not a member of yield 404, so we
    never reveal the existence of teams the caller does not belong to.
    """
    team = db.query(models.Team).filter(models.Team.id == team_id).first()
    if team is None:
        raise HTTPException(status_code=404, detail="Team not found")

    membership = (
        db.query(models.TeamMember)
        .filter(
            models.TeamMember.team_id == team_id,
            models.TeamMember.user_id == user.id,
        )
        .first()
    )
    if membership is None:
        raise HTTPException(status_code=404, detail="Team not found")

    return team


def require_team_admin(team_id: int, user, db):
    """Return the Team if the user is a team ADMIN, else 404 or 403.

      * Team missing, or user is not a member  -> 404 (existence hidden).
      * User is a member but TeamMember.role != "admin" -> 403 (forbidden).

    The split reflects the convention: non-members must not learn the team
    exists (404), whereas a known member is told they lack permission (403).
    """
    team = db.query(models.Team).filter(models.Team.id == team_id).first()
    if team is None:
        raise HTTPException(status_code=404, detail="Team not found")

    membership = (
        db.query(models.TeamMember)
        .filter(
            models.TeamMember.team_id == team_id,
            models.TeamMember.user_id == user.id,
        )
        .first()
    )
    if membership is None:
        raise HTTPException(status_code=404, detail="Team not found")

    if membership.role != "admin":
        raise HTTPException(status_code=403, detail="Team admin role required")

    return team
