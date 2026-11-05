"""Sync convergence tests (Wave 1 spec; passes after Wave 2 wiring).

Covers SYNC_DESIGN.md §5 "Convergence": two team members concurrently add the
same product to a team session -> the session converges to a single item row
for that product with the quantities summed.

This exercises only existing endpoints (add-add already merges server-side per
the current POST /api/session/{id}/items behavior); it locks in that the
multi-writer path produces one converged row.
"""

import random

import pytest

from app import models


def _items(client, session_id, headers):
    resp = client.get(f"/api/session/{session_id}/items", headers=headers)
    assert resp.status_code == 200, resp.text
    return resp.json()


def _pull_ops(client, session_id, since, headers):
    response = client.get(
        f"/api/session/{session_id}/ops",
        params={"since": since},
        headers=headers,
    )
    assert response.status_code == 200, response.text
    return response.json()


def _fold(replica, operations):
    """Apply the public op-log contract to an independent client replica."""
    for operation in operations:
        op_type = operation["op_type"]
        product_id = operation["product_id"]
        item_uuid = operation["item_uuid"]

        if op_type == "AddItem":
            assert product_id not in replica
            assert item_uuid
            replica[product_id] = (item_uuid, operation["qty_delta"])
        elif op_type == "ChangeQty":
            assert product_id in replica
            current_uuid, current_quantity = replica[product_id]
            assert item_uuid == current_uuid
            replica[product_id] = (
                current_uuid,
                current_quantity + operation["qty_delta"],
            )
        elif op_type == "RemoveItem":
            assert product_id in replica
            assert replica[product_id][0] == item_uuid
            del replica[product_id]
        elif op_type == "ClearSession":
            replica.clear()
        else:
            pytest.fail(f"unknown operation type: {op_type}")


def _snapshot(rows):
    return {
        row["product_id"]: (row["item_uuid"], row["quantity"])
        for row in rows
    }


def test_two_members_add_same_product_converges_to_one_row_qty_two(
    client, team_session, sample_product, auth_headers, team_member_headers
):
    """Two distinct team members each add product P (qty 1) to the same team
    session. The session must end with exactly ONE item row for P and the
    quantity summed to 2 (no duplicate rows, no lost write)."""
    body = {"product_id": sample_product.id, "quantity": 1}

    # Member A (team admin) adds the product.
    r1 = client.post(
        f"/api/session/{team_session.id}/items", json=body, headers=auth_headers
    )
    assert r1.status_code in (200, 201), r1.text

    # Member B adds the same product.
    r2 = client.post(
        f"/api/session/{team_session.id}/items",
        json=body,
        headers=team_member_headers,
    )
    assert r2.status_code in (200, 201), r2.text

    # Converged state: a single row for the product with quantity 2.
    items = _items(client, team_session.id, auth_headers)
    rows = [i for i in items if i["product_id"] == sample_product.id]
    assert len(rows) == 1, f"expected one converged row, got {rows}"
    assert rows[0]["quantity"] == 2, f"expected summed qty 2, got {rows[0]}"

    # The same converged view is visible to the other member too.
    items_b = _items(client, team_session.id, team_member_headers)
    rows_b = [i for i in items_b if i["product_id"] == sample_product.id]
    assert len(rows_b) == 1
    assert rows_b[0]["quantity"] == 2
    assert rows_b[0]["id"] == rows[0]["id"]


