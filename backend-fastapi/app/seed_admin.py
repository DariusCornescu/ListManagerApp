# app/seed_admin.py
from sqlalchemy.orm import Session
from .database import SessionLocal, engine
from . import models
from .auth import get_password_hash
import logging
import os
from dotenv import load_dotenv

load_dotenv()
logging.basicConfig(level=logging.INFO)

logger = logging.getLogger(__name__)

# Values considered "truthy" for opt-in environment flags.
_TRUTHY = {"1", "true", "yes", "on"}


def _is_truthy(value: str | None) -> bool:
    return value is not None and value.strip().lower() in _TRUTHY


def create_admin_user():
    """Idempotently seed accounts.

    Safe to call on every startup:
      - Never overwrites or re-hashes an existing user's password.
      - Creates the admin only when ADMIN_PASSWORD is provided (no insecure
        default credential).
      - Creates the dev test user only when explicitly opted in via
        SEED_DEV_USERS and TEST_USER_PASSWORD.
      - Never logs plaintext passwords.
    """
    db = SessionLocal()

    try:
        # --- Admin user ---------------------------------------------------
        admin_password = os.getenv("ADMIN_PASSWORD")
        if not admin_password:
            logger.warning(
                "ADMIN_PASSWORD is not set; skipping admin user creation. "
                "Set ADMIN_PASSWORD to seed an admin account."
            )
        else:
            admin = (
                db.query(models.User)
                .filter(models.User.username == "admin")
                .first()
            )
            if admin:
                logger.info("Admin user already exists; leaving it untouched.")
            else:
                admin_user = models.User(
                    username="admin",
                    email="admin@listmanager.com",
                    hashed_password=get_password_hash(admin_password),
                    role="ADMIN",
                    is_active=True,
                )
                db.add(admin_user)
                db.commit()
                logger.info("Admin user created.")

        # --- Dev test user (opt-in only) ----------------------------------
        seed_dev_users = _is_truthy(os.getenv("SEED_DEV_USERS"))
        test_user_password = os.getenv("TEST_USER_PASSWORD")

        if not seed_dev_users:
            logger.info(
                "SEED_DEV_USERS is not enabled; skipping dev test user."
            )
        elif not test_user_password:
            logger.warning(
                "SEED_DEV_USERS is enabled but TEST_USER_PASSWORD is not set; "
                "skipping dev test user creation."
            )
        else:
            user2 = (
                db.query(models.User)
                .filter(models.User.username == "user")
                .first()
            )
            if user2:
                logger.info(
                    "Dev test user already exists; leaving it untouched."
                )
            else:
                test_user = models.User(
                    username="user",
                    email="user@listmanager.com",
                    hashed_password=get_password_hash(test_user_password),
                    role="USER",
                    is_active=True,
                )
                db.add(test_user)
                db.commit()
                logger.info("Dev test user created.")

    finally:
        db.close()


if __name__ == "__main__":
    models.Base.metadata.create_all(bind=engine)
    create_admin_user()
