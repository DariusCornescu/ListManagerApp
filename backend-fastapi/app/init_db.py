from sqlalchemy.orm import Session
from .database import engine, SessionLocal
from . import models
from .auth import get_password_hash
import os
import  logging

logging.basicConfig(level=logging.INFO)
def init_database():
    models.Base.metadata.create_all(bind=engine)
    database = SessionLocal()
    try:
        admin = database.query(models.User).filter(models.User.username == "admin").first()
        if admin:
            logging.info("Admin user already exists.")
            return
        
        admin_password = os.getenv("ADMIN_PASSWORD", "admin123")
        admin_user = models.User(
            username="admin",
            email="admin@listmanager.com",
            hashed_password=get_password_hash(admin_password),
            role="ADMIN",
            is_active=True
        )
        database.add(admin_user)
        database.commit()

        logging.info("Admin user created with username 'admin'.")
    finally:
        database.close()

if __name__ == "__main__":
    init_database()