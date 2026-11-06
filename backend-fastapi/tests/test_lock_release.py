"""A refused write must release its row lock before answering.

Handlers run on the event loop and the database session closes only after a later
checkpoint. A writer that ran in that gap would block the loop waiting for the lock, which
could then never be released, freezing the whole process. SQLite cannot show the freeze, but
it can show that no transaction is left open.
"""
import uuid


def _item(client, headers, session_id, product_id):
    return client.post(
        f"/api/session/{session_id}/items",
        headers=headers,
        json={"product_id": product_id, "quantity": 1},
    ).json()


def _session(client, headers):
    return client.post("/api/session/create", headers=headers, json={"name": "Locks"}).json()["id"]


def test_a_stale_update_leaves_no_transaction_open(client, auth_headers, sample_product, db_session):
    session_id = _session(client, auth_headers)
    item = _item(client, auth_headers, session_id, sample_product.id)

    response = client.put(
        f"/api/session/items/{item['id']}",
        headers=auth_headers,
        json={"quantity": 2, "version": item["version"] + 5},
    )

    assert response.status_code == 409
    assert response.json()["detail"]["current_version"] == item["version"]
    assert not db_session.in_transaction()


def test_adding_an_unknown_product_leaves_no_transaction_open(client, auth_headers, db_session):
    session_id = _session(client, auth_headers)

    response = client.post(
        f"/api/session/{session_id}/items",
        headers=auth_headers,
        json={"product_id": 999999, "quantity": 1},
    )

    assert response.status_code == 404
    assert not db_session.in_transaction()


def test_a_replayed_add_leaves_no_transaction_open(client, auth_headers, sample_product, db_session):
    session_id = _session(client, auth_headers)
    body = {"product_id": sample_product.id, "quantity": 1, "idempotency_key": "replay"}
    url = f"/api/session/{session_id}/items"
    client.post(url, headers=auth_headers, json=body)

    assert client.post(url, headers=auth_headers, json=body).status_code == 200
    assert not db_session.in_transaction()


def test_writes_to_a_completed_session_leave_no_transaction_open(
    client, auth_headers, sample_product, db_session
):
    session_id = _session(client, auth_headers)
    item = _item(client, auth_headers, session_id, sample_product.id)
    client.post(f"/api/session/{session_id}/complete", headers=auth_headers)

    attempts = [
        client.delete(f"/api/session/items/{item['id']}", headers=auth_headers),
        client.delete(f"/api/session/{session_id}/items", headers=auth_headers),
        client.post(
            f"/api/session/{session_id}/items",
            headers=auth_headers,
            json={"product_id": sample_product.id, "quantity": 1},
        ),
    ]

    assert [response.status_code for response in attempts] == [400, 400, 400]
    assert not db_session.in_transaction()


def test_a_refused_inventory_write_leaves_no_transaction_open(client, auth_headers, db_session):
    inventory = client.get("/api/inventory/active", headers=auth_headers).json()

    response = client.post(
        f"/api/inventory/{inventory['id']}/items",
        headers=auth_headers,
        json={"item_uuid": str(uuid.uuid4()), "name": "Lapte", "product_id": 999999},
    )

    assert response.status_code == 404
    assert not db_session.in_transaction()
