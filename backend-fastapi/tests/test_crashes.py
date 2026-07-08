from fastapi import status

CRASH = {
    "app_version": "2.0",
    "android_version": "14",
    "device": "Samsung SM-X000",
    "username": "tata",
    "stacktrace": "java.lang.RuntimeException: boom\n\tat com.darius.listmanager.MainActivity.onCreate(MainActivity.kt:42)",
}


def test_report_crash_no_auth_needed(client):
    resp = client.post("/api/crashes", json=CRASH)
    assert resp.status_code == status.HTTP_200_OK
    body = resp.json()
    assert body["device"] == "Samsung SM-X000"
    assert "boom" in body["stacktrace"]
    assert "id" in body and "created_at" in body


def test_report_crash_minimal_fields(client):
    resp = client.post("/api/crashes", json={"stacktrace": "x"})
    assert resp.status_code == status.HTTP_200_OK
    assert resp.json()["app_version"] is None


def test_oversize_stacktrace_rejected(client):
    resp = client.post("/api/crashes", json={"stacktrace": "x" * 20001})
    assert resp.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY


def test_empty_stacktrace_rejected(client):
    resp = client.post("/api/crashes", json={"stacktrace": ""})
    assert resp.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY


def test_admin_list_requires_admin(client, auth_headers):
    resp = client.get("/api/admin/crashes", headers=auth_headers)
    assert resp.status_code == status.HTTP_403_FORBIDDEN


def test_admin_list_rejects_anonymous(client):
    resp = client.get("/api/admin/crashes")
    assert resp.status_code in (status.HTTP_401_UNAUTHORIZED, status.HTTP_403_FORBIDDEN)


def test_admin_list_newest_first(client, admin_headers):
    client.post("/api/crashes", json={"stacktrace": "first"})
    client.post("/api/crashes", json={"stacktrace": "second"})
    resp = client.get("/api/admin/crashes", headers=admin_headers)
    assert resp.status_code == status.HTTP_200_OK
    traces = [c["stacktrace"] for c in resp.json()]
    assert traces[0] == "second"
    assert traces[1] == "first"


def test_admin_list_limit_capped(client, admin_headers):
    resp = client.get("/api/admin/crashes?limit=999999", headers=admin_headers)
    assert resp.status_code == status.HTTP_200_OK
