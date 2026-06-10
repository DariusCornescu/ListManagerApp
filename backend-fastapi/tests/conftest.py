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
def sample_session(db_session, sample_user):
    """Create a sample global session (personal session owned by sample_user)"""
    from app.models import GlobalSession
    session = GlobalSession(
        name="Test Session",
        is_active=True,
        owner_user_id=sample_user.id,
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


# ===== Phase 2 (Teams) fixtures =====

@pytest.fixture
def second_user(db_session):
    """Create a second regular user, distinct from sample_user."""
    from app.models import User
    user = User(
        username="seconduser",
        email="second@example.com",
        hashed_password=get_password_hash("password123"),
        role="USER",
        is_active=True,
    )
    db_session.add(user)
    db_session.commit()
    db_session.refresh(user)
    return user


@pytest.fixture
def second_user_token(client, second_user):
    """Get authentication token for the second user."""
    response = client.post(
        "/api/auth/login",
        json={"username": "seconduser", "password": "password123"},
    )
    return response.json()["access_token"]


@pytest.fixture
def second_user_headers(second_user_token):
    """Authorization headers for the second user."""
    return {"Authorization": f"Bearer {second_user_token}"}


@pytest.fixture
def team(db_session, sample_user):
    """Create a Team with sample_user as a TeamMember(role='admin')."""
    from app.models import Team, TeamMember
    team = Team(
        name="Test Team",
        created_by=sample_user.id,
    )
    db_session.add(team)
    db_session.commit()
    db_session.refresh(team)

    membership = TeamMember(
        team_id=team.id,
        user_id=sample_user.id,
        role="admin",
    )
    db_session.add(membership)
    db_session.commit()
    db_session.refresh(team)
    return team


@pytest.fixture
def team_member_user(db_session, team):
    """A regular user who is a TeamMember(role='member') of team."""
    from app.models import User, TeamMember
    user = User(
        username="teammember",
        email="teammember@example.com",
        hashed_password=get_password_hash("password123"),
        role="USER",
        is_active=True,
    )
    db_session.add(user)
    db_session.commit()
    db_session.refresh(user)

    membership = TeamMember(
        team_id=team.id,
        user_id=user.id,
        role="member",
    )
    db_session.add(membership)
    db_session.commit()
    return user


@pytest.fixture
def team_member_token(client, team_member_user):
    """Get authentication token for the team member user."""
    response = client.post(
        "/api/auth/login",
        json={"username": "teammember", "password": "password123"},
    )
    return response.json()["access_token"]


@pytest.fixture
def team_member_headers(team_member_token):
    """Authorization headers for the team member user."""
    return {"Authorization": f"Bearer {team_member_token}"}


@pytest.fixture
def non_member_user(db_session):
    """An authenticated user belonging to NO team."""
    from app.models import User
    user = User(
        username="nonmember",
        email="nonmember@example.com",
        hashed_password=get_password_hash("password123"),
        role="USER",
        is_active=True,
    )
    db_session.add(user)
    db_session.commit()
    db_session.refresh(user)
    return user


@pytest.fixture
def non_member_token(client, non_member_user):
    """Get authentication token for the non-member user."""
    response = client.post(
        "/api/auth/login",
        json={"username": "nonmember", "password": "password123"},
    )
    return response.json()["access_token"]


@pytest.fixture
def non_member_headers(non_member_token):
    """Authorization headers for the non-member user."""
    return {"Authorization": f"Bearer {non_member_token}"}


@pytest.fixture
def personal_session(db_session, sample_user):
    """A personal GlobalSession owned by sample_user (no team)."""
    from app.models import GlobalSession
    session = GlobalSession(
        name="Personal Session",
        is_active=True,
        owner_user_id=sample_user.id,
        team_id=None,
    )
    db_session.add(session)
    db_session.commit()
    db_session.refresh(session)
    return session


@pytest.fixture
def team_session(db_session, team):
    """A team GlobalSession owned by team (no personal owner)."""
    from app.models import GlobalSession
    session = GlobalSession(
        name="Team Session",
        is_active=True,
        owner_user_id=None,
        team_id=team.id,
    )
    db_session.add(session)
    db_session.commit()
    db_session.refresh(session)
    return session


@pytest.fixture
def personal_session_item(db_session, personal_session, sample_product):
    """A GlobalSessionItem in the personal session."""
    from app.models import GlobalSessionItem
    item = GlobalSessionItem(
        session_id=personal_session.id,
        product_id=sample_product.id,
        quantity=3,
    )
    db_session.add(item)
    db_session.commit()
    db_session.refresh(item)
    return item


@pytest.fixture
def team_session_item(db_session, team_session, sample_product):
    """A GlobalSessionItem in the team session."""
    from app.models import GlobalSessionItem
    item = GlobalSessionItem(
        session_id=team_session.id,
        product_id=sample_product.id,
        quantity=7,
    )
    db_session.add(item)
    db_session.commit()
    db_session.refresh(item)
    return item


@pytest.fixture
def valid_invite(db_session, team, sample_user):
    """A TeamInvite for team that is unused and not expired."""
    import secrets
    from datetime import datetime, timezone, timedelta
    from app.models import TeamInvite
    invite = TeamInvite(
        team_id=team.id,
        code=secrets.token_urlsafe(16)[:32],
        role="member",
        created_by=sample_user.id,
        expires_at=datetime.now(timezone.utc) + timedelta(days=7),
        accepted_at=None,
    )
    db_session.add(invite)
    db_session.commit()
    db_session.refresh(invite)
    return invite
