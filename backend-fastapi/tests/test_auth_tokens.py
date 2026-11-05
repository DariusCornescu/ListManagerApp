"""EX-45: refresh rotation, reuse detection and revocation."""
from datetime import datetime, timedelta, timezone

import pytest
from starlette.websockets import WebSocketDisconnect

from app import models
from app.auth import create_access_token


LOGIN = {"username": "testuser", "password": "password123"}


def _login(client):
    response = client.post("/api/auth/login", json=LOGIN)
    assert response.status_code == 200, response.text
    return response.json()


def _bearer(token):
    return {"Authorization": f"Bearer {token}"}


def _age_used_tokens(db_session):
    """Push every used refresh token past the reuse grace window."""
    past = datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(hours=1)
    db_session.query(models.RefreshToken).filter(
        models.RefreshToken.used_at.isnot(None)
    ).update({"used_at": past}, synchronize_session=False)
    db_session.commit()


def _me_status(client, token):
    return client.get("/api/auth/me", headers=_bearer(token)).status_code


def test_login_returns_working_access_and_refresh_tokens(client, sample_user):
    tokens = _login(client)
    assert tokens["token_type"] == "bearer"
    assert tokens["refresh_token"]
    assert _me_status(client, tokens["access_token"]) == 200


def test_refresh_token_is_stored_only_as_a_hash(client, sample_user, db_session):
    tokens = _login(client)
    row = db_session.query(models.RefreshToken).one()
    assert row.token_hash != tokens["refresh_token"]
    assert len(row.token_hash) == 64


def test_refresh_rotates_and_returns_usable_pair(client, sample_user):
    first = _login(client)
    second = client.post(
        "/api/auth/refresh", json={"refresh_token": first["refresh_token"]}
    )
    assert second.status_code == 200
    body = second.json()
    assert body["refresh_token"] != first["refresh_token"]
    assert _me_status(client, body["access_token"]) == 200


def test_retry_within_grace_window_survives_a_lost_response(client, sample_user):
    first = _login(client)["refresh_token"]
    client.post("/api/auth/refresh", json={"refresh_token": first})

    # The phone never saw that response, so it presents the same token again.
    retry = client.post("/api/auth/refresh", json={"refresh_token": first})
    assert retry.status_code == 200
    assert client.post(
        "/api/auth/refresh", json={"refresh_token": retry.json()["refresh_token"]}
    ).status_code == 200


def test_reused_refresh_token_revokes_the_whole_family(client, sample_user, db_session):
    first = _login(client)["refresh_token"]
    successor = client.post("/api/auth/refresh", json={"refresh_token": first}).json()
    _age_used_tokens(db_session)

    replay = client.post("/api/auth/refresh", json={"refresh_token": first})
    assert replay.status_code == 401

    # The legitimate holder of the successor is cut off too: the family is burned.
    after = client.post(
        "/api/auth/refresh", json={"refresh_token": successor["refresh_token"]}
    )
    assert after.status_code == 401


def test_reuse_detection_does_not_touch_other_logins(client, sample_user, db_session):
    other_device = _login(client)["refresh_token"]
    first = _login(client)["refresh_token"]
    client.post("/api/auth/refresh", json={"refresh_token": first})
    _age_used_tokens(db_session)
    assert client.post(
        "/api/auth/refresh", json={"refresh_token": first}
    ).status_code == 401

    assert client.post(
        "/api/auth/refresh", json={"refresh_token": other_device}
    ).status_code == 200


def test_expired_refresh_token_is_rejected(client, sample_user, db_session):
    tokens = _login(client)
    row = db_session.query(models.RefreshToken).one()
    row.expires_at = datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(seconds=1)
    db_session.commit()

    assert client.post(
        "/api/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
    ).status_code == 401


def test_unknown_refresh_token_is_rejected(client, sample_user):
    assert client.post(
        "/api/auth/refresh", json={"refresh_token": "not-a-real-token"}
    ).status_code == 401


