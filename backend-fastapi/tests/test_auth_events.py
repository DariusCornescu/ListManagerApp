from datetime import datetime, timedelta

import pytest
from fastapi import status

from app import auth_events, models

ANDROID = {"User-Agent": "okhttp/4.12.0"}
FLUTTER = {"User-Agent": "Dart/3.5 (dart:io)"}
BROWSER = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0) Edge/130"}


def _events(db_session, **filters):
    db_session.expire_all()
    query = db_session.query(models.AuthEvent)
    for name, value in filters.items():
        query = query.filter(getattr(models.AuthEvent, name) == value)
    return query.order_by(models.AuthEvent.id).all()


def _login(client, username="testuser", password="password123", headers=None):
    return client.post(
        "/api/auth/login", json={"username": username, "password": password}, headers=headers
    )


def test_login_records_the_user_and_client(client, db_session, sample_user):
    response = _login(client, headers=ANDROID)
    assert response.status_code == status.HTTP_200_OK
    assert set(response.json()) == {"access_token", "token_type", "refresh_token"}

    [event] = _events(db_session, event_type="login")
    assert event.user_id == sample_user.id
    assert event.client == "android"
    assert event.reason is None
    assert event.actor_user_id is None


@pytest.mark.parametrize(
    "headers, expected",
    [(ANDROID, "android"), (FLUTTER, "flutter"), (BROWSER, "web"), ({"User-Agent": "curl/8"}, "other")],
)
def test_client_is_named_from_the_user_agent(client, db_session, sample_user, headers, expected):
    _login(client, headers=headers)
    assert _events(db_session, event_type="login")[-1].client == expected


def test_wrong_password_records_the_account(client, db_session, sample_user):
    assert _login(client, password="wrong").status_code == status.HTTP_401_UNAUTHORIZED
    [event] = _events(db_session, event_type="login_failed")
    assert (event.user_id, event.reason) == (sample_user.id, "wrong_password")


def test_unknown_username_records_nothing_typed(client, db_session):
    secret_looking = "Hunter2-typed-in-the-wrong-box"
    assert _login(client, username=secret_looking).status_code == status.HTTP_401_UNAUTHORIZED
    [event] = _events(db_session, event_type="login_failed")
    assert (event.user_id, event.reason) == (None, "unknown_user")
    stored = " ".join(str(v) for v in vars(event).values())
    assert secret_looking not in stored


def test_inactive_login_records_the_reason(client, db_session, sample_user):
    sample_user.is_active = False
    db_session.commit()
    assert _login(client).status_code == status.HTTP_403_FORBIDDEN
    [event] = _events(db_session, event_type="login_failed")
    assert event.reason == "inactive"


def test_logout_records_only_known_tokens(client, db_session, sample_user):
    tokens = _login(client).json()
    assert client.post("/api/auth/logout", json={"refresh_token": "unknown"}).json() == {
        "message": "Logged out"
    }
    assert _events(db_session, event_type="logout") == []

    client.post("/api/auth/logout", json={"refresh_token": tokens["refresh_token"]})
    [event] = _events(db_session, event_type="logout")
    assert event.user_id == sample_user.id


def test_refresh_is_not_recorded_but_reuse_is(client, db_session, sample_user, monkeypatch):
    from app import auth

    first = _login(client).json()
    second = client.post("/api/auth/refresh", json={"refresh_token": first["refresh_token"]})
    assert second.status_code == status.HTTP_200_OK
    assert _events(db_session, event_type="token_reuse") == []
    assert len(_events(db_session)) == 1  # the login only

    # Presenting the used token after the grace window burns the login.
    monkeypatch.setattr(auth, "REFRESH_REUSE_GRACE_SECONDS", -1)
    replay = client.post("/api/auth/refresh", json={"refresh_token": first["refresh_token"]})
    assert replay.status_code == status.HTTP_401_UNAUTHORIZED
    [event] = _events(db_session, event_type="token_reuse")
    assert (event.user_id, event.reason) == (sample_user.id, "replayed")


def test_logout_all_and_password_change_are_recorded(client, db_session, sample_user):
    headers = {"Authorization": f"Bearer {_login(client).json()['access_token']}"}
    client.post("/api/auth/logout-all", headers=headers)
    assert _events(db_session, event_type="logout_all")[0].user_id == sample_user.id

    headers = {"Authorization": f"Bearer {_login(client).json()['access_token']}"}
    response = client.put("/api/auth/me", json={"password": "a-new-password"}, headers=headers)
    assert response.status_code == status.HTTP_200_OK
    assert _events(db_session, event_type="password_changed")[0].user_id == sample_user.id


