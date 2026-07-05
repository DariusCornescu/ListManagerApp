from fastapi import status


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
    got = client.get(f"/api/catalog/products/{created['id']}").json()
    assert got["price"] == "6.50"
