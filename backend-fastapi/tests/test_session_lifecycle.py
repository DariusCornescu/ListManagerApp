from app import models


def test_create_session_replays_by_client_uuid(
    client,
    auth_headers,
    sample_user,
    db_session,
):
    payload = {
        "name": "Offline session",
        "client_uuid": "11111111-1111-1111-1111-111111111111",
    }

    first = client.post("/api/session/create", headers=auth_headers, json=payload)
    second = client.post("/api/session/create", headers=auth_headers, json=payload)

    assert first.status_code == 200
    assert second.status_code == 200
    assert second.json() == first.json()
    assert first.json()["client_uuid"] == payload["client_uuid"]
    assert db_session.query(models.GlobalSession).filter(
        models.GlobalSession.owner_user_id == sample_user.id,
        models.GlobalSession.client_uuid == payload["client_uuid"],
    ).count() == 1


def test_client_uuid_cannot_be_claimed_by_another_account(
    client,
    auth_headers,
    second_user_headers,
):
    payload = {
        "name": "Private offline session",
        "client_uuid": "22222222-2222-2222-2222-222222222222",
    }
    assert client.post(
        "/api/session/create", headers=auth_headers, json=payload
    ).status_code == 200

    collision = client.post(
        "/api/session/create", headers=second_user_headers, json=payload
    )
    assert collision.status_code == 409
    assert collision.json()["detail"] == "Session identity already exists"


def test_completed_session_is_immutable_and_completion_is_idempotent(
    client,
    auth_headers,
    sample_session,
    sample_product,
    db_session,
):
    added = client.post(
        f"/api/session/{sample_session.id}/items",
        headers=auth_headers,
        json={
            "product_id": sample_product.id,
            "quantity": 2,
            "idempotency_key": "lifecycle-add",
        },
    )
    assert added.status_code == 200
    item = added.json()

    first_completion = client.post(
        f"/api/session/{sample_session.id}/complete",
        headers=auth_headers,
    )
    second_completion = client.post(
        f"/api/session/{sample_session.id}/complete",
        headers=auth_headers,
    )
    assert first_completion.status_code == 200
    assert second_completion.status_code == 200
    assert second_completion.json() == first_completion.json()

    update = client.put(
        f"/api/session/items/{item['id']}",
        headers=auth_headers,
        json={"quantity": 9, "version": item["version"]},
    )
    delete = client.delete(
        f"/api/session/items/{item['id']}",
        headers=auth_headers,
    )
    clear = client.delete(
        f"/api/session/{sample_session.id}/items",
        headers=auth_headers,
    )

    assert [update.status_code, delete.status_code, clear.status_code] == [400, 400, 400]
    assert update.json()["detail"] == "Session is no longer active."
    assert delete.json()["detail"] == "Session is no longer active."
    assert clear.json()["detail"] == "Session is no longer active."
    stored = db_session.query(models.GlobalSessionItem).one()
    assert stored.quantity == 2
    assert db_session.query(models.SessionOp).count() == 1
