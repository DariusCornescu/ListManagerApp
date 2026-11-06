"""Public route contract used while main.py is split into APIRouters."""

from app.main import app


EXPECTED_HTTP_ROUTES = {
    ("GET", "/"),
    ("GET", "/health"),
    ("POST", "/api/auth/register"),
    ("POST", "/api/auth/login"),
    ("GET", "/api/auth/me"),
    ("PUT", "/api/auth/me"),
    ("POST", "/api/auth/refresh"),
    ("POST", "/api/auth/logout"),
    ("POST", "/api/auth/logout-all"),
    ("GET", "/api/inventory/active"),
    ("GET", "/api/inventory/{inventory_id}/items"),
    ("POST", "/api/inventory/{inventory_id}/items"),
    ("PUT", "/api/inventory/{inventory_id}/items/{item_uuid}"),
    ("DELETE", "/api/inventory/{inventory_id}/items/{item_uuid}"),
    ("POST", "/api/inventory/{inventory_id}/clear"),
    ("POST", "/api/admin/users/{user_id}/revoke-tokens"),
    ("POST", "/api/admin/users/{user_id}/deactivate"),
    ("POST", "/api/admin/users/{user_id}/reactivate"),
    ("GET", "/api/catalog/distributors"),
    ("GET", "/api/catalog/distributors/{distributor_id}"),
    ("POST", "/api/catalog/distributors"),
    ("PUT", "/api/catalog/distributors/{distributor_id}"),
    ("DELETE", "/api/catalog/distributors/{distributor_id}"),
    ("GET", "/api/catalog/products"),
    ("GET", "/api/catalog/products/{product_id}"),
    ("POST", "/api/catalog/products"),
    ("PUT", "/api/catalog/products/{product_id}"),
    ("DELETE", "/api/catalog/products/{product_id}"),
    ("POST", "/api/admin/catalog/import"),
    ("GET", "/api/admin/catalog/products"),
    ("GET", "/admin"),
    ("GET", "/api/session/active"),
    ("POST", "/api/session/create"),
    ("POST", "/api/session/{session_id}/complete"),
    ("DELETE", "/api/session/{session_id}/items"),
    ("GET", "/api/session/{session_id}/items"),
    ("GET", "/api/session/{session_id}/ops"),
    ("POST", "/api/session/{session_id}/items"),
    ("PUT", "/api/session/items/{item_id}"),
    ("DELETE", "/api/session/items/{item_id}"),
    ("GET", "/api/stats"),
    ("GET", "/api/presence"),
    ("POST", "/api/crashes"),
    ("GET", "/api/admin/crashes"),
    ("GET", "/api/admin/crashes/groups"),
    ("GET", "/api/admin/dashboard"),
    ("GET", "/api/admin/users"),
    ("DELETE", "/api/admin/users/{user_id}"),
    ("GET", "/api/protected/test"),
    ("POST", "/api/speech/transcribe"),
    ("POST", "/api/teams"),
    ("GET", "/api/teams"),
    ("GET", "/api/teams/{team_id}"),
    ("PUT", "/api/teams/{team_id}"),
    ("DELETE", "/api/teams/{team_id}"),
    ("POST", "/api/teams/{team_id}/invites"),
    ("POST", "/api/teams/invites/{code}/accept"),
    ("GET", "/api/teams/{team_id}/members"),
    ("PUT", "/api/teams/{team_id}/members/{user_id}"),
    ("DELETE", "/api/teams/{team_id}/members/{user_id}"),
}


def test_public_http_route_methods_and_paths_are_stable():
    # The OpenAPI schema is the public view of the routes; app.routes nests
    # included routers and its shape changes between FastAPI versions.
    actual = {
        (method.upper(), path)
        for path, operations in app.openapi()["paths"].items()
        for method in operations
    }
    assert actual == EXPECTED_HTTP_ROUTES


def test_websocket_path_is_stable():
    websocket_paths = {
        route.path for route in app.routes if route.__class__.__name__ == "APIWebSocketRoute"
    }
    assert websocket_paths == {"/ws"}
