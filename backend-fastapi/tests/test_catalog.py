# tests/test_catalog.py
import pytest
from fastapi import status


class TestDistributorCRUD:
    """Tests for Distributor CRUD operations"""

    def test_create_distributor(self, client, admin_headers):
        """Test creating a new distributor with admin authentication"""
        distributor_data = {
            "distributor_name": "New Distributor"
        }
        response = client.post(
            "/api/catalog/distributors",
            json=distributor_data,
            headers=admin_headers
        )

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["distributor_name"] == "New Distributor"
        assert "id" in data
        assert "created_at" in data

    def test_create_distributor_no_auth(self, client):
        """Test creating distributor without authentication fails"""
        distributor_data = {
            "distributor_name": "New Distributor"
        }
        response = client.post(
            "/api/catalog/distributors",
            json=distributor_data
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_create_distributor_non_admin(self, client, auth_headers):
        """Test creating distributor as a non-admin (regular) user fails"""
        distributor_data = {
            "distributor_name": "New Distributor"
        }
        response = client.post(
            "/api/catalog/distributors",
            json=distributor_data,
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_create_duplicate_distributor(self, client, admin_headers, sample_distributor):
        """Test creating distributor with duplicate name fails"""
        distributor_data = {
            "distributor_name": "Test Distributor"
        }
        response = client.post(
            "/api/catalog/distributors",
            json=distributor_data,
            headers=admin_headers
        )

        assert response.status_code == status.HTTP_400_BAD_REQUEST
        assert "already exists" in response.json()["detail"]

    def test_get_all_distributors(self, client, sample_distributor):
        """Test retrieving all distributors (no auth required for GET)"""
        response = client.get("/api/catalog/distributors")

        assert response.status_code == status.HTTP_200_OK
        distributors = response.json()
        assert len(distributors) >= 1
        assert distributors[0]["distributor_name"] == "Test Distributor"

    def test_get_distributor_by_id(self, client, sample_distributor):
        """Test retrieving a specific distributor by ID"""
        response = client.get(f"/api/catalog/distributors/{sample_distributor.id}")

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["id"] == sample_distributor.id
        assert data["distributor_name"] == "Test Distributor"

    def test_get_nonexistent_distributor(self, client):
        """Test retrieving nonexistent distributor returns 404"""
        response = client.get("/api/catalog/distributors/99999")

        assert response.status_code == status.HTTP_404_NOT_FOUND
        assert "Distributor not found" in response.json()["detail"]

    def test_update_distributor(self, client, admin_headers, sample_distributor):
        """Test updating a distributor with admin authentication"""
        update_data = {
            "distributor_name": "Updated Distributor"
        }
        response = client.put(
            f"/api/catalog/distributors/{sample_distributor.id}",
            json=update_data,
            headers=admin_headers
        )

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["distributor_name"] == "Updated Distributor"
        assert data["id"] == sample_distributor.id

    def test_update_distributor_no_auth(self, client, sample_distributor):
        """Test updating distributor without authentication fails"""
        update_data = {
            "distributor_name": "Updated Distributor"
        }
        response = client.put(
            f"/api/catalog/distributors/{sample_distributor.id}",
            json=update_data
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_update_nonexistent_distributor(self, client, admin_headers):
        """Test updating nonexistent distributor returns 404"""
        update_data = {
            "distributor_name": "Updated Distributor"
        }
        response = client.put(
            "/api/catalog/distributors/99999",
            json=update_data,
            headers=admin_headers
        )

        assert response.status_code == status.HTTP_404_NOT_FOUND
        assert "Distributor not found" in response.json()["detail"]

    def test_delete_distributor(self, client, admin_headers, sample_distributor):
        """Test deleting a distributor with admin authentication"""
        response = client.delete(
            f"/api/catalog/distributors/{sample_distributor.id}",
            headers=admin_headers
        )

        assert response.status_code == status.HTTP_200_OK
        assert response.json()["message"] == "Distributor deleted successfully"

        # Verify deletion
        get_response = client.get(f"/api/catalog/distributors/{sample_distributor.id}")
        assert get_response.status_code == status.HTTP_404_NOT_FOUND

    def test_delete_distributor_no_auth(self, client, sample_distributor):
        """Test deleting distributor without authentication fails"""
        response = client.delete(
            f"/api/catalog/distributors/{sample_distributor.id}"
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_delete_nonexistent_distributor(self, client, admin_headers):
        """Test deleting nonexistent distributor returns 404"""
        response = client.delete(
            "/api/catalog/distributors/99999",
            headers=admin_headers
        )

        assert response.status_code == status.HTTP_404_NOT_FOUND
        assert "Distributor not found" in response.json()["detail"]


class TestProductCRUD:
    """Tests for Product CRUD operations"""

    def test_create_product(self, client, admin_headers, sample_distributor):
        """Test creating a new product with admin authentication"""
        product_data = {
            "name": "New Product",
            "distributor_id": sample_distributor.id,
            "aliases": "alias1, alias2"
        }
        response = client.post(
            "/api/catalog/products",
            json=product_data,
            headers=admin_headers
        )

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["name"] == "New Product"
        assert data["distributor_id"] == sample_distributor.id
        assert data["aliases"] == "alias1, alias2"
        assert "id" in data

    def test_create_product_no_auth(self, client, sample_distributor):
        """Test creating product without authentication fails"""
        product_data = {
            "name": "New Product",
            "distributor_id": sample_distributor.id,
            "aliases": "alias1, alias2"
        }
        response = client.post(
            "/api/catalog/products",
            json=product_data
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_create_product_non_admin(self, client, auth_headers, sample_distributor):
        """Test creating product as a non-admin (regular) user fails"""
        product_data = {
            "name": "New Product",
            "distributor_id": sample_distributor.id,
            "aliases": "alias1, alias2"
        }
        response = client.post(
            "/api/catalog/products",
            json=product_data,
            headers=auth_headers
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_get_all_products(self, client, sample_product):
        """Test retrieving all products (no auth required for GET)"""
        response = client.get("/api/catalog/products")

        assert response.status_code == status.HTTP_200_OK
        products = response.json()
        assert len(products) >= 1
        assert products[0]["name"] == "Test Product"

    def test_search_products_by_name(self, client, sample_product):
        """Test searching products by name"""
        response = client.get("/api/catalog/products?search=Test")

        assert response.status_code == status.HTTP_200_OK
        products = response.json()
        assert len(products) >= 1
        assert "Test" in products[0]["name"]

    def test_search_products_by_alias(self, client, sample_product):
        """Test searching products by alias"""
        response = client.get("/api/catalog/products?search=alias1")

        assert response.status_code == status.HTTP_200_OK
        products = response.json()
        assert len(products) >= 1
        assert "alias1" in products[0]["aliases"]

    def test_search_products_no_results(self, client):
        """Test searching products with no results"""
        response = client.get("/api/catalog/products?search=nonexistent")

        assert response.status_code == status.HTTP_200_OK
        products = response.json()
        assert len(products) == 0

    def test_get_product_by_id(self, client, sample_product):
        """Test retrieving a specific product by ID"""
        response = client.get(f"/api/catalog/products/{sample_product.id}")

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["id"] == sample_product.id
        assert data["name"] == "Test Product"

    def test_get_nonexistent_product(self, client):
        """Test retrieving nonexistent product returns 404"""
        response = client.get("/api/catalog/products/99999")

        assert response.status_code == status.HTTP_404_NOT_FOUND
        assert "Product not found" in response.json()["detail"]

    def test_update_product(self, client, admin_headers, sample_product, sample_distributor):
        """Test updating a product with admin authentication"""
        update_data = {
            "name": "Updated Product",
            "distributor_id": sample_distributor.id,
            "aliases": "new_alias"
        }
        response = client.put(
            f"/api/catalog/products/{sample_product.id}",
            json=update_data,
            headers=admin_headers
        )

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["name"] == "Updated Product"
        assert data["aliases"] == "new_alias"

    def test_update_product_no_auth(self, client, sample_product, sample_distributor):
        """Test updating product without authentication fails"""
        update_data = {
            "name": "Updated Product",
            "distributor_id": sample_distributor.id,
            "aliases": "new_alias"
        }
        response = client.put(
            f"/api/catalog/products/{sample_product.id}",
            json=update_data
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_update_nonexistent_product(self, client, admin_headers, sample_distributor):
        """Test updating nonexistent product returns 404"""
        update_data = {
            "name": "Updated Product",
            "distributor_id": sample_distributor.id,
            "aliases": "alias"
        }
        response = client.put(
            "/api/catalog/products/99999",
            json=update_data,
            headers=admin_headers
        )

        assert response.status_code == status.HTTP_404_NOT_FOUND
        assert "Product not found" in response.json()["detail"]

    def test_delete_product(self, client, admin_headers, sample_product):
        """Test deleting a product with admin authentication"""
        response = client.delete(
            f"/api/catalog/products/{sample_product.id}",
            headers=admin_headers
        )

        assert response.status_code == status.HTTP_200_OK
        assert response.json()["message"] == "Product deleted successfully"

        # Verify deletion
        get_response = client.get(f"/api/catalog/products/{sample_product.id}")
        assert get_response.status_code == status.HTTP_404_NOT_FOUND

    def test_delete_product_no_auth(self, client, sample_product):
        """Test deleting product without authentication fails"""
        response = client.delete(
            f"/api/catalog/products/{sample_product.id}"
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_delete_nonexistent_product(self, client, admin_headers):
        """Test deleting nonexistent product returns 404"""
        response = client.delete(
            "/api/catalog/products/99999",
            headers=admin_headers
        )

        assert response.status_code == status.HTTP_404_NOT_FOUND
        assert "Product not found" in response.json()["detail"]