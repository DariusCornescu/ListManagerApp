# app/schemas_teams.py
from datetime import datetime
from typing import Optional
from pydantic import BaseModel, ConfigDict


class TeamCreate(BaseModel):
    name: str


class TeamDTO(BaseModel):
    id: int
    name: str
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)


class TeamMemberDTO(BaseModel):
    id: int
    team_id: int
    user_id: int
    role: str

    model_config = ConfigDict(from_attributes=True)


class InviteCreate(BaseModel):
    role: Optional[str] = "member"


class InviteDTO(BaseModel):
    code: str
    expires_at: datetime
    role: str

    model_config = ConfigDict(from_attributes=True)


class InviteAccept(BaseModel):
    code: str


class MemberUpdate(BaseModel):
    role: str
