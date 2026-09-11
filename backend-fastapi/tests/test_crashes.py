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
    assert len(body["signature"]) == 64


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


def test_signature_groups_messages_and_line_number_changes(client):
    traces = [
        "java.lang.RuntimeException: first message\n"
        "\tat com.darius.listmanager.SyncWorker.run(SyncWorker.kt:42)\n"
        "\tat com.darius.listmanager.App.start(App.kt:10)",
        "java.lang.RuntimeException: another message\n"
        "\tat com.darius.listmanager.SyncWorker.run(SyncWorker.kt:999)\n"
        "\tat com.darius.listmanager.App.start(App.kt:11)",
    ]

    signatures = [
        client.post("/api/crashes", json={"stacktrace": trace}).json()["signature"]
        for trace in traces
    ]

    assert signatures[0] == signatures[1]


def test_signature_distinguishes_exception_type_and_top_frame(client):
    base = "\n\tat com.darius.listmanager.SyncWorker.run(SyncWorker.kt:42)"
    runtime = client.post(
        "/api/crashes", json={"stacktrace": "java.lang.RuntimeException: x" + base}
    ).json()["signature"]
    illegal = client.post(
        "/api/crashes", json={"stacktrace": "java.lang.IllegalStateException: x" + base}
    ).json()["signature"]
    other_frame = client.post(
        "/api/crashes",
        json={
            "stacktrace": "java.lang.RuntimeException: x\n"
            "\tat com.darius.listmanager.HomeScreen.render(HomeScreen.kt:42)"
        },
    ).json()["signature"]

    assert len({runtime, illegal, other_frame}) == 3


def test_grouped_crashes_require_admin(client, auth_headers):
    anonymous = client.get("/api/admin/crashes/groups")
    regular_user = client.get("/api/admin/crashes/groups", headers=auth_headers)

    assert anonymous.status_code in (
        status.HTTP_401_UNAUTHORIZED,
        status.HTTP_403_FORBIDDEN,
    )
    assert regular_user.status_code == status.HTTP_403_FORBIDDEN


def test_grouped_crashes_return_aggregate_and_instances(client, admin_headers):
    common_frame = "\tat com.darius.listmanager.SyncWorker.run(SyncWorker.kt:{line})"
    reports = [
        {
            "stacktrace": "java.lang.RuntimeException: one\n" + common_frame.format(line=10),
            "app_version": "2.0",
            "device": "Samsung S25",
        },
        {
            "stacktrace": "java.lang.RuntimeException: two\n" + common_frame.format(line=99),
            "app_version": "2.1",
            "device": "Samsung S25",
        },
        {
            "stacktrace": (
                "java.lang.IllegalStateException: separate\n"
                + common_frame.format(line=10)
            ),
            "app_version": "2.1",
            "device": "Pixel 9",
        },
    ]
    for report in reports:
        assert client.post("/api/crashes", json=report).status_code == status.HTTP_200_OK

    response = client.get("/api/admin/crashes/groups", headers=admin_headers)

    assert response.status_code == status.HTTP_200_OK
    groups = response.json()
    assert [group["count"] for group in groups] == [2, 1]
    repeated = groups[0]
    assert repeated["signature"] == repeated["reports"][0]["signature"]
    assert repeated["app_versions"] == ["2.0", "2.1"]
    assert repeated["devices"] == ["Samsung S25"]
    assert len(repeated["reports"]) == 2
    assert repeated["first_seen"] <= repeated["last_seen"]
