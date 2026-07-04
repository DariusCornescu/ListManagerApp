"""Sync convergence tests (Wave 1 spec; passes after Wave 2 wiring).

Covers SYNC_DESIGN.md §5 "Convergence": two team members concurrently add the
same product to a team session -> the session converges to a single item row
for that product with the quantities summed.

This exercises only existing endpoints (add-add already merges server-side per
the current POST /api/session/{id}/items behavior); it locks in that the
multi-writer path produces one converged row.
"""


def _items(client, session_id, headers):
    resp = client.get(f"/api/session/{session_id}/items", headers=headers)
    assert resp.status_code == 200, resp.text
    return resp.json()


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