def test_refresh_is_refused_for_an_inactive_user(client, sample_user, db_session):
    tokens = _login(client)
    sample_user.is_active = False
    db_session.commit()

    assert client.post(
        "/api/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
    ).status_code == 401


def test_logout_revokes_only_that_login(client, sample_user):
    other_device = _login(client)["refresh_token"]
    this_device = _login(client)["refresh_token"]

    assert client.post(
        "/api/auth/logout", json={"refresh_token": this_device}
    ).status_code == 200

    assert client.post(
        "/api/auth/refresh", json={"refresh_token": this_device}
    ).status_code == 401
    assert client.post(
        "/api/auth/refresh", json={"refresh_token": other_device}
    ).status_code == 200


def test_logout_with_unknown_token_succeeds_silently(client):
    assert client.post(
        "/api/auth/logout", json={"refresh_token": "never-issued"}
    ).status_code == 200


def test_logout_all_revokes_access_and_refresh_tokens(client, sample_user):
    tokens = _login(client)
    response = client.post("/api/auth/logout-all", headers=_bearer(tokens["access_token"]))
    assert response.status_code == 200

    assert _me_status(client, tokens["access_token"]) == 401
    assert client.post(
        "/api/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
    ).status_code == 401


def test_password_change_revokes_existing_tokens(client, sample_user):
    tokens = _login(client)
    response = client.put(
        "/api/auth/me",
        headers=_bearer(tokens["access_token"]),
        json={"password": "a-new-password-456"},
    )
    assert response.status_code == 200

    assert _me_status(client, tokens["access_token"]) == 401
    assert client.post(
        "/api/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
    ).status_code == 401
    assert client.post(
        "/api/auth/login",
        json={"username": "testuser", "password": "a-new-password-456"},
    ).status_code == 200


def test_email_change_keeps_existing_tokens(client, sample_user):
    tokens = _login(client)
    assert client.put(
        "/api/auth/me",
        headers=_bearer(tokens["access_token"]),
        json={"email": "changed@example.com"},
    ).status_code == 200
    assert _me_status(client, tokens["access_token"]) == 200


def test_legacy_token_without_version_claim_works_until_first_revocation(
    client, sample_user, db_session
):
    legacy = create_access_token(data={"sub": sample_user.username, "role": sample_user.role})
    assert _me_status(client, legacy) == 200

    sample_user.token_version += 1
    db_session.commit()
    assert _me_status(client, legacy) == 401


def test_admin_can_force_logout_a_user(client, sample_user, admin_headers):
    tokens = _login(client)

    response = client.post(
        f"/api/admin/users/{sample_user.id}/revoke-tokens", headers=admin_headers
    )
    assert response.status_code == 200

    assert _me_status(client, tokens["access_token"]) == 401
    assert client.post(
        "/api/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
    ).status_code == 401


def test_non_admin_cannot_force_logout(client, sample_user, auth_headers):
    assert client.post(
        f"/api/admin/users/{sample_user.id}/revoke-tokens", headers=auth_headers
    ).status_code == 403


def test_force_logout_of_unknown_user_is_404(client, admin_headers):
    assert client.post(
        "/api/admin/users/999999/revoke-tokens", headers=admin_headers
    ).status_code == 404


def test_websocket_rejects_a_revoked_token(client, sample_user, db_session, monkeypatch):
    # The endpoint opens its own session, so point it at the test database.
    import app.database

    monkeypatch.setattr(app.database, "SessionLocal", lambda: _Unclosed(db_session))

    tokens = _login(client)
    sample_user.token_version += 1
    db_session.commit()

    with pytest.raises(WebSocketDisconnect) as closed:
        with client.websocket_connect(f"/ws?token={tokens['access_token']}"):
            pass
    assert closed.value.code == 4003


class _Unclosed:
    """Session proxy whose close() keeps the shared test session usable."""

    def __init__(self, session):
        self._session = session

    def __getattr__(self, name):
        return getattr(self._session, name)

    def close(self):
        pass
