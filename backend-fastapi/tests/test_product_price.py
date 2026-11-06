from decimal import Decimal

from fastapi import status

from app import models


def test_create_product_with_price(client, admin_headers, sample_distributor):
    resp = client.post(
        "/api/catalog/products",
        json={
            "name": "Coca-Cola 2L",
            "distributor_id": sample_distributor.id,
            "aliases": "cola|coke",
            "price": 8.50,
        },
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_200_OK
    data = resp.json()
    assert data["name"] == "Coca-Cola 2L"
    assert data["price"] == "8.50"


def test_create_product_without_price_defaults_null(client, admin_headers, sample_distributor):
    resp = client.post(
        "/api/catalog/products",
        json={"name": "Lapte", "distributor_id": sample_distributor.id, "aliases": None},
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_200_OK
    assert resp.json()["price"] is None


def test_negative_price_rejected(client, admin_headers, sample_distributor):
    resp = client.post(
        "/api/catalog/products",
        json={"name": "Bad", "distributor_id": sample_distributor.id, "price": -1},
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY


def test_update_product_persists_price(client, admin_headers, sample_distributor):
    created = client.post(
        "/api/catalog/products",
        json={"name": "Milk", "distributor_id": sample_distributor.id, "price": 5.00},
        headers=admin_headers,
    ).json()
    resp = client.put(
        f"/api/catalog/products/{created['id']}",
        json={"name": "Milk", "distributor_id": sample_distributor.id, "aliases": None, "price": 6.50},
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_200_OK
    assert resp.json()["price"] == "6.50"
    page = client.get("/api/admin/catalog/products", headers=admin_headers).json()
    got = next(p for p in page["items"] if p["id"] == created["id"])
    assert got["price"] == "6.50"


def _priced_product(client, admin_headers, distributor):
    return client.post(
        "/api/catalog/products",
        json={"name": "Ciment", "distributor_id": distributor.id, "price": 12.5},
        headers=admin_headers,
    ).json()


def _stored_price(db_session, product_id):
    db_session.expire_all()
    return db_session.get(models.Product, product_id).price


def test_edit_without_a_price_keeps_it(client, admin_headers, db_session, sample_distributor):
    """Android and Flutter edit products without sending a price."""
    created = _priced_product(client, admin_headers, sample_distributor)
    resp = client.put(
        f"/api/catalog/products/{created['id']}",
        json={"name": "Ciment nou", "distributor_id": sample_distributor.id, "aliases": None},
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_200_OK
    assert resp.json()["name"] == "Ciment nou"
    assert _stored_price(db_session, created["id"]) == Decimal("12.50")


def test_edit_with_an_explicit_null_clears_the_price(
    client, admin_headers, db_session, sample_distributor
):
    created = _priced_product(client, admin_headers, sample_distributor)
    resp = client.put(
        f"/api/catalog/products/{created['id']}",
        json={"name": "Ciment", "distributor_id": sample_distributor.id, "price": None},
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_200_OK
    assert _stored_price(db_session, created["id"]) is None
