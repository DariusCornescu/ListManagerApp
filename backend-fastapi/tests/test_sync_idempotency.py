"""Idempotency-key tests (Wave 1 spec; passes after Wave 2 wiring).

Covers SYNC_DESIGN.md §5 "Idempotency" / FM3: a retried add carrying the same
`idempotency_key` must apply exactly once (the retry returns the stored result
without double-counting), while two DIFFERENT keys for the same product both
apply (no false dedupe -> quantities sum).

`idempotency_key` is a NEW optional field on the item-create schema, so omitting
it preserves today's behavior. These assertions are RED until Wave 2 wires the
AppliedOp ledger into POST /api/session/{id}/items.
"""


def _qty_for(client, session_id, product_id, headers):
    resp = client.get(f"/api/session/{session_id}/items", headers=headers)
    assert resp.status_code == 200, resp.text
    rows = [i for i in resp.json() if i["product_id"] == product_id]
    assert len(rows) == 1, f"expected exactly one row for product, got {rows}"
    return rows[0]["quantity"]


def test_same_idempotency_key_twice_applies_once(
    client, team_session, sample_product, auth_headers
):
    """POSTing an add with the same idempotency_key twice must increment the
    quantity only ONCE; the second (retried) call returns the same result and
    does not double-count."""
    body = {"product_id": sample_product.id, "quantity": 1, "idempotency_key": "k1"}

    r1 = client.post(
        f"/api/session/{team_session.id}/items", json=body, headers=auth_headers
    )
    assert r1.status_code in (200, 201), r1.text
    first = r1.json()

    # Retry with the SAME key -> no-op effect, stored result returned.
    r2 = client.post(
        f"/api/session/{team_session.id}/items", json=body, headers=auth_headers
    )
    assert r2.status_code in (200, 201), r2.text
    second = r2.json()

    # Same logical item, same converged quantity (applied once).
    assert second["id"] == first["id"]
    assert second["quantity"] == first["quantity"]

    qty = _qty_for(client, team_session.id, sample_product.id, auth_headers)
    assert qty == 1, f"duplicate key must apply once; expected 1, got {qty}"


def test_two_different_idempotency_keys_both_apply(
    client, team_session, sample_product, auth_headers
):
    """Two DIFFERENT idempotency_keys for the same product are distinct
    operations and must BOTH apply -> quantities sum (no false dedupe)."""
    base = {"product_id": sample_product.id, "quantity": 1}

    r1 = client.post(
        f"/api/session/{team_session.id}/items",
        json={**base, "idempotency_key": "k1"},
        headers=auth_headers,
    )
    assert r1.status_code in (200, 201), r1.text

    r2 = client.post(
        f"/api/session/{team_session.id}/items",
        json={**base, "idempotency_key": "k2"},
        headers=auth_headers,
    )
    assert r2.status_code in (200, 201), r2.text

    qty = _qty_for(client, team_session.id, sample_product.id, auth_headers)
    assert qty == 2, f"distinct keys must both apply; expected 2, got {qty}"


def test_idempotency_key_is_scoped_to_session(
    client, personal_session, team_session, sample_product, auth_headers
):
    """A key used in session A must NOT return session A's stored DTO when the
    SAME key is presented to session B (cross-tenant disclosure, SEC finding).

    `sample_user` (auth_headers) has access to BOTH a personal session and the
    team session, so this exercises one caller reusing a key across two
    different sessions. The response for session B must be session B's own
    item, and session A's item must be untouched.
    """
    key = "shared-key"

    # Post to session A (personal) with key K.
    ra = client.post(
        f"/api/session/{personal_session.id}/items",
        json={"product_id": sample_product.id, "quantity": 3, "idempotency_key": key},
        headers=auth_headers,
    )
    assert ra.status_code in (200, 201), ra.text
    a_item = ra.json()
    assert a_item["session_id"] == personal_session.id

    # Post to session B (team) reusing key K.
    rb = client.post(
        f"/api/session/{team_session.id}/items",
        json={"product_id": sample_product.id, "quantity": 5, "idempotency_key": key},
        headers=auth_headers,
    )
    assert rb.status_code in (200, 201), rb.text
    b_item = rb.json()

    # The response must belong to session B, NOT leak session A's DTO.
    assert b_item["session_id"] == team_session.id, (
        f"cross-tenant leak: session B got session A's DTO {b_item}"
    )
    assert b_item["id"] != a_item["id"]
    assert b_item["quantity"] == 5

    # Session A's item is unchanged (not overwritten by the B write).
    a_qty = _qty_for(client, personal_session.id, sample_product.id, auth_headers)
    assert a_qty == 3, f"session A must be untouched; expected 3, got {a_qty}"

    b_qty = _qty_for(client, team_session.id, sample_product.id, auth_headers)
    assert b_qty == 5, f"session B should hold its own qty; expected 5, got {b_qty}"
