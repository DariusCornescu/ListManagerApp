"""Purchase prices stay admin-only, and admins can page through a large catalog."""
from decimal import Decimal

import pytest
from fastapi import status

from app import models

SECRET_PRICE = Decimal("1234.56")
SECRET_PRICE_TEXT = "1234.56"
ADMIN_URL = "/api/admin/catalog/products"


@pytest.fixture
def priced_product(db_session, sample_distributor):
    product = models.Product(
        name="Ciment Portland",
        distributor_id=sample_distributor.id,
        aliases="ciment",
        price=SECRET_PRICE,
    )
    db_session.add(product)
    db_session.commit()
    db_session.refresh(product)
    return product


@pytest.fixture
def many_products(db_session, sample_distributor):
    # Inserted out of order so the ordering assertion means something.
    names = [f"Produs {i:03d}" for i in range(120, 0, -1)]
    db_session.add_all(
        models.Product(name=n, distributor_id=sample_distributor.id, price=Decimal("1.00"))
        for n in names
    )
    db_session.commit()
    return sorted(names)


class TestPublicResponsesHidePrices:
    def test_list_has_no_price_without_login(self, client, priced_product):
        resp = client.get("/api/catalog/products")
        assert resp.status_code == status.HTTP_200_OK
        row = next(p for p in resp.json() if p["id"] == priced_product.id)
        assert "price" not in row
        assert SECRET_PRICE_TEXT not in resp.text

    def test_single_product_has_no_price_without_login(self, client, priced_product):
        resp = client.get(f"/api/catalog/products/{priced_product.id}")
        assert resp.status_code == status.HTTP_200_OK
        assert "price" not in resp.json()
        assert SECRET_PRICE_TEXT not in resp.text

    def test_logged_in_user_gets_no_price_either(self, client, priced_product, auth_headers):
        resp = client.get("/api/catalog/products", headers=auth_headers)
        assert SECRET_PRICE_TEXT not in resp.text

    def test_search_has_no_price(self, client, priced_product):
        resp = client.get("/api/catalog/products", params={"search": "ciment"})
        assert len(resp.json()) == 1
        assert SECRET_PRICE_TEXT not in resp.text

    def test_list_keeps_the_fields_mobile_clients_read(self, client, priced_product):
        row = client.get(f"/api/catalog/products/{priced_product.id}").json()
        assert set(row) == {"id", "name", "distributor_id", "aliases", "created_at"}

    def test_list_stays_complete_for_mobile_clients(self, client, many_products):
        assert len(client.get("/api/catalog/products").json()) == len(many_products)


class TestAdminRead:
    def test_requires_a_login(self, client):
        assert client.get(ADMIN_URL).status_code in (
            status.HTTP_401_UNAUTHORIZED,
            status.HTTP_403_FORBIDDEN,
        )

    def test_rejects_a_normal_user(self, client, auth_headers, priced_product):
        resp = client.get(ADMIN_URL, headers=auth_headers)
        assert resp.status_code == status.HTTP_403_FORBIDDEN
        assert SECRET_PRICE_TEXT not in resp.text

    def test_admin_sees_the_price(self, client, admin_headers, priced_product):
        body = client.get(ADMIN_URL, headers=admin_headers).json()
        row = next(p for p in body["items"] if p["id"] == priced_product.id)
        assert row["price"] == SECRET_PRICE_TEXT

    def test_pages_in_a_stable_order(self, client, admin_headers, many_products):
        first = client.get(ADMIN_URL, params={"limit": 50}, headers=admin_headers).json()
        second = client.get(
            ADMIN_URL, params={"limit": 50, "offset": 50}, headers=admin_headers
        ).json()

        assert first["total"] == second["total"] == len(many_products)
        assert first["limit"] == 50 and second["offset"] == 50
        assert [p["name"] for p in first["items"]] == many_products[:50]
        assert [p["name"] for p in second["items"]] == many_products[50:100]

    def test_default_page_is_not_the_whole_catalog(self, client, admin_headers, many_products):
        body = client.get(ADMIN_URL, headers=admin_headers).json()
        assert len(body["items"]) == 50

    @pytest.mark.parametrize("limit", [0, 201])
    def test_limit_is_bounded(self, client, admin_headers, limit):
        resp = client.get(ADMIN_URL, params={"limit": limit}, headers=admin_headers)
        assert resp.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY

    def test_negative_offset_is_rejected(self, client, admin_headers):
        resp = client.get(ADMIN_URL, params={"offset": -1}, headers=admin_headers)
        assert resp.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY

    def test_search_matches_name_and_alias_ignoring_case(
        self, client, admin_headers, priced_product, many_products
    ):
        for term in ("PORTLAND", "cimen"):
            body = client.get(ADMIN_URL, params={"q": term}, headers=admin_headers).json()
            assert [p["id"] for p in body["items"]] == [priced_product.id]
            assert body["total"] == 1

    @pytest.mark.parametrize("wildcard", ["%", "_", "\\"])
    def test_search_treats_wildcards_as_text(
        self, client, admin_headers, priced_product, wildcard
    ):
        body = client.get(ADMIN_URL, params={"q": wildcard}, headers=admin_headers).json()
        assert body["total"] == 0

    def test_filters_by_distributor(
        self, client, admin_headers, db_session, priced_product, sample_distributor
    ):
        other = models.Distributor(distributor_name="Alt furnizor")
        db_session.add(other)
        db_session.commit()
        db_session.add(models.Product(name="Alt produs", distributor_id=other.id))
        db_session.commit()

        body = client.get(
            ADMIN_URL, params={"distributor_id": other.id}, headers=admin_headers
        ).json()
        assert [p["name"] for p in body["items"]] == ["Alt produs"]


def test_admin_page_reads_the_catalog_through_the_admin_api(client):
    body = client.get("/admin").text
    assert ADMIN_URL in body
    assert 'fetch("/api/catalog/products")' not in body
