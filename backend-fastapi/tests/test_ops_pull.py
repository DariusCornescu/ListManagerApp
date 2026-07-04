"""Op-pull endpoint tests (Wave 1 spec; passes after Wave 2 wiring).

Covers SYNC_DESIGN.md §3/§5 "Op pull": the NEW endpoint
GET /api/session/{id}/ops?since=<seq> returns the per-session op-log entries
newer than `since`, ordered by monotonic `seq`, scoped by require_session_access
(non-members get 404 = hidden; unauthenticated gets 403).

RED until Wave 2 adds the SessionOp recording + the /ops endpoint.
"""


def _ops(client, session_id, since, headers):
    return client.get(
        f"/api/session/{session_id}/ops",
        params={"since": since},
        headers=headers,
    )


def test_ops_pull_returns_ops_ordered_by_seq(
    client, team_session, sample_product, team_member_headers
):
    """After a couple of item ops on the team session (performed by a member),
    GET /ops?since=0 returns 200 with a non-empty list ordered by ascending
    seq; a follow-up pull since the last seq returns an empty list."""
    add = {"product_id": sample_product.id, "quantity": 1}

    r1 = client.post(
        f"/api/session/{team_session.id}/items",
        json=add,
        headers=team_member_headers,
    )
    assert r1.status_code in (200, 201), r1.text
    # Second op on the same product (increments) -> another logged op.
    r2 = client.post(
        f"/api/session/{team_session.id}/items",
        json=add,
        headers=team_member_headers,
    )
    assert r2.status_code in (200, 201), r2.text

    resp = _ops(client, team_session.id, 0, team_member_headers)
    assert resp.status_code == 200, resp.text
    ops = resp.json()
    assert isinstance(ops, list)
    assert len(ops) >= 1, "expected a non-empty op log after item operations"

    seqs = [op["seq"] for op in ops]
    assert seqs == sorted(seqs), f"ops must be ordered by seq, got {seqs}"
    assert len(seqs) == len(set(seqs)), f"seq values must be unique, got {seqs}"

    # Pulling since the last seq yields nothing new.
    last_seq = seqs[-1]
    resp_after = _ops(client, team_session.id, last_seq, team_member_headers)
    assert resp_after.status_code == 200, resp_after.text
    assert resp_after.json() == [], "since=<last seq> must return an empty list"


def test_ops_pull_non_member_gets_404(
    client, team_session, non_member_headers
):
    """A user with no relationship to the team session must not learn it exists:
    the /ops endpoint returns 404 (hidden), not 403."""
    resp = _ops(client, team_session.id, 0, non_member_headers)
    assert resp.status_code == 404, resp.text


def test_ops_pull_without_token_is_403(client, team_session):
    """No bearer token -> 403 (auth required, HTTPBearer auto_error)."""
    resp = client.get(f"/api/session/{team_session.id}/ops", params={"since": 0})
    assert resp.status_code == 403, resp.text
