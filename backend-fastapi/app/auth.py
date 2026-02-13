# app/auth.py
from datetime import datetime, timedelta, timezone
from typing import Optional
from jose import JWTError, jwt
import bcrypt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from sqlalchemy.orm import Session
import os
from dotenv import load_dotenv
from .database import get_db
from . import models

load_dotenv()

SECRET_KEY = os.getenv("SECRET_KEY", "fallback-key-only-for-dev")
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = int(os.getenv("ACCESS_TOKEN_EXPIRE_MINUTES", 1440))
BCRYPT_MAX_PASSWORD_LENGTH = 72
security = HTTPBearer()


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

    except JWTError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid authentication credentials",
            headers={"WWW-Authenticate": "Bearer"},
        )


def get_current_user( credentials: HTTPAuthorizationCredentials = Depends(security), database: Session = Depends(get_db) ):
    """Get the current authenticated user from JWT token"""

    token = credentials.credentials
    payload = decode_access_token(token)

    username = payload.get("sub")
    user = database.query(models.User).filter(models.User.username == username).first()

    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="User not found",
            headers={"WWW-Authenticate": "Bearer"},
        )

    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="User is inactive"
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