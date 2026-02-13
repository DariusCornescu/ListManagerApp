# tests/test_auth.py
import pytest
from fastapi import status


class TestRootAndHealth:
    """Tests for root and health check endpoints"""

    def test_root_endpoint(self, client):
        """Test root endpoint returns welcome message"""
        response = client.get("/")
        assert response.status_code == status.HTTP_200_OK
        assert response.json() == {"message": "List Manager API is running!"}

    def test_health_check(self, client):
        """Test health check endpoint"""
        response = client.get("/health")
        assert response.status_code == status.HTTP_200_OK
        assert response.json() == {"status": "healthy"}


class TestUserRegistration:
    """Tests for user registration endpoint"""

    def test_register_new_user(self, client):
        """Test successful user registration"""
        user_data = {
            "username": "newuser",
            "email": "newuser@example.com",
            "password": "securepass123"
        }
        response = client.post("/api/auth/register", json=user_data)

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["username"] == "newuser"
        assert data["email"] == "newuser@example.com"
        assert data["role"] == "USER"
        assert data["is_active"] is True
        assert "hashed_password" not in data
        assert "id" in data

    def test_register_duplicate_username(self, client, sample_user):
        """Test registration fails with duplicate username"""
        user_data = {
            "username": "testuser",  # Already exists
            "email": "different@example.com",
            "password": "password123"
        }
        response = client.post("/api/auth/register", json=user_data)

        assert response.status_code == status.HTTP_400_BAD_REQUEST
        assert "Username already registered" in response.json()["detail"]

    def test_register_duplicate_email(self, client, sample_user):
        """Test registration fails with duplicate email"""
        user_data = {
            "username": "differentuser",
            "email": "test@example.com",  # Already exists
            "password": "password123"
        }
        response = client.post("/api/auth/register", json=user_data)

        assert response.status_code == status.HTTP_400_BAD_REQUEST
        assert "Email already registered" in response.json()["detail"]


class TestUserLogin:
    """Tests for user login endpoint"""

    def test_login_success(self, client, sample_user):
        """Test successful login"""
        login_data = {
            "username": "testuser",
            "password": "password123"
        }
        response = client.post("/api/auth/login", json=login_data)

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert "access_token" in data
        assert data["token_type"] == "bearer"

    def test_login_wrong_password(self, client, sample_user):
        """Test login fails with wrong password"""
        login_data = {
            "username": "testuser",
            "password": "wrongpassword"
        }
        response = client.post("/api/auth/login", json=login_data)

        assert response.status_code == status.HTTP_401_UNAUTHORIZED
        assert "Incorrect username or password" in response.json()["detail"]

    def test_login_nonexistent_user(self, client):
        """Test login fails with nonexistent user"""
        login_data = {
            "username": "nonexistent",
            "password": "password123"
        }
        response = client.post("/api/auth/login", json=login_data)

        assert response.status_code == status.HTTP_401_UNAUTHORIZED
        assert "Incorrect username or password" in response.json()["detail"]

    def test_login_inactive_user(self, client, db_session, sample_user):
        """Test login fails for inactive user"""
        sample_user.is_active = False
        db_session.commit()

        login_data = {
            "username": "testuser",
            "password": "password123"
        }
        response = client.post("/api/auth/login", json=login_data)

        assert response.status_code == status.HTTP_403_FORBIDDEN
        assert "User account is inactive" in response.json()["detail"]


class TestAuthenticatedEndpoints:
    """Tests for authenticated endpoint access"""

    def test_get_current_user_authenticated(self, client, auth_headers, sample_user):
        """Test getting current user info with valid token"""
        response = client.get("/api/auth/me", headers=auth_headers)

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["username"] == "testuser"
        assert data["email"] == "test@example.com"

    def test_get_current_user_no_token(self, client):
        """Test getting current user fails without token"""
        response = client.get("/api/auth/me")

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_protected_endpoint(self, client, auth_headers, sample_user):
        """Test protected endpoint with valid token"""
        response = client.get("/api/protected/test", headers=auth_headers)

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert "Hello testuser!" in data["message"]
        assert data["role"] == "USER"


class TestAdminEndpoints:
    """Tests for admin-only endpoints"""

    def test_get_all_users_as_admin(self, client, admin_headers, sample_admin, sample_user):
        """Test admin can get all users"""
        response = client.get("/api/admin/users", headers=admin_headers)

        assert response.status_code == status.HTTP_200_OK
        users = response.json()
        assert len(users) >= 2
        usernames = [u["username"] for u in users]
        assert "adminuser" in usernames
        assert "testuser" in usernames

    def test_get_all_users_as_regular_user(self, client, auth_headers):
        """Test regular user cannot access admin endpoint"""
        response = client.get("/api/admin/users", headers=auth_headers)

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_get_all_users_no_auth(self, client):
        """Test unauthenticated user cannot access admin endpoint"""
        response = client.get("/api/admin/users")

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_delete_user_as_admin(self, client, admin_headers, sample_user):
        """Test admin can delete user"""
        response = client.delete(
            f"/api/admin/users/{sample_user.id}",
            headers=admin_headers
        )

        assert response.status_code == status.HTTP_200_OK
        assert response.json()["message"] == "User deleted successfully"

    def test_admin_cannot_delete_self(self, client, admin_headers, sample_admin):
        """Test admin cannot delete themselves"""
        response = client.delete(
            f"/api/admin/users/{sample_admin.id}",
            headers=admin_headers
        )

        assert response.status_code == status.HTTP_400_BAD_REQUEST
        assert "Cannot delete yourself" in response.json()["detail"]

    def test_delete_nonexistent_user(self, client, admin_headers):
        """Test deleting nonexistent user returns 404"""
        response = client.delete(
            "/api/admin/users/99999",
            headers=admin_headers
        )

        assert response.status_code == status.HTTP_404_NOT_FOUND
        assert "User not found" in response.json()["detail"]
