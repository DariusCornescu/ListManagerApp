from typing import List

from fastapi import APIRouter, Depends

from .. import models, schemas
from ..auth import get_current_user
from ..websocket_manager import manager


router = APIRouter()


@router.get("/api/presence", response_model=List[schemas.PresenceUserDTO])
def get_presence(current_user: models.User = Depends(get_current_user)):
    """Who is connected over WebSocket right now (drawer 'Online acum')."""
    return manager.online_users()
