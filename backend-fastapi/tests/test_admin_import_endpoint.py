from fastapi import status

CSV = b"distributor,product_name,aliases,price\nMetro,Milk,lapte,5.00\nMetro,Bread,,2.00\n"


def _files(content=CSV, name="catalog.csv"):
    return {"file": (name, content, "text/csv")}


def test_requires_admin(client, auth_headers):
    resp = client.post("/api/admin/catalog/import", files=_files(), headers=auth_headers)
    assert resp.status_code == status.HTTP_403_FORBIDDEN


def test_rejects_anonymous(client):
    resp = client.post("/api/admin/catalog/import", files=_files())
    assert resp.status_code in (status.HTTP_401_UNAUTHORIZED, status.HTTP_403_FORBIDDEN)


def test_dry_run_previews_without_writing(client, admin_headers):
    resp = client.post(
        "/api/admin/catalog/import",
        files=_files(),
        params={"dry_run": "true"},
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_200_OK
    body = resp.json()
    assert body["new"] == 2
    assert body["committed"] is False
    assert client.get("/api/catalog/products").json() == []


def test_commit_persists(client, admin_headers):
    resp = client.post(
        "/api/admin/catalog/import",
        files=_files(),
        params={"dry_run": "false"},
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_200_OK
    assert resp.json()["committed"] is True
    names = {p["name"] for p in client.get("/api/catalog/products").json()}
    assert names == {"Milk", "Bread"}


def test_partial_errors_reported(client, admin_headers):
    bad = b"distributor,product_name,price\nMetro,Milk,abc\nMetro,Bread,2.00\n"
    resp = client.post(
        "/api/admin/catalog/import",
        files=_files(content=bad),
        params={"dry_run": "false"},
        headers=admin_headers,
    )
    body = resp.json()
    assert body["new"] == 1
    assert len(body["errors"]) == 1
    assert body["errors"][0]["line"] == 2


def test_missing_header_is_400(client, admin_headers):
    resp = client.post(
        "/api/admin/catalog/import",
        files=_files(content=b"product_name\nMilk\n"),
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_400_BAD_REQUEST


def test_empty_file_is_400(client, admin_headers):
    resp = client.post(
        "/api/admin/catalog/import",
        files=_files(content=b""),
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_400_BAD_REQUEST


def test_oversize_rejected(client, admin_headers, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "MAX_IMPORT_BYTES", 10)
    resp = client.post(
        "/api/admin/catalog/import",
        files=_files(content=b"distributor,product_name\nMetro,Milk\n"),
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_413_REQUEST_ENTITY_TOO_LARGE
