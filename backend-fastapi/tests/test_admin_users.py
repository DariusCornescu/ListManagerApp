from datetime import datetime, timedelta, timezone

import pytest
from fastapi import status

from app import models


def _deny_statuses():
    return (status.HTTP_401_UNAUTHORIZED, status.HTTP_403_FORBIDDEN)


@pytest.mark.parametrize("action", ["deactivate", "reactivate"])
def test_status_actions_are_admin_only(client, auth_headers, sample_user, action):
    url = f"/api/admin/users/{sample_user.id}/{action}"
    assert client.post(url).status_code in _deny_statuses()
    assert client.post(url, headers=auth_headers).status_code == status.HTTP_403_FORBIDDEN


@pytest.mark.parametrize("action", ["deactivate", "reactivate"])
def test_status_actions_on_missing_user(client, admin_headers, action):
    response = client.post(f"/api/admin/users/99999/{action}", headers=admin_headers)
    assert response.status_code == status.HTTP_404_NOT_FOUND


def test_admin_cannot_deactivate_self(client, admin_headers, sample_admin):
    response = client.post(
        f"/api/admin/users/{sample_admin.id}/deactivate", headers=admin_headers
    )
    assert response.status_code == status.HTTP_400_BAD_REQUEST


def test_deactivate_ends_current_logins_and_blocks_new_ones(
    client, admin_headers, sample_user
):
    login = client.post(
        "/api/auth/login", json={"username": "testuser", "password": "password123"}
    ).json()
    headers = {"Authorization": f"Bearer {login['access_token']}"}
    assert client.get("/api/auth/me", headers=headers).status_code == status.HTTP_200_OK

    response = client.post(
        f"/api/admin/users/{sample_user.id}/deactivate", headers=admin_headers
    )
    assert response.status_code == status.HTTP_200_OK
    assert response.json()["is_active"] is False

    assert client.get("/api/auth/me", headers=headers).status_code == status.HTTP_401_UNAUTHORIZED
    assert (
        client.post(
            "/api/auth/refresh", json={"refresh_token": login["refresh_token"]}
        ).status_code
        == status.HTTP_401_UNAUTHORIZED
    )
    assert (
        client.post(
            "/api/auth/login", json={"username": "testuser", "password": "password123"}
        ).status_code
        == status.HTTP_403_FORBIDDEN
    )


def test_reactivate_allows_login_but_not_old_tokens(client, admin_headers, sample_user):
    old = client.post(
        "/api/auth/login", json={"username": "testuser", "password": "password123"}
    ).json()
    client.post(f"/api/admin/users/{sample_user.id}/deactivate", headers=admin_headers)

    response = client.post(
        f"/api/admin/users/{sample_user.id}/reactivate", headers=admin_headers
    )
    assert response.status_code == status.HTTP_200_OK
    assert response.json()["is_active"] is True

    old_headers = {"Authorization": f"Bearer {old['access_token']}"}
    assert client.get("/api/auth/me", headers=old_headers).status_code == status.HTTP_401_UNAUTHORIZED
    assert (
        client.post(
            "/api/auth/login", json={"username": "testuser", "password": "password123"}
        ).status_code
        == status.HTTP_200_OK
    )


def _assert_blocked(response, blocker):
    assert response.status_code == status.HTTP_409_CONFLICT
    detail = response.json()["detail"]
    assert detail["code"] == "user_has_linked_data"
    assert detail["blockers"][blocker] >= 1


def test_delete_blocked_by_team_membership(
    client, admin_headers, db_session, team, team_member_user
):
    response = client.delete(
        f"/api/admin/users/{team_member_user.id}", headers=admin_headers
    )
    _assert_blocked(response, "team_memberships")
    assert db_session.get(models.User, team_member_user.id) is not None


def test_delete_blocked_by_created_team(client, admin_headers, db_session, sample_user):
    db_session.add(models.Team(name="Orphan Team", created_by=sample_user.id))
    db_session.commit()
    response = client.delete(f"/api/admin/users/{sample_user.id}", headers=admin_headers)
    _assert_blocked(response, "teams_created")


def test_delete_blocked_by_created_invite(client, admin_headers, db_session, sample_user):
    team = models.Team(name="Other Team")
    db_session.add(team)
    db_session.commit()
    db_session.add(
        models.TeamInvite(
            team_id=team.id,
            code="invite-code",
            created_by=sample_user.id,
            expires_at=datetime.now(timezone.utc) + timedelta(days=7),
        )
    )
    db_session.commit()
    response = client.delete(f"/api/admin/users/{sample_user.id}", headers=admin_headers)
    _assert_blocked(response, "invites_created")


def test_delete_blocked_by_personal_list(
    client, admin_headers, sample_user, personal_session
):
    response = client.delete(f"/api/admin/users/{sample_user.id}", headers=admin_headers)
    _assert_blocked(response, "lists")


def _inventory_with_item(db_session, user, deleted_at=None):
    inventory = models.Inventory(owner_user_id=user.id)
    db_session.add(inventory)
    db_session.commit()
    db_session.add(
        models.InventoryItem(
            inventory_id=inventory.id,
            item_uuid="11111111-1111-1111-1111-111111111111",
            name="Stock line",
            deleted_at=deleted_at,
        )
    )
    db_session.commit()


def test_delete_blocked_by_live_inventory_item(
    client, admin_headers, db_session, sample_user
):
    _inventory_with_item(db_session, sample_user)
    response = client.delete(f"/api/admin/users/{sample_user.id}", headers=admin_headers)
    _assert_blocked(response, "inventory_items")


def test_delete_allowed_with_only_removed_inventory_items(
    client, admin_headers, db_session, sample_user
):
    _inventory_with_item(db_session, sample_user, deleted_at=datetime.now(timezone.utc))
    response = client.delete(f"/api/admin/users/{sample_user.id}", headers=admin_headers)
    assert response.status_code == status.HTTP_200_OK


def test_delete_integrity_error_answers_409(
    client, admin_headers, db_session, sample_user, monkeypatch
):
    from sqlalchemy.exc import IntegrityError

    def fail_commit():
        raise IntegrityError("DELETE", {}, Exception("linked row appeared"))

    # The blocker check saw nothing, then a linked row appeared before the commit.
    monkeypatch.setattr(db_session, "commit", fail_commit)
    response = client.delete(f"/api/admin/users/{sample_user.id}", headers=admin_headers)
    assert response.status_code == status.HTTP_409_CONFLICT
    assert response.json()["detail"]["code"] == "user_has_linked_data"