@pytest.mark.parametrize("seed", [0, 1, 2, 7, 42, 99, 2026, 65535])
def test_randomised_multi_writer_interleavings_converge(
    seed,
    client,
    db_session,
    team_session,
    sample_product,
    auth_headers,
    team_member_headers,
):
    """Every replica converges after a reproducible random operation history.

    Writers alternate in a shuffled order. Replicas pull at unrelated points,
    while idempotent retries, deletes, re-adds and clears change the shape of
    the session. The final fold must equal the authoritative HTTP snapshot.
    """
    rng = random.Random(seed)
    products = [sample_product]
    for index in range(2):
        product = models.Product(
            name=f"Random product {seed}-{index}",
            distributor_id=sample_product.distributor_id,
            aliases=f"random-{seed}-{index}",
        )
        db_session.add(product)
        products.append(product)
    db_session.commit()
    for product in products:
        db_session.refresh(product)

    session_id = team_session.id
    writers = [auth_headers, team_member_headers]
    writer_order = [writers[index % len(writers)] for index in range(36)]
    rng.shuffle(writer_order)
    replicas = [
        {"cursor": 0, "items": {}},
        {"cursor": 0, "items": {}},
        {"cursor": 0, "items": {}},
    ]
    live_items = {}
    expected_operation_count = 0

    for step, writer in enumerate(writer_order):
        roll = rng.random()
        request = None

        if not live_items or roll < 0.45:
            product = rng.choice(products)
            payload = {
                "product_id": product.id,
                "quantity": rng.randint(1, 4),
                "idempotency_key": f"seed-{seed}-add-{step}",
            }
            url = f"/api/session/{session_id}/items"
            response = client.post(url, json=payload, headers=writer)
            assert response.status_code == 200, (seed, step, response.text)
            live_items[product.id] = response.json()
            request = ("post", url, payload, response.json())
        elif roll < 0.72:
            product_id = rng.choice(list(live_items))
            current = live_items[product_id]
            payload = {
                "quantity": rng.randint(0, 12),
                "version": current["version"],
                "idempotency_key": f"seed-{seed}-update-{step}",
            }
            url = f"/api/session/items/{current['id']}"
            response = client.put(url, json=payload, headers=writer)
            assert response.status_code == 200, (seed, step, response.text)
            live_items[product_id] = response.json()
            request = ("put", url, payload, response.json())
        elif roll < 0.88:
            product_id = rng.choice(list(live_items))
            current = live_items.pop(product_id)
            response = client.delete(
                f"/api/session/items/{current['id']}",
                headers=writer,
            )
            assert response.status_code == 200, (seed, step, response.text)
        else:
            response = client.delete(
                f"/api/session/{session_id}/items",
                headers=writer,
            )
            assert response.status_code == 200, (seed, step, response.text)
            live_items.clear()

        expected_operation_count += 1

        # A lost response can cause the same add/update request to be delivered
        # again. Its stored result must be returned without another log entry.
        if request is not None and rng.random() < 0.35:
            method, url, payload, original = request
            retry = getattr(client, method)(url, json=payload, headers=writer)
            assert retry.status_code == 200, (seed, step, retry.text)
            assert retry.json() == original

        # Each replica refreshes independently, reproducing different online /
        # reconnect timing while preserving the API's sequence order.
        for replica in replicas:
            if rng.random() < 0.4:
                new_ops = _pull_ops(
                    client,
                    session_id,
                    replica["cursor"],
                    rng.choice(writers),
                )
                _fold(replica["items"], new_ops)
                if new_ops:
                    replica["cursor"] = new_ops[-1]["seq"]

    all_ops = _pull_ops(client, session_id, 0, auth_headers)
    sequences = [operation["seq"] for operation in all_ops]
    assert sequences == list(range(1, expected_operation_count + 1)), seed

    for replica in replicas:
        remaining = _pull_ops(
            client,
            session_id,
            replica["cursor"],
            team_member_headers,
        )
        _fold(replica["items"], remaining)
        if remaining:
            replica["cursor"] = remaining[-1]["seq"]

    server_state = _snapshot(_items(client, session_id, auth_headers))
    member_state = _snapshot(_items(client, session_id, team_member_headers))
    assert member_state == server_state, seed
    assert all(replica["items"] == server_state for replica in replicas), seed
    assert all(replica["cursor"] == len(all_ops) for replica in replicas), seed
