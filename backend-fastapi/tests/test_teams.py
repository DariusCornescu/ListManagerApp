# tests/test_teams.py
#
# Phase 2 (Teams) endpoint tests. These are written to the Phase 2 spec and
# will FAIL until Wave 2 mounts the teams router and adds the access checks.
#
# Conventions (per Phase 2 spec):
#   - resource the caller cannot see  => 404 (hide existence)
#   - authenticated member lacking required TEAM role => 403
#   - missing/invalid token => 403 (HTTPBearer auto_error)
#   - TeamMember.role (admin|member) is TEAM-scoped, separate from User.role.
import pytest
from fastapi import status


class TestTeamCrud:
    """Happy-path team CRUD."""

    def test_create_team(self, client, auth_headers):
        """An authenticated user can create a team and becomes its admin."""
        response = client.post(
            "/api/teams",
            json={"name": "My New Team"},
            headers=auth_headers,
        )

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["name"] == "My New Team"
        assert "id" in data

    def test_create_team_no_auth(self, client):
        """Creating a team without a token is rejected (403, HTTPBearer)."""
        response = client.post("/api/teams", json={"name": "No Auth Team"})

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_list_my_teams(self, client, auth_headers, team):
        """A team admin/member sees their team in the listing."""
        response = client.get("/api/teams", headers=auth_headers)

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        team_ids = [t["id"] for t in data]
        assert team.id in team_ids

    def test_list_teams_excludes_non_member(self, client, non_member_headers, team):
        """A non-member does not see a team they don't belong to."""
        response = client.get("/api/teams", headers=non_member_headers)

        assert response.status_code == status.HTTP_200_OK
        team_ids = [t["id"] for t in response.json()]
        assert team.id not in team_ids

    def test_get_team_as_member(self, client, auth_headers, team):
        """A member can retrieve their team's details."""
        response = client.get(f"/api/teams/{team.id}", headers=auth_headers)

        assert response.status_code == status.HTTP_200_OK
        assert response.json()["id"] == team.id

    def test_get_team_as_non_member_is_404(self, client, non_member_headers, team):
        """A non-member retrieving a team gets 404 (existence hidden)."""
        response = client.get(f"/api/teams/{team.id}", headers=non_member_headers)

        assert response.status_code == status.HTTP_404_NOT_FOUND

    def test_update_team_as_admin(self, client, auth_headers, team):
        """A team admin can rename the team."""
        response = client.put(
            f"/api/teams/{team.id}",
            json={"name": "Renamed Team"},
            headers=auth_headers,
        )

        assert response.status_code == status.HTTP_200_OK
        assert response.json()["name"] == "Renamed Team"

    def test_update_team_as_member_is_403(self, client, team_member_headers, team):
        """A non-admin member cannot rename the team (has access but lacks role)."""
        response = client.put(
            f"/api/teams/{team.id}",
            json={"name": "Hacked Name"},
            headers=team_member_headers,
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_update_team_as_non_member_is_404(self, client, non_member_headers, team):
        """A non-member updating a team gets 404 (existence hidden)."""
        response = client.put(
            f"/api/teams/{team.id}",
            json={"name": "Hacked Name"},
            headers=non_member_headers,
        )

        assert response.status_code == status.HTTP_404_NOT_FOUND

    def test_delete_team_as_admin(self, client, auth_headers, team):
        """A team admin can delete the team."""
        response = client.delete(f"/api/teams/{team.id}", headers=auth_headers)

        assert response.status_code == status.HTTP_200_OK

    def test_delete_team_as_member_is_403(self, client, team_member_headers, team):
        """A non-admin member cannot delete the team."""
        response = client.delete(f"/api/teams/{team.id}", headers=team_member_headers)

        assert response.status_code == status.HTTP_403_FORBIDDEN


class TestTeamMembers:
    """Listing / managing members."""

    def test_list_members_as_member(self, client, auth_headers, team, team_member_user):
        """A member can list the team's members."""
        response = client.get(f"/api/teams/{team.id}/members", headers=auth_headers)

        assert response.status_code == status.HTTP_200_OK
        user_ids = [m["user_id"] for m in response.json()]
        assert team_member_user.id in user_ids

    def test_list_members_as_non_member_is_404(self, client, non_member_headers, team):
        """A non-member listing members gets 404 (existence hidden)."""
        response = client.get(
            f"/api/teams/{team.id}/members", headers=non_member_headers
        )

        assert response.status_code == status.HTTP_404_NOT_FOUND


class TestInviteLifecycle:
    """Invite creation and acceptance."""

    def test_admin_creates_invite(self, client, auth_headers, team):
        """A team admin can create an invite code."""
        response = client.post(
            f"/api/teams/{team.id}/invites",
            json={"role": "member"},
            headers=auth_headers,
        )

        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert "code" in data
        assert data["code"]

    def test_member_cannot_create_invite_is_403(
        self, client, team_member_headers, team
    ):
        """A non-admin member creating an invite is forbidden (403)."""
        response = client.post(
            f"/api/teams/{team.id}/invites",
            json={"role": "member"},
            headers=team_member_headers,
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN

    def test_non_member_cannot_create_invite_is_404(
        self, client, non_member_headers, team
    ):
        """A non-member creating an invite gets 404 (existence hidden)."""
        response = client.post(
            f"/api/teams/{team.id}/invites",
            json={"role": "member"},
            headers=non_member_headers,
        )

        assert response.status_code == status.HTTP_404_NOT_FOUND

    def test_non_member_accepts_invite_and_gains_access(
        self, client, non_member_headers, team, valid_invite, team_session
    ):
        """
        Full happy-path: a non-member accepts a valid invite, becomes a member,
        and then has access to the team's session.
        """
        # Before accepting: the non-member cannot see the team session items.
        before = client.get(
            f"/api/session/{team_session.id}/items", headers=non_member_headers
        )
        assert before.status_code == status.HTTP_404_NOT_FOUND

        accept = client.post(
            f"/api/teams/invites/{valid_invite.code}/accept",
            headers=non_member_headers,
        )
        assert accept.status_code == status.HTTP_200_OK

        # After accepting: the new member can see the team session items.
        after = client.get(
            f"/api/session/{team_session.id}/items", headers=non_member_headers
        )
        assert after.status_code == status.HTTP_200_OK

    def test_accept_single_use_invite_twice_is_400(
        self, client, non_member_headers, second_user_headers, team, valid_invite
    ):
        """Re-accepting a single-use (already accepted) invite returns 400."""
        first = client.post(
            f"/api/teams/invites/{valid_invite.code}/accept",
            headers=non_member_headers,
        )
        assert first.status_code == status.HTTP_200_OK

        # A second, different user trying to reuse the consumed code => 400.
        second = client.post(
            f"/api/teams/invites/{valid_invite.code}/accept",
            headers=second_user_headers,
        )
        assert second.status_code == status.HTTP_400_BAD_REQUEST

    def test_accept_invalid_invite_code_is_404(self, client, non_member_headers):
        """Accepting a non-existent invite code returns 404."""
        response = client.post(
            "/api/teams/invites/this-code-does-not-exist/accept",
            headers=non_member_headers,
        )

        assert response.status_code == status.HTTP_404_NOT_FOUND


class TestLastAdminProtection:
    """The sole remaining admin of a team cannot be demoted or removed."""

    def test_cannot_demote_last_admin_is_400(self, client, auth_headers, team, sample_user):
        """Demoting the only admin to member is rejected with 400."""
        response = client.put(
            f"/api/teams/{team.id}/members/{sample_user.id}",
            json={"role": "member"},
            headers=auth_headers,
        )

        assert response.status_code == status.HTTP_400_BAD_REQUEST

    def test_cannot_remove_last_admin_is_400(self, client, auth_headers, team, sample_user):
        """Removing the only admin from the team is rejected with 400."""
        response = client.delete(
            f"/api/teams/{team.id}/members/{sample_user.id}",
            headers=auth_headers,
        )

        assert response.status_code == status.HTTP_400_BAD_REQUEST


class TestLeaveTeam:
    """Self-removal (leave team) — any member may remove themselves."""

    def test_member_can_leave_team(self, client, team_member_headers, team):
        """A non-admin member can remove themselves from the team."""
        me = client.get("/api/auth/me", headers=team_member_headers).json()

        response = client.delete(
            f"/api/teams/{team.id}/members/{me['id']}",
            headers=team_member_headers,
        )

        assert response.status_code == status.HTTP_200_OK
        # They no longer see the team.
        listing = client.get("/api/teams", headers=team_member_headers)
        assert team.id not in [t["id"] for t in listing.json()]

    def test_last_admin_cannot_leave(self, client, auth_headers, team):
        """The last admin cannot remove themselves (team would be orphaned)."""
        me = client.get("/api/auth/me", headers=auth_headers).json()

        response = client.delete(
            f"/api/teams/{team.id}/members/{me['id']}",
            headers=auth_headers,
        )

        assert response.status_code == status.HTTP_400_BAD_REQUEST

    def test_member_still_cannot_remove_others(
        self, client, team_member_headers, auth_headers, team
    ):
        """A non-admin member removing someone ELSE is still forbidden (403)."""
        admin = client.get("/api/auth/me", headers=auth_headers).json()

        response = client.delete(
            f"/api/teams/{team.id}/members/{admin['id']}",
            headers=team_member_headers,
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN
