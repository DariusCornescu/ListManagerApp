from datetime import datetime, timedelta

from fastapi import status

from app import models


def _distributor_product(db_session, name):
    distributor = models.Distributor(distributor_name=f"Dist {name}")
    db_session.add(distributor)
    db_session.commit()
    product = models.Product(name=f"Product {name}", distributor_id=distributor.id)
    db_session.add(product)
    db_session.commit()
    return product


def _completed_list(db_session, products, completed_at=None, **owner):
    session = models.GlobalSession(
        name="Lista",
        is_active=False,
        completed_at=completed_at or datetime.utcnow(),
        **owner,
    )
    db_session.add(session)
    db_session.commit()
    for product, quantity in products:
        db_session.add(
            models.GlobalSessionItem(
                session_id=session.id, product_id=product.id, quantity=quantity
            )
        )
    db_session.commit()
    return session


def test_orders_are_admin_only(client, auth_headers):
    assert client.get("/api/admin/orders").status_code in (
        status.HTTP_401_UNAUTHORIZED,
        status.HTTP_403_FORBIDDEN,
    )
    assert (
        client.get("/api/admin/orders", headers=auth_headers).status_code
        == status.HTTP_403_FORBIDDEN
    )


def test_order_counts_documents_per_distributor(
    client, admin_headers, db_session, team, sample_user
):
    first = _distributor_product(db_session, "A")
    second = _distributor_product(db_session, "B")
    same_distributor = models.Product(name="Other", distributor_id=first.distributor_id)
    db_session.add(same_distributor)
    db_session.commit()

    session = _completed_list(
        db_session,
        [(first, 2), (same_distributor, 1), (second, 4)],
        team_id=team.id,
    )

    body = client.get("/api/admin/orders", headers=admin_headers).json()

    assert body["total"] == 1
    order = body["items"][0]
    assert order["session_id"] == session.id
    assert order["team_name"] == "Test Team"
    assert order["owner_username"] is None
    assert order["item_lines"] == 3
    assert order["total_quantity"] == 7
    assert order["documents"] == 2


def test_orders_leave_out_open_lists_and_products(
    client, admin_headers, db_session, sample_user
):
    product = _distributor_product(db_session, "A")
    db_session.add(models.GlobalSession(name="Open", owner_user_id=sample_user.id))
    db_session.commit()
    _completed_list(db_session, [(product, 1)], owner_user_id=sample_user.id)

    body = client.get("/api/admin/orders", headers=admin_headers).json()

    assert body["total"] == 1
    order = body["items"][0]
    assert order["owner_username"] == "testuser"
    assert "Product A" not in str(body)
    assert "items" not in order


def test_orders_are_newest_first_and_paged(
    client, admin_headers, db_session, sample_user
):
    now = datetime.utcnow()
    ids = [
        _completed_list(
            db_session,
            [],
            completed_at=now - timedelta(days=age),
            owner_user_id=sample_user.id,
        ).id
        for age in (2, 0, 1)
    ]

    page = client.get("/api/admin/orders?limit=2", headers=admin_headers).json()
    assert page["total"] == 3
    assert [o["session_id"] for o in page["items"]] == [ids[1], ids[2]]

    rest = client.get("/api/admin/orders?limit=2&offset=2", headers=admin_headers).json()
    assert [o["session_id"] for o in rest["items"]] == [ids[0]]
    assert rest["items"][0]["documents"] == 0
    assert rest["items"][0]["total_quantity"] == 0


def test_order_limit_is_bounded(client, admin_headers):
    response = client.get("/api/admin/orders?limit=101", headers=admin_headers)
    assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY
