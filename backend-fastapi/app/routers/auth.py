from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.orm import Session

from .. import auth_events, models, schemas
from ..auth import (
    create_user_access_token,
    get_current_user,
    get_password_hash,
    issue_refresh_token,
    revoke_all_user_tokens,
    revoke_refresh_token,
    rotate_refresh_token,
    verify_password,
)
from ..database import get_db
from ..limiting import limiter
from ..websocket_manager import manager


router = APIRouter()


@router.post("/api/auth/register", response_model=schemas.UserDTO)
@limiter.limit("5/minute")
def register(request: Request, user_data: schemas.UserCreate, db: Session = Depends(get_db)):
    """
    Register new user
    Public endpoint - anyone can register
    """
    # Check if username exists
    existing_user = db.query(models.User).filter(
        models.User.username == user_data.username
    ).first()

    if existing_user:
        raise HTTPException(
            status_code=400,
            detail="Username already registered"
        )

    # Check if email exists
    existing_email = db.query(models.User).filter(
        models.User.email == user_data.email
    ).first()

    if existing_email:
        raise HTTPException(
            status_code=400,
            detail="Email already registered"
        )

    # Check password length before hashing (bcrypt limit: 72 bytes)
    password_bytes = user_data.password.encode('utf-8')
    if len(password_bytes) > 72:
        raise HTTPException(
            status_code=400,
            detail="Password is too long (maximum 72 bytes allowed). Please use a shorter password."
        )

    # Create new user
    hashed_password = get_password_hash(user_data.password)

    new_user = models.User(
        username=user_data.username,
        email=user_data.email,
        hashed_password=hashed_password,
        role="USER",
        is_active=True
    )

    db.add(new_user)
    db.commit()
    db.refresh(new_user)

    return new_user


@router.post("/api/auth/login", response_model=schemas.Token)
@limiter.limit("5/minute")
def login(request: Request, login_data: schemas.UserLogin, db: Session = Depends(get_db)):
    """
    Login and get JWT token
    Public endpoint
    """
    # Find user
    user = db.query(models.User).filter(
        models.User.username == login_data.username
    ).first()

    if not user:
        # The typed username is not stored: it may be someone's password.
        auth_events.record(db, auth_events.LOGIN_FAILED, reason="unknown_user", request=request)
        db.commit()
        raise HTTPException(
            status_code=401,
            detail="Incorrect username or password"
        )

    # Check password length before verification (bcrypt limit: 72 bytes)
    password_bytes = login_data.password.encode('utf-8')
    if len(password_bytes) > 72:
        raise HTTPException(
            status_code=400,
            detail="Password is too long (maximum 72 bytes allowed). Please use a shorter password."
        )
    
    # Verify password
    if not verify_password(login_data.password, user.hashed_password):
        auth_events.record(
            db, auth_events.LOGIN_FAILED, user_id=user.id, reason="wrong_password", request=request
        )
        db.commit()
        raise HTTPException(
            status_code=401,
            detail="Incorrect username or password"
        )

    # Check if user is active
    if not user.is_active:
        auth_events.record(
            db, auth_events.LOGIN_FAILED, user_id=user.id, reason="inactive", request=request
        )
        db.commit()
        raise HTTPException(
            status_code=403,
            detail="User account is inactive"
        )

    refresh_token = issue_refresh_token(user, db)
    auth_events.record(db, auth_events.LOGIN, user_id=user.id, request=request)
    db.commit()

    return {
        "access_token": create_user_access_token(user),
        "token_type": "bearer",
        "refresh_token": refresh_token,
    }


@router.post("/api/auth/refresh", response_model=schemas.Token)
@limiter.limit("30/minute")
def refresh(request: Request, data: schemas.RefreshRequest, db: Session = Depends(get_db)):
    """Exchange a refresh token for a new access + refresh pair (single use)."""
    user, refresh_token = rotate_refresh_token(data.refresh_token, db, request=request)
    db.commit()

    return {
        "access_token": create_user_access_token(user),
        "token_type": "bearer",
        "refresh_token": refresh_token,
    }


@router.post("/api/auth/logout")
@limiter.limit("30/minute")
def logout(request: Request, data: schemas.RefreshRequest, db: Session = Depends(get_db)):
    """Revoke this device's login. Works without a valid access token, so a device
    holding an expired one can still log out; unknown tokens succeed silently."""
    user_id = revoke_refresh_token(data.refresh_token, db)
    if user_id is not None:
        auth_events.record(db, auth_events.LOGOUT, user_id=user_id, request=request)
    db.commit()
    return {"message": "Logged out"}


@router.post("/api/auth/logout-all")
async def logout_all(
    request: Request,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Revoke every token of the caller, on all devices, including this one."""
    revoke_all_user_tokens(current_user, db)
    auth_events.record(db, auth_events.LOGOUT_ALL, user_id=current_user.id, request=request)
    db.commit()
    await manager.close_user(current_user.id)
    return {"message": "All sessions revoked"}


@router.get("/api/auth/me", response_model=schemas.UserDTO)
def get_current_user_info(current_user: models.User = Depends(get_current_user)):
    """
    Get current user info
    Protected endpoint - requires authentication
    """
    return current_user


@router.put("/api/auth/me", response_model=schemas.UserDTO)
async def update_current_user_info(
    request: Request,
    update_data: schemas.UserUpdate,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    """
    Update current user's email and/or password
    Protected endpoint - requires authentication
    """
    # Update email if provided (uniqueness check; format validated by EmailStr)
    if update_data.email is not None and update_data.email != current_user.email:
        existing_email = db.query(models.User).filter(
            models.User.email == update_data.email,
            models.User.id != current_user.id
        ).first()
        if existing_email:
            raise HTTPException(
                status_code=400,
                detail="Email already registered"
            )
        current_user.email = update_data.email

    for field in ("full_name", "company", "address"):
        value = getattr(update_data, field)
        if value is not None:
            setattr(current_user, field, value.strip() or None)

    # Update password if provided (enforce bcrypt 72-byte limit, re-hash)
    if update_data.password is not None:
        password_bytes = update_data.password.encode('utf-8')
        if len(password_bytes) > 72:
            raise HTTPException(
                status_code=400,
                detail="Password is too long (maximum 72 bytes allowed). Please use a shorter password."
            )
        current_user.hashed_password = get_password_hash(update_data.password)
        # A changed password ends every login, including this device's.
        revoke_all_user_tokens(current_user, db)
        auth_events.record(
            db, auth_events.PASSWORD_CHANGED, user_id=current_user.id, request=request
        )

    db.commit()
    db.refresh(current_user)
    if update_data.password is not None:
        await manager.close_user(current_user.id)

    return current_user


@router.get("/api/protected/test")
def protected_test(current_user: models.User = Depends(get_current_user)):
    """
    Example protected endpoint
    Requires authentication
    """
    return {
        "message": f"Hello {current_user.username}!",
        "user_id": current_user.id,
        "role": current_user.role
    }
