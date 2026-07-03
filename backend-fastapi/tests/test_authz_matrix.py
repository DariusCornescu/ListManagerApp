# tests/test_authz_matrix.py
#
# Cross-cutting authorization matrix for session/item operations. These tests
# are written to the Phase 2 spec and will FAIL until Wave 2 adds the per-session
# access checks. Resources are resolved at runtime via request.getfixturevalue.
#
# Expected-status conventions (per Phase 2 spec):
#   - owner of a personal session            => 200
#   - team admin / team member on team sess.  => 200
#   - authenticated user with NO access       => 404 (existence hidden)
#   - missing/invalid token                   => 403 (HTTPBearer auto_error)
#
# Matrix rows (actor, resource, expected):
#   owner (sample_user)      on personal_session => 200
#   non_member               on personal_session => 404
#   team_member              on team_session      => 200
#   team_admin (sample_user) on team_session      => 200
#   non_member               on team_session      => 404
#   no token                 on either            => 403
import pytest
from fastapi import status


# Each row: (actor_headers_fixture_name_or_None, session_fixture, item_fixture, expected_status)
# actor fixture name of None means "send no Authorization header".
AUTHZ_MATRIX = [
    # owner of the personal session -> full access
    ("auth_headers", "personal_session", "personal_session_item", status.HTTP_200_OK),
    # authenticated user with no relationship to the personal session -> hidden
    ("non_member_headers", "personal_session", "personal_session_item", status.HTTP_404_NOT_FOUND),
    # team member on the team session -> access
    ("team_member_headers", "team_session", "team_session_item", status.HTTP_200_OK),
    # team admin (sample_user) on the team session -> access
    ("auth_headers", "team_session", "team_session_item", status.HTTP_200_OK),
    # non-member on the team session -> hidden
    ("non_member_headers", "team_session", "team_session_item", status.HTTP_404_NOT_FOUND),
    # no token at all -> 403 on both resource kinds
    (None, "personal_session", "personal_session_item", status.HTTP_403_FORBIDDEN),
    (None, "team_session", "team_session_item", status.HTTP_403_FORBIDDEN),
]

# Readable ids so a failing row is easy to spot in pytest output.
AUTHZ_IDS = [
    "owner-personal-200",
    "nonmember-personal-404",
    "teammember-team-200",
    "teamadmin-team-200",
    "nonmember-team-404",
    "notoken-personal-403",
    "notoken-team-403",
]


def _headers(request, headers_fixture):
    """Resolve an actor's headers fixture by name, or {} when no token."""
    if headers_fixture is None:
        return {}
    return request.getfixturevalue(headers_fixture)


@pytest.mark.parametrize(
    "headers_fixture,session_fixture,item_fixture,expected",
    AUTHZ_MATRIX,
    ids=AUTHZ_IDS,
)
class TestAuthzMatrix:
    """One class per operation keeps the same matrix applied uniformly."""

    def test_get_session_items(
        self, request, client, headers_fixture, session_fixture, item_fixture, expected
    ):
        """GET /api/session/{id}/items"""
        session = request.getfixturevalue(session_fixture)
        request.getfixturevalue(item_fixture)  # ensure an item exists
        headers = _headers(request, headers_fixture)

        response = client.get(f"/api/session/{session.id}/items", headers=headers)

        assert response.status_code == expected

    def test_add_session_item(
        self, request, client, headers_fixture, session_fixture, item_fixture, expected,
        sample_product,
    ):
        """POST /api/session/{id}/items"""
        session = request.getfixturevalue(session_fixture)
        headers = _headers(request, headers_fixture)

        response = client.post(
            f"/api/session/{session.id}/items",
            json={"product_id": sample_product.id, "quantity": 2},
            headers=headers,
        )

        assert response.status_code == expected

    def test_update_session_item(
        self, request, client, headers_fixture, session_fixture, item_fixture, expected
    ):
        """PUT /api/session/items/{item_id}"""
        item = request.getfixturevalue(item_fixture)
        headers = _headers(request, headers_fixture)

        response = client.put(
            f"/api/session/items/{item.id}",
            json={"quantity": 10, "version": item.version},
            headers=headers,
        )

        assert response.status_code == expected

    def test_delete_session_item(
        self, request, client, headers_fixture, session_fixture, item_fixture, expected
    ):
        """DELETE /api/session/items/{item_id}"""
        item = request.getfixturevalue(item_fixture)
        headers = _headers(request, headers_fixture)

        response = client.delete(f"/api/session/items/{item.id}", headers=headers)

        assert response.status_code == expected

    def test_clear_session_items(
        self, request, client, headers_fixture, session_fixture, item_fixture, expected
    ):
        """DELETE /api/session/{id}/items"""
        session = request.getfixturevalue(session_fixture)
        request.getfixturevalue(item_fixture)  # ensure an item exists
        headers = _headers(request, headers_fixture)

        response = client.delete(f"/api/session/{session.id}/items", headers=headers)

        assert response.status_code == expected

    def test_complete_session(
        self, request, client, headers_fixture, session_fixture, item_fixture, expected
    ):
        """POST /api/session/{id}/complete"""
        session = request.getfixturevalue(session_fixture)
        request.getfixturevalue(item_fixture)  # complete requires >=1 item
        headers = _headers(request, headers_fixture)

        response = client.post(
            f"/api/session/{session.id}/complete", headers=headers
        )

        assert response.status_code == expected
