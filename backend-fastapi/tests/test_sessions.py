# tests/test_sessions.py
import pytest
from fastapi import status


class TestSessionManagement:
    """Tests for session management endpoints"""

    def test_get_active_session(self, client, sample_session):
        """Test retrieving the active session (no auth required for GET)"""
        response = client.get("/api/session/active")

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["id"] == sample_session.id
        assert data["name"] == "Test Session"
        assert data["is_active"] is True

    def test_get_active_session_when_none_exists(self, client):
        """Test getting active session when none exists"""
        response = client.get("/api/session/active")

        assert response.status_code == status.HTTP_404_NOT_FOUND
        assert "No active session" in response.json()["detail"]

    def test_create_new_session(self, client, auth_headers):
        """Test creating a new session with authentication"""
        session_data = {
            "name": "New Session"
        }
        response = client.post(
            "/api/session/create",
            json=session_data,
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["name"] == "New Session"
        assert data["is_active"] is True
        assert "id" in data

    def test_create_session_no_auth(self, client):
        """Test creating session without authentication fails"""
        session_data = {
            "name": "New Session"
        }
        response = client.post(
            "/api/session/create",
            json=session_data
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_create_session_deactivates_previous(self, client, auth_headers, sample_session):
        """Test creating new session deactivates previous active session"""
        session_data = {
            "name": "New Active Session"
        }
        response = client.post(
            "/api/session/create",
            json=session_data,
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_200_OK
        new_session = response.json()
        assert new_session["is_active"] is True

        # Check that the old session is now inactive
        active_response = client.get("/api/session/active")
        assert active_response.json()["id"] == new_session["id"]

    def test_complete_session(self, client, auth_headers, db_session, sample_session, sample_product, sample_distributor):
        """Test completing a session with items"""
        from app.models import GlobalSessionItem

        # Add items to session
        item1 = GlobalSessionItem(
            session_id=sample_session.id,
            product_id=sample_product.id,
            quantity=5
        )
        db_session.add(item1)
        db_session.commit()

        response = client.post(
            f"/api/session/{sample_session.id}/complete",
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["session"]["is_active"] is False
        assert data["session"]["completed_at"] is not None
        assert "items_by_distributor" in data
        assert "distributor_count" in data
        assert data["total_items"] == 5
        assert "Test Distributor" in data["items_by_distributor"]

    def test_complete_session_no_auth(self, client, sample_session):
        """Test completing session without authentication fails"""
        response = client.post(
            f"/api/session/{sample_session.id}/complete"
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_complete_session_groups_by_distributor(self, client, auth_headers, db_session, sample_session):
        """Test completing session correctly groups items by distributor"""
        from app.models import Distributor, Product, GlobalSessionItem

        # Create two distributors
        dist1 = Distributor(distributor_name="Distributor 1")
        dist2 = Distributor(distributor_name="Distributor 2")
        db_session.add_all([dist1, dist2])
        db_session.commit()

        # Create products for each distributor
        prod1 = Product(name="Product 1", distributor_id=dist1.id)
        prod2 = Product(name="Product 2", distributor_id=dist2.id)
        prod3 = Product(name="Product 3", distributor_id=dist1.id)
        db_session.add_all([prod1, prod2, prod3])
        db_session.commit()

        # Add items to session
        item1 = GlobalSessionItem(session_id=sample_session.id, product_id=prod1.id, quantity=3)
        item2 = GlobalSessionItem(session_id=sample_session.id, product_id=prod2.id, quantity=2)
        item3 = GlobalSessionItem(session_id=sample_session.id, product_id=prod3.id, quantity=4)
        db_session.add_all([item1, item2, item3])
        db_session.commit()

        response = client.post(
            f"/api/session/{sample_session.id}/complete",
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["distributor_count"] == 2
        assert data["total_items"] == 9
        assert "Distributor 1" in data["items_by_distributor"]
        assert "Distributor 2" in data["items_by_distributor"]
        assert len(data["items_by_distributor"]["Distributor 1"]) == 2
        assert len(data["items_by_distributor"]["Distributor 2"]) == 1

    def test_complete_session_without_items(self, client, auth_headers, sample_session):
        """Test completing session without items fails"""
        response = client.post(
            f"/api/session/{sample_session.id}/complete",
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_400_BAD_REQUEST
        assert "Session has no items" in response.json()["detail"]

    def test_complete_nonexistent_session(self, client, auth_headers):
        """Test completing nonexistent session returns 404"""
        response = client.post(
            "/api/session/99999/complete",
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_404_NOT_FOUND
        assert "Session not found" in response.json()["detail"]


class TestSessionItems:
    """Tests for session item operations"""

    def test_get_session_items(self, client, sample_session_item, sample_session, sample_product):
        """Test retrieving all items in a session (no auth required for GET)"""
        response = client.get(f"/api/session/{sample_session.id}/items")

        assert response.status_code == status.HTTP_200_OK
        items = response.json()
        assert len(items) >= 1
        assert items[0]["session_id"] == sample_session.id
        assert items[0]["product_id"] == sample_product.id
        assert items[0]["quantity"] == 5
        assert "product_name" in items[0]
        assert "distributor_name" in items[0]

    def test_get_session_items_empty(self, client, sample_session):
        """Test retrieving items from session with no items"""
        response = client.get(f"/api/session/{sample_session.id}/items")

        assert response.status_code == status.HTTP_200_OK
        items = response.json()
        assert len(items) == 0

    def test_add_item_to_session(self, client, auth_headers, sample_session, sample_product):
        """Test adding a new item to a session with authentication"""
        item_data = {
            "product_id": sample_product.id,
            "quantity": 3
        }
        response = client.post(
            f"/api/session/{sample_session.id}/items",
            json=item_data,
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["session_id"] == sample_session.id
        assert data["product_id"] == sample_product.id
        assert data["quantity"] == 3
        assert data["version"] == 0

    def test_add_item_no_auth(self, client, sample_session, sample_product):
        """Test adding item without authentication fails"""
        item_data = {
            "product_id": sample_product.id,
            "quantity": 3
        }
        response = client.post(
            f"/api/session/{sample_session.id}/items",
            json=item_data
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_add_item_to_nonexistent_session(self, client, auth_headers, sample_product):
        """Test adding item to a nonexistent session returns 404"""
        item_data = {
            "product_id": sample_product.id,
            "quantity": 3
        }
        response = client.post(
            "/api/session/99999/items",
            json=item_data,
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_404_NOT_FOUND
        assert "Session not found" in response.json()["detail"]

    def test_add_existing_item_increments_quantity(self, client, auth_headers, sample_session_item, sample_session, sample_product):
        """Test adding existing item increments quantity"""
        item_data = {
            "product_id": sample_product.id,
            "quantity": 2
        }
        response = client.post(
            f"/api/session/{sample_session.id}/items",
            json=item_data,
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["quantity"] == 7  # Original 5 + 2
        assert data["version"] == 1  # Version incremented

    def test_update_session_item(self, client, auth_headers, db_session, sample_session_item):
        """Test updating a session item with optimistic locking"""
        # Refresh to get current version
        db_session.refresh(sample_session_item)
        current_version = sample_session_item.version

        update_data = {
            "quantity": 10,
            "version": current_version
        }
        response = client.put(
            f"/api/session/items/{sample_session_item.id}",
            json=update_data,
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["quantity"] == 10
        assert data["version"] == current_version + 1

    def test_update_item_no_auth(self, client, sample_session_item):
        """Test updating item without authentication fails"""
        update_data = {
            "quantity": 10,
            "version": 0
        }
        response = client.put(
            f"/api/session/items/{sample_session_item.id}",
            json=update_data
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_update_session_item_version_conflict(self, client, auth_headers, sample_session_item):
        """Test optimistic locking prevents concurrent updates"""
        update_data = {
            "quantity": 10,
            "version": 999  # Wrong version
        }
        response = client.put(
            f"/api/session/items/{sample_session_item.id}",
            json=update_data,
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_409_CONFLICT
        detail = response.json()["detail"]
        assert detail["error"] == "Conflict"
        assert "modified by another user" in detail["message"]
        assert detail["current_version"] == sample_session_item.version

    def test_update_nonexistent_item(self, client, auth_headers):
        """Test updating nonexistent item returns 404"""
        update_data = {
            "quantity": 10,
            "version": 0
        }
        response = client.put(
            "/api/session/items/99999",
            json=update_data,
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_404_NOT_FOUND
        assert "Item not found" in response.json()["detail"]

    def test_delete_session_item(self, client, auth_headers, sample_session_item):
        """Test deleting a session item with authentication"""
        response = client.delete(
            f"/api/session/items/{sample_session_item.id}",
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_200_OK
        assert response.json()["message"] == "Item deleted successfully"

    def test_delete_item_no_auth(self, client, sample_session_item):
        """Test deleting item without authentication fails"""
        response = client.delete(
            f"/api/session/items/{sample_session_item.id}"
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_delete_nonexistent_item(self, client, auth_headers):
        """Test deleting nonexistent item returns 404"""
        response = client.delete(
            "/api/session/items/99999",
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_404_NOT_FOUND
        assert "Item not found" in response.json()["detail"]

    def test_clear_session(self, client, auth_headers, db_session, sample_session, sample_product):
        """Test clearing all items from a session with authentication"""
        from app.models import GlobalSessionItem

        # Add multiple items
        item1 = GlobalSessionItem(session_id=sample_session.id, product_id=sample_product.id, quantity=5)
        item2 = GlobalSessionItem(session_id=sample_session.id, product_id=sample_product.id, quantity=3)
        db_session.add_all([item1, item2])
        db_session.commit()

        response = client.delete(
            f"/api/session/{sample_session.id}/items",
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["count"] == 2
        assert "Cleared 2 items" in data["message"]

        # Verify items are cleared
        get_response = client.get(f"/api/session/{sample_session.id}/items")
        assert len(get_response.json()) == 0

    def test_clear_session_no_auth(self, client, sample_session):
        """Test clearing session without authentication fails"""
        response = client.delete(
            f"/api/session/{sample_session.id}/items"
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_clear_empty_session(self, client, auth_headers, sample_session):
        """Test clearing session with no items"""
        response = client.delete(
            f"/api/session/{sample_session.id}/items",
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["count"] == 0


class TestStatistics:
    """Tests for statistics endpoint"""

    def test_get_stats(self, client, sample_distributor, sample_product, sample_session, sample_session_item):
        """Test retrieving application statistics (no auth required)"""
        response = client.get("/api/stats")

        assert response.status_code == status.HTTP_200_OK
        stats = response.json()
        assert "distributors_count" in stats
        assert "products_count" in stats
        assert "sessions_count" in stats
        assert "active_sessions_count" in stats
        assert "total_session_items" in stats
        assert stats["distributors_count"] >= 1
        assert stats["products_count"] >= 1
        assert stats["sessions_count"] >= 1
        assert stats["active_sessions_count"] >= 1

    def test_get_stats_with_active_session(self, client, sample_session, sample_session_item):
        """Test stats includes active session information"""
        response = client.get("/api/stats")

        assert response.status_code == status.HTTP_200_OK
        stats = response.json()
        assert stats["active_session"] is not None
        assert stats["active_session"]["id"] == sample_session.id
        assert stats["active_session"]["name"] == "Test Session"
        assert "items_count" in stats["active_session"]

    def test_get_stats_without_active_session(self, client):
        """Test stats when no active session exists"""
        response = client.get("/api/stats")

        assert response.status_code == status.HTTP_200_OK
        stats = response.json()
        assert stats["active_session"] is None