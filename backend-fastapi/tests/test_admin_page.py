from fastapi import status


def _page(client):
    resp = client.get("/admin")
    assert resp.status_code == status.HTTP_200_OK
    assert "text/html" in resp.headers["content-type"]
    return resp.text


def test_admin_page_served_with_every_section(client):
    body = _page(client)
    for section in (
        "Panou",
        "Produse și distribuitori",
        "Utilizatori",
        "Echipe",
        "Activitate",
        "Comenzi",
        "Erori",
    ):
        assert section in body
    assert "Import CSV" in body
    assert "Semnături de crash" in body


def test_admin_page_uses_the_admin_routes(client):
    body = _page(client)
    for route in (
        "/api/admin/dashboard",
        "/api/admin/catalog/products",
        "/api/admin/catalog/import",
        "/api/admin/users/details",
        "/api/admin/orders",
        "/api/admin/auth-events",
        "/api/admin/crashes/groups",
    ):
        assert route in body


def test_admin_page_never_renders_server_data_as_html(client):
    # Crash reports arrive without sign-in and the page holds an admin token,
    # so every server value must go through textContent.
    body = _page(client)
    assert "innerHTML" not in body
    assert "outerHTML" not in body
    assert "insertAdjacentHTML" not in body
