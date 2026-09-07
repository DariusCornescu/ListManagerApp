"""Session input must fit storage and never mutate data on rejection."""
import pytest

from app import models

INT_MAX = 2**31 - 1


@pytest.mark.parametrize("overrides", [
    {"quantity": -1}, {"quantity": 0}, {"quantity": INT_MAX + 1},
    {"idempotency_key": "k" * 65},
])
def test_invalid_add_leaves_no_item_or_history(
    client, auth_headers, sample_session, sample_product, db_session, overrides
):
    response = client.post(
        f"/api/session/{sample_session.id}/items", headers=auth_headers,
        json={"product_id": sample_product.id, "quantity": 1,
              "idempotency_key": "invalid-add", **overrides},
    )
    assert response.status_code == 422
    assert db_session.query(models.GlobalSessionItem).count() == 0
    assert db_session.query(models.SessionOp).count() == 0
    assert db_session.query(models.AppliedOp).count() == 0


@pytest.mark.parametrize("overrides", [
    {"quantity": -1}, {"quantity": INT_MAX + 1},
    {"version": -1}, {"version": INT_MAX + 1},
    {"idempotency_key": "k" * 65},
])
def test_invalid_update_preserves_item_and_history(
    client, auth_headers, sample_session_item, db_session, overrides
):
    item = sample_session_item
    before = (item.quantity, item.version)
    response = client.put(
        f"/api/session/items/{item.id}", headers=auth_headers,
        json={"quantity": 3, "version": item.version,
              "idempotency_key": "invalid-update", **overrides},
    )
    assert response.status_code == 422
    db_session.refresh(item)
    assert (item.quantity, item.version) == before
    assert db_session.query(models.SessionOp).count() == 0
    assert db_session.query(models.AppliedOp).count() == 0


def test_sum_overflow_is_rejected_without_consuming_key(
    client, auth_headers, sample_session_item, db_session
):
    item = sample_session_item
    item.quantity = INT_MAX
    db_session.commit()
    before_version = item.version
    url = f"/api/session/{item.session_id}/items"
    payload = {"product_id": item.product_id, "quantity": 1, "idempotency_key": "overflow"}
    response = client.post(url, headers=auth_headers, json=payload)
    assert response.status_code == 422
    db_session.refresh(item)
    assert (item.quantity, item.version) == (INT_MAX, before_version)
    assert db_session.query(models.SessionOp).count() == 0
    assert db_session.query(models.AppliedOp).count() == 0
    # A rejected attempt did not reserve the key; after correcting the stored
    # quantity, the same intended increment can still be submitted once.
    item.quantity = INT_MAX - 1
    db_session.commit()
    assert client.post(url, headers=auth_headers, json=payload).status_code == 200
    assert client.post(url, headers=auth_headers, json=payload).json()["quantity"] == INT_MAX
    assert db_session.query(models.SessionOp).count() == 1


@pytest.mark.parametrize("method", ["add", "update"])
def test_exhausted_version_never_wraps_or_mutates(
    client, auth_headers, sample_session_item, db_session, method
):
    item = sample_session_item
    item.version = INT_MAX
    db_session.commit()
    before_quantity = item.quantity
    if method == "add":
        response = client.post(
            f"/api/session/{item.session_id}/items", headers=auth_headers,
            json={"product_id": item.product_id, "quantity": 1, "idempotency_key": "limit"},
        )
    else:
        response = client.put(
            f"/api/session/items/{item.id}", headers=auth_headers,
            json={"quantity": 2, "version": INT_MAX, "idempotency_key": "limit"},
        )
    assert response.status_code == 409
    db_session.refresh(item)
    assert (item.quantity, item.version) == (before_quantity, INT_MAX)
    assert db_session.query(models.SessionOp).count() == 0
    assert db_session.query(models.AppliedOp).count() == 0


def test_boundary_values_and_zero_update_remain_compatible(
    client, auth_headers, sample_session, sample_product
):
    url = f"/api/session/{sample_session.id}/items"
    payload = {"product_id": sample_product.id, "quantity": INT_MAX, "idempotency_key": "a" * 64}
    first = client.post(url, headers=auth_headers, json=payload)
    assert first.status_code == 200
    assert first.json()["quantity"] == INT_MAX
    assert client.post(url, headers=auth_headers, json=payload).json() == first.json()
    update_url = f"/api/session/items/{first.json()['id']}"
    update = {"quantity": 0, "version": first.json()["version"], "idempotency_key": "u" * 64}
    zero = client.put(update_url, headers=auth_headers, json=update)
    assert zero.status_code == 200
    assert zero.json()["quantity"] == 0
    assert client.put(update_url, headers=auth_headers, json=update).json() == zero.json()
    assert client.get(url, headers=auth_headers).json() == [zero.json()]