def test_profile_edit_without_password_records_nothing(client, db_session, auth_headers):
    client.put("/api/auth/me", json={"full_name": "Nume Nou"}, headers=auth_headers)
    assert _events(db_session, event_type="password_changed") == []


def test_admin_actions_name_the_admin(client, db_session, admin_headers, sample_admin, sample_user):
    for action, event_type in (
        ("revoke-tokens", "admin_revoke"),
        ("deactivate", "admin_deactivate"),
        ("reactivate", "admin_reactivate"),
    ):
        response = client.post(f"/api/admin/users/{sample_user.id}/{action}", headers=admin_headers)
        assert response.status_code == status.HTTP_200_OK
        [event] = _events(db_session, event_type=event_type)
        assert (event.user_id, event.actor_user_id) == (sample_user.id, sample_admin.id)


def test_purge_removes_only_rows_past_retention(db_session, sample_user):
    now = datetime.utcnow()
    db_session.add_all(
        [
            models.AuthEvent(user_id=sample_user.id, event_type="login", created_at=now - timedelta(days=91)),
            models.AuthEvent(user_id=sample_user.id, event_type="login", created_at=now - timedelta(days=89)),
            models.AuthEvent(user_id=sample_user.id, event_type="login", created_at=now),
        ]
    )
    db_session.commit()

    assert auth_events.purge_old(db_session) == 1
    db_session.commit()
    assert len(_events(db_session)) == 2


def test_record_purges_at_most_once_a_day(db_session, sample_user, monkeypatch):
    calls = []
    monkeypatch.setattr(auth_events, "_last_purge", None)
    real_purge = auth_events.purge_old
    monkeypatch.setattr(auth_events, "purge_old", lambda db: calls.append(1) or real_purge(db))

    auth_events.record(db_session, auth_events.LOGIN, user_id=sample_user.id)
    auth_events.record(db_session, auth_events.LOGIN, user_id=sample_user.id)
    db_session.commit()

    assert len(calls) == 1


def test_unknown_event_type_is_refused(db_session):
    with pytest.raises(ValueError):
        auth_events.record(db_session, "made_up")


# ===== Admin reads =====

def test_auth_events_route_is_admin_only(client, auth_headers):
    assert client.get("/api/admin/auth-events").status_code in (
        status.HTTP_401_UNAUTHORIZED,
        status.HTTP_403_FORBIDDEN,
    )
    assert (
        client.get("/api/admin/auth-events", headers=auth_headers).status_code
        == status.HTTP_403_FORBIDDEN
    )


def test_auth_events_route_names_people_and_filters(
    client, db_session, admin_headers, sample_user
):
    _login(client, password="wrong")
    _login(client, username="nobody-here")
    client.post(f"/api/admin/users/{sample_user.id}/revoke-tokens", headers=admin_headers)

    body = client.get("/api/admin/auth-events", headers=admin_headers).json()
    kinds = [e["event_type"] for e in body["items"]]
    # Newest first: the revoke, the two failures, then the admin's own login.
    assert kinds[:3] == ["admin_revoke", "login_failed", "login_failed"]
    revoke = body["items"][0]
    assert (revoke["username"], revoke["actor_username"]) == ("testuser", "adminuser")
    unknown = body["items"][1]
    assert (unknown["username"], unknown["reason"]) == (None, "unknown_user")

    mine = client.get(
        f"/api/admin/auth-events?user_id={sample_user.id}&event_type=login_failed",
        headers=admin_headers,
    ).json()
    assert mine["total"] == 1
    assert mine["items"][0]["reason"] == "wrong_password"


def test_auth_events_limit_is_bounded(client, admin_headers):
    response = client.get("/api/admin/auth-events?limit=201", headers=admin_headers)
    assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY


def test_dashboard_counts_sign_ins_per_day(client, db_session, admin_headers, sample_user):
    tokens = _login(client).json()
    _login(client, password="wrong")
    client.post("/api/auth/logout", json={"refresh_token": tokens["refresh_token"]})

    today = client.get("/api/admin/dashboard", headers=admin_headers).json()["activity"][-1]
    assert today["sign_ins"] == 2  # sample_user and the admin fixture
    assert today["failed_sign_ins"] == 1
    assert today["sign_outs"] == 1


def test_user_details_show_last_login(client, db_session, admin_headers, sample_user):
    rows = client.get("/api/admin/users/details", headers=admin_headers).json()
    assert next(u for u in rows if u["username"] == "testuser")["last_login_at"] is None

    _login(client)
    rows = client.get("/api/admin/users/details", headers=admin_headers).json()
    assert next(u for u in rows if u["username"] == "testuser")["last_login_at"] is not None
