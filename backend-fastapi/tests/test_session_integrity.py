"""Regression coverage for session writes, retries, and snapshot identity."""
from app import models


def test_missing_product_does_not_write_item_or_sync_history(
    client, auth_headers, sample_session, db_session
):
    response = client.post(
        f"/api/session/{sample_session.id}/items",
        headers=auth_headers,
        json={"product_id": 999999, "quantity": 2, "idempotency_key": "missing"},
    )
    assert response.status_code == 404
    assert response.json()["detail"] == "Product not found"
    assert db_session.query(models.GlobalSessionItem).count() == 0
    assert db_session.query(models.SessionOp).count() == 0
    assert db_session.query(models.AppliedOp).count() == 0
    # An invalid write must not leave a broken relationship that crashes a read.
    assert client.get(
        f"/api/session/{sample_session.id}/items", headers=auth_headers
    ).json() == []


def test_item_identity_and_labels_survive_write_retry_snapshot_and_completion(
    client, auth_headers, sample_session, sample_product, sample_distributor
):
    url = f"/api/session/{sample_session.id}/items"
    payload = {"product_id": sample_product.id, "quantity": 2, "idempotency_key": "add"}
    added = client.post(url, headers=auth_headers, json=payload)
    assert added.status_code == 200
    initial = added.json()
    assert initial["product_name"] == sample_product.name
    assert initial["distributor_name"] == sample_distributor.distributor_name
    assert initial["item_uuid"]
    assert client.post(url, headers=auth_headers, json=payload).json() == initial
    assert client.get(url, headers=auth_headers).json() == [initial]

    update_url = f"/api/session/items/{initial['id']}"
    update = {"quantity": 4, "version": initial["version"], "idempotency_key": "update"}
    updated = client.put(update_url, headers=auth_headers, json=update)
    assert updated.status_code == 200
    current = updated.json()
    assert current["product_name"] == sample_product.name
    assert current["distributor_name"] == sample_distributor.distributor_name
    assert current["item_uuid"] == initial["item_uuid"]
    assert client.put(update_url, headers=auth_headers, json=update).json() == current
    assert client.get(url, headers=auth_headers).json() == [current]

    completed = client.post(
        f"/api/session/{sample_session.id}/complete", headers=auth_headers
    )
    assert completed.status_code == 200
    assert completed.json()["items_by_distributor"][sample_distributor.distributor_name] == [current]


def test_successful_add_retry_after_completion_returns_original_acknowledgement(
    client, auth_headers, sample_session, sample_product, db_session
):
    url = f"/api/session/{sample_session.id}/items"
    payload = {"product_id": sample_product.id, "quantity": 2, "idempotency_key": "retry"}
    first = client.post(url, headers=auth_headers, json=payload)
    assert first.status_code == 200
    assert client.post(
        f"/api/session/{sample_session.id}/complete", headers=auth_headers
    ).status_code == 200
    retry = client.post(url, headers=auth_headers, json=payload)
    assert retry.status_code == 200
    assert retry.json() == first.json()
    assert db_session.query(models.GlobalSessionItem).one().quantity == 2
    assert db_session.query(models.SessionOp).count() == 1
    assert db_session.query(models.AppliedOp).count() == 1
    # A new write is still rejected; replaying an acknowledgement is not a write.
    payload["idempotency_key"] = "new-operation"
    assert client.post(url, headers=auth_headers, json=payload).status_code == 400


def test_idempotent_retry_still_requires_session_access(
    client, auth_headers, second_user_headers, sample_session, sample_product
):
    url = f"/api/session/{sample_session.id}/items"
    payload = {"product_id": sample_product.id, "quantity": 2, "idempotency_key": "private"}
    assert client.post(url, headers=auth_headers, json=payload).status_code == 200
    assert client.post(url, headers=second_user_headers, json=payload).status_code == 404
