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

def create_admin_user():
    """Create default users for testing multi-device sync"""
    db = SessionLocal()
    
    try:
        # User 1: Admin
        admin_password = os.getenv("ADMIN_PASSWORD", "admin123")  # Default for development
        admin = db.query(models.User).filter(models.User.username == "admin").first()
        if not admin:
            logging.info("Creating admin user...")
            admin_user = models.User(
                username="admin",
                email="admin@listmanager.com",
                hashed_password=get_password_hash(admin_password),
                role="ADMIN",
                is_active=True
            )
            db.add(admin_user)
            db.commit()
            logging.info(f"✅ Admin user created: admin / {admin_password}")
        else:
            logging.info("Admin user already exists!")
            # Verify and fix password if needed
            from .auth import verify_password
            if not verify_password(admin_password, admin.hashed_password):
                logging.info("⚠️  Admin password hash is invalid, re-hashing...")
                admin.hashed_password = get_password_hash(admin_password)
                db.commit()
                logging.info("✅ Admin password re-hashed successfully!")
        
        # User 2: Test user (for multi-device testing)
        test_user_password = os.getenv("TEST_USER_PASSWORD", "user123")  # Default for development
        user2 = db.query(models.User).filter(models.User.username == "user").first()
        if not user2:
            logging.info("Creating test user...")
            test_user = models.User(
                username="user",
                email="user@listmanager.com",
                hashed_password=get_password_hash(test_user_password),
                role="USER",
                is_active=True
            )
            db.add(test_user)
            db.commit()
            logging.info(f"✅ Test user created: user / {test_user_password}")
        else:
            logging.info("Test user already exists!")
            # Verify and fix password if needed
            from .auth import verify_password
            if not verify_password(test_user_password, user2.hashed_password):
                logging.info("⚠️  Test user password hash is invalid, re-hashing...")
                user2.hashed_password = get_password_hash(test_user_password)
                db.commit()
                logging.info("✅ Test user password re-hashed successfully!")
        
        logging.info("")
        logging.info("=" * 50)
        logging.info("📱 CREDENTIALS FOR MULTI-DEVICE TESTING:")
        logging.info("=" * 50)
        logging.info(f"  Device 1 (Emulator):   admin / {admin_password}")
        logging.info(f"  Device 2 (Physical):   user  / {test_user_password}")
        logging.info("=" * 50)
        
    finally:
        db.close()

if __name__ == "__main__":
    models.Base.metadata.create_all(bind=engine)
    create_admin_user()