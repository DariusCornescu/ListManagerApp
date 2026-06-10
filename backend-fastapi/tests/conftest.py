# tests/conftest.py
import os
# Must be set BEFORE importing app.* (config.Settings requires SECRET_KEY,
# and we disable rate limiting so the suite's many logins aren't throttled).
os.environ.setdefault("SECRET_KEY", "test-secret-key-not-for-prod")
os.environ.setdefault("RATE_LIMIT_ENABLED", "false")

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.main import app
from app.database import Base, get_db
from app.auth import get_password_hash


# Create in-memory SQLite database for testing
SQLALCHEMY_DATABASE_URL = "sqlite:///:memory:"

engine = create_engine(
    SQLALCHEMY_DATABASE_URL,
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


@pytest.fixture(scope="function")
def db_session():
    """Create a fresh database for each test"""
    Base.metadata.create_all(bind=engine)
    session = TestingSessionLocal()
    try:
        yield session
    finally:
        session.close()
        Base.metadata.drop_all(bind=engine)


@pytest.fixture(scope="function")
def client(db_session):
    """Create a test client with database override"""
    def override_get_db():
        try:
            yield db_session
        finally:
            pass

    app.dependency_overrides[get_db] = override_get_db
    test_client = TestClient(app)
    yield test_client
    app.dependency_overrides.clear()


@pytest.fixture
def sample_user(db_session):
    """Create a sample user"""
    from app.models import User
    user = User(
        username="testuser",
        email="test@example.com",
        hashed_password=get_password_hash("password123"),
        role="USER",
        is_active=True
    )
    db_session.add(user)
    db_session.commit()
    db_session.refresh(user)
    return user


@pytest.fixture
def sample_admin(db_session):
    """Create a sample admin user"""
    from app.models import User
    admin = User(
        username="adminuser",
        email="admin@example.com",
        hashed_password=get_password_hash("admin123"),
        role="ADMIN",
        is_active=True
    )
    db_session.add(admin)
    db_session.commit()
    db_session.refresh(admin)
    return admin


@pytest.fixture
def user_token(client, sample_user):
    """Get authentication token for regular user"""
    response = client.post(
        "/api/auth/login",
        json={"username": "testuser", "password": "password123"}
    )
    return response.json()["access_token"]


@pytest.fixture
def admin_token(client, sample_admin):
    """Get authentication token for admin user"""
    response = client.post(
        "/api/auth/login",
        json={"username": "adminuser", "password": "admin123"}
    )
    return response.json()["access_token"]


@pytest.fixture
def sample_distributor(db_session):
    """Create a sample distributor"""
    from app.models import Distributor
    distributor = Distributor(
        distributor_name="Test Distributor"
    )
    db_session.add(distributor)
    db_session.commit()
    db_session.refresh(distributor)
    return distributor


@pytest.fixture
def sample_product(db_session, sample_distributor):
    """Create a sample product"""
    from app.models import Product
    product = Product(
        name="Test Product",
        distributor_id=sample_distributor.id,
        aliases="alias1, alias2"
    )
    db_session.add(product)
    db_session.commit()
    db_session.refresh(product)
    return product


@pytest.fixture
def sample_session(db_session):
    """Create a sample global session"""
    from app.models import GlobalSession
    session = GlobalSession(
        name="Test Session",
        is_active=True
    )
    db_session.add(session)
    db_session.commit()
    db_session.refresh(session)
    return session


@pytest.fixture
def sample_session_item(db_session, sample_session, sample_product):
    """Create a sample session item"""
    from app.models import GlobalSessionItem
    item = GlobalSessionItem(
        session_id=sample_session.id,
        product_id=sample_product.id,
        quantity=5
    )
    db_session.add(item)
    db_session.commit()
    db_session.refresh(item)
    return item


@pytest.fixture
def auth_headers(user_token):
    """Generate authorization headers for regular user"""
    return {"Authorization": f"Bearer {user_token}"}


@pytest.fixture
def admin_headers(admin_token):
    """Generate authorization headers for admin user"""
    return {"Authorization": f"Bearer {admin_token}"}
