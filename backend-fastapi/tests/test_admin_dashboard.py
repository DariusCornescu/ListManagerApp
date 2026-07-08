from datetime import datetime, timedelta

from fastapi import status


def test_dashboard_rejects_anonymous(client):
    resp = client.get("/api/admin/dashboard")
    assert resp.status_code in (
        status.HTTP_401_UNAUTHORIZED,
        status.HTTP_403_FORBIDDEN,
    )


def test_dashboard_requires_admin(client, auth_headers):
    resp = client.get("/api/admin/dashboard", headers=auth_headers)
    assert resp.status_code == status.HTTP_403_FORBIDDEN


def test_dashboard_counts_and_stores(
    client, admin_headers, team, team_member_user, sample_product
):
    resp = client.get("/api/admin/dashboard", headers=admin_headers)
    assert resp.status_code == status.HTTP_200_OK
    body = resp.json()

    stores = {s["name"]: s["member_count"] for s in body["stores"]}
    assert stores["Test Team"] == 2  # sample_user (admin) + teammember

    # sample_user, sample_admin and teammember at minimum
    assert body["users_count"] >= 3
    assert body["products_count"] == 1
    assert body["distributors_count"] == 1


def test_dashboard_team_without_members_counts_zero(
    client, admin_headers, db_session
):
    from app.models import Team

    db_session.add(Team(name="Empty Store"))
    db_session.commit()

    body = client.get("/api/admin/dashboard", headers=admin_headers).json()
    stores = {s["name"]: s["member_count"] for s in body["stores"]}
    assert stores["Empty Store"] == 0


def test_dashboard_activity_series(
    client, admin_headers, db_session, sample_user, sample_product
):
    from app.models import GlobalSession, GlobalSessionItem

    now = datetime.utcnow()
    done_today = GlobalSession(
        name="done today",
        is_active=False,
        owner_user_id=sample_user.id,
        completed_at=now,
    )
    done_long_ago = GlobalSession(
        name="done long ago",
        is_active=False,
        owner_user_id=sample_user.id,
        completed_at=now - timedelta(days=60),
    )
    never_done = GlobalSession(name="open", owner_user_id=sample_user.id)
    db_session.add_all([done_today, done_long_ago, never_done])
    db_session.commit()
    db_session.add(
        GlobalSessionItem(
            session_id=done_today.id, product_id=sample_product.id, quantity=2
        )
    )
    db_session.commit()

    body = client.get("/api/admin/dashboard", headers=admin_headers).json()

    activity = body["activity"]
    assert len(activity) == 30
    # Zero-filled, chronological, ending today.
    dates = [a["date"] for a in activity]
    assert dates == sorted(dates)
    assert dates[-1] == now.date().isoformat()

    today = activity[-1]
    assert today["lists_completed"] == 1
    assert today["items_added"] == 1
    # All-time counter still sees the 60-day-old completion; the open one never counts.
    assert body["lists_completed_count"] == 2


def test_dashboard_days_clamped(client, admin_headers):
    body = client.get(
        "/api/admin/dashboard?days=5000", headers=admin_headers
    ).json()
    assert len(body["activity"]) == 90

    body = client.get("/api/admin/dashboard?days=1", headers=admin_headers).json()
    assert len(body["activity"]) == 7


def test_dashboard_crash_device_counters(client, admin_headers):
    client.post("/api/crashes", json={"stacktrace": "a", "device": "Samsung A52"})
    client.post("/api/crashes", json={"stacktrace": "b", "device": "Samsung A52"})
    client.post("/api/crashes", json={"stacktrace": "c", "device": "Xiaomi 12"})
    client.post("/api/crashes", json={"stacktrace": "d"})  # no device reported

    body = client.get("/api/admin/dashboard", headers=admin_headers).json()
    assert body["crashes_count"] == 4
    assert body["devices_count"] == 2  # distinct, non-null devices
