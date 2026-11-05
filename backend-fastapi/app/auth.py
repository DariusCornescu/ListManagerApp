# app/auth.py
import hashlib
import secrets
import uuid
from datetime import datetime, timedelta, timezone
from typing import Optional
import jwt
from jwt import PyJWTError
import bcrypt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from sqlalchemy.orm import Session
from .config import settings
from .database import get_db
from . import models

SECRET_KEY = settings.SECRET_KEY
ALGORITHM = settings.ALGORITHM
ACCESS_TOKEN_EXPIRE_MINUTES = settings.ACCESS_TOKEN_EXPIRE_MINUTES
REFRESH_TOKEN_EXPIRE_DAYS = settings.REFRESH_TOKEN_EXPIRE_DAYS
REFRESH_REUSE_GRACE_SECONDS = settings.REFRESH_REUSE_GRACE_SECONDS
BCRYPT_MAX_PASSWORD_LENGTH = 72
# auto_error off: newer FastAPI answers a missing token with 401, but clients treat
# 401 as "session expired", so get_current_user keeps the established 403.
security = HTTPBearer(auto_error=False)


def verify_password(plain_password: str, hashed_password: str) -> bool:
    """Verify a plain password against a hashed password"""

    password_bytes = plain_password.encode('utf-8')
    if len(password_bytes) > BCRYPT_MAX_PASSWORD_LENGTH:
        return False
    try:
        hashed_bytes = hashed_password.encode('utf-8')
        return bcrypt.checkpw(password_bytes, hashed_bytes)
    except (ValueError, Exception) as e:
        return False


def get_password_hash(password: str) -> str:
    """Hash a password"""

    password_bytes = password.encode('utf-8')
    if len(password_bytes) > BCRYPT_MAX_PASSWORD_LENGTH:
        raise ValueError(
            f"Password is too long (max {BCRYPT_MAX_PASSWORD_LENGTH} bytes). "
            "Please use a shorter password."
        )
    salt = bcrypt.gensalt()
    hashed = bcrypt.hashpw(password_bytes, salt)
    return hashed.decode('utf-8')


def create_access_token(data: dict, expires_delta: Optional[timedelta] = None):
    """Create a JWT access token"""
    to_encode = data.copy()
    if expires_delta:
        expire = datetime.now(timezone.utc) + expires_delta
    else:
        expire = datetime.now(timezone.utc) + timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)

    to_encode.update({"exp": expire})
    encoded_jwt = jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)

    return encoded_jwt


def decode_access_token(token: str):
    """Decode and validate a JWT token"""
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        username: str = payload.get("sub")
        if username is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid authentication credentials",
                headers={"WWW-Authenticate": "Bearer"},
            )
        return payload

    except PyJWTError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid authentication credentials",
            headers={"WWW-Authenticate": "Bearer"},
        )


def create_user_access_token(user: models.User) -> str:
    """Access token bound to the user's current token_version."""
    return create_access_token(
        data={"sub": user.username, "role": user.role, "ver": user.token_version}
    )


def resolve_token_user(token: str, database: Session) -> Optional[models.User]:
    """Return the token's user, or None if the account no longer exists.

    Raises 401 for an invalid or revoked token and 403 for an inactive account.
    A token issued before token_version existed has no "ver" claim and counts as
    version 0, so it stays valid until the user's first revocation.
    """
    payload = decode_access_token(token)
    user = database.query(models.User).filter(
        models.User.username == payload.get("sub")
    ).first()
    if user is None:
        return None
    if payload.get("ver", 0) != user.token_version:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token has been revoked",
            headers={"WWW-Authenticate": "Bearer"},
        )
    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="User is inactive"
        )
    return user


def _hash_refresh_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _utcnow() -> datetime:
    """Naive UTC, matching what SQLite and the DateTime columns round-trip."""
    return datetime.now(timezone.utc).replace(tzinfo=None)


def issue_refresh_token(
    user: models.User, database: Session, family_id: Optional[str] = None
) -> str:
    """Add a refresh token row and return the raw value. Caller commits.

    Only the SHA-256 is stored, so a database leak does not expose usable tokens.
    """
    raw_token = secrets.token_urlsafe(48)
    database.add(models.RefreshToken(
        user_id=user.id,
        token_hash=_hash_refresh_token(raw_token),
        family_id=family_id or str(uuid.uuid4()),
        expires_at=_utcnow() + timedelta(days=REFRESH_TOKEN_EXPIRE_DAYS),
    ))
    return raw_token


def rotate_refresh_token(raw_token: str, database: Session) -> tuple[models.User, str]:
    """Exchange a refresh token for its successor. Caller commits on success.

    A used token presented within the grace window gets another successor: the first
    response was probably lost. Later than that it was copied or replayed, so the
    whole family is revoked, and that revocation is committed here, before the 401.
    """
    invalid = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Invalid refresh token",
        headers={"WWW-Authenticate": "Bearer"},
    )
    row = (
        database.query(models.RefreshToken)
        .filter(models.RefreshToken.token_hash == _hash_refresh_token(raw_token))
        .with_for_update()
        .first()
    )
    if row is None:
        raise invalid

    now = _utcnow()
    if row.revoked_at is not None or row.expires_at <= now:
        raise invalid
    if row.used_at is not None and now - row.used_at > timedelta(
        seconds=REFRESH_REUSE_GRACE_SECONDS
    ):
        revoke_refresh_family(row.family_id, database)
        database.commit()
        raise invalid

    user = database.query(models.User).filter(models.User.id == row.user_id).first()
    if user is None or not user.is_active:
        raise invalid

    row.used_at = row.used_at or now
    return user, issue_refresh_token(user, database, family_id=row.family_id)


def revoke_refresh_family(family_id: str, database: Session) -> None:
    """Revoke every live token of one login. Caller commits."""
    database.query(models.RefreshToken).filter(
        models.RefreshToken.family_id == family_id,
        models.RefreshToken.revoked_at.is_(None),
    ).update({"revoked_at": _utcnow()}, synchronize_session=False)


def revoke_refresh_token(raw_token: str, database: Session) -> None:
    """Log one device out. Unknown tokens are ignored. Caller commits."""
    row = database.query(models.RefreshToken).filter(
        models.RefreshToken.token_hash == _hash_refresh_token(raw_token)
    ).first()
    if row is not None:
        revoke_refresh_family(row.family_id, database)


def revoke_all_user_tokens(user: models.User, database: Session) -> None:
    """Invalidate every access and refresh token of the user. Caller commits."""
    user.token_version += 1
    database.query(models.RefreshToken).filter(
        models.RefreshToken.user_id == user.id,
        models.RefreshToken.revoked_at.is_(None),
    ).update({"revoked_at": _utcnow()}, synchronize_session=False)


def get_current_user(
    credentials: Optional[HTTPAuthorizationCredentials] = Depends(security),
    database: Session = Depends(get_db),
):
    """Get the current authenticated user from JWT token"""

    if credentials is None:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Not authenticated",
        )

    user = resolve_token_user(credentials.credentials, database)

    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="User not found",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return user


def get_current_admin_user( current_user: models.User = Depends(get_current_user) ):
    """Verify that the current user is an admin"""
    if current_user.role != "ADMIN":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Admin access required"
        )

    return current_user