from fastapi import status


def test_admin_page_served(client):
    resp = client.get("/admin")
    assert resp.status_code == status.HTTP_200_OK
    assert "text/html" in resp.headers["content-type"]
    body = resp.text
    assert "Catalog" in body
    assert "Import CSV" in body
    assert "Distributors" in body
    assert "Add / edit product" in body
