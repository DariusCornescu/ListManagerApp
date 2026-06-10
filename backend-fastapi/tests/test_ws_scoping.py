# tests/test_ws_scoping.py
"""Unit tests for ConnectionManager.broadcast_to_session scoping.

These verify that a websocket "session event" is delivered ONLY to users
authorized for that session:
  - team session -> every TeamMember of the team (and nobody else)
  - personal session -> only the owner
  - missing session -> nobody

We use the deterministic unit-test approach: register fake websocket objects
(each capturing send_json calls) into a ConnectionManager keyed by user_id,
then assert which ones received the message.
"""
import asyncio

import pytest

from app.websocket_manager import ConnectionManager


class FakeWebSocket:
    """A stand-in for a Starlette WebSocket that records send_json payloads."""

    def __init__(self):
        self.sent = []

    async def send_json(self, message):
        self.sent.append(message)


def _run(coro):
    return asyncio.new_event_loop().run_until_complete(coro)


def test_team_session_event_reaches_members_not_outsiders(
    db_session, team, team_session, sample_user, team_member_user, non_member_user
):
    """A team-session event reaches the admin + member, but NOT a non-member."""
    manager = ConnectionManager()

    admin_ws = FakeWebSocket()      # sample_user is the team admin
    member_ws = FakeWebSocket()     # team_member_user is a member
    outsider_ws = FakeWebSocket()   # non_member_user belongs to no team

    manager.active_connections[sample_user.id] = [admin_ws]
    manager.active_connections[team_member_user.id] = [member_ws]
    manager.active_connections[non_member_user.id] = [outsider_ws]

    message = {"type": "item_added", "session_id": team_session.id}
    _run(manager.broadcast_to_session(message, team_session.id, db_session))

    # Both team members receive it.
    assert admin_ws.sent == [message]
    assert member_ws.sent == [message]
    # The non-member must NOT receive it.
    assert outsider_ws.sent == []


def test_personal_session_event_reaches_only_owner(
    db_session, personal_session, sample_user, non_member_user
):
    """A personal-session event reaches only the owner."""
    manager = ConnectionManager()

    owner_ws = FakeWebSocket()
    other_ws = FakeWebSocket()

    manager.active_connections[sample_user.id] = [owner_ws]
    manager.active_connections[non_member_user.id] = [other_ws]

    message = {"type": "item_added", "session_id": personal_session.id}
    _run(manager.broadcast_to_session(message, personal_session.id, db_session))

    assert owner_ws.sent == [message]
    assert other_ws.sent == []


def test_missing_session_sends_to_no_one(db_session, sample_user):
    """An event for a non-existent session is delivered to nobody."""
    manager = ConnectionManager()

    ws = FakeWebSocket()
    manager.active_connections[sample_user.id] = [ws]

    _run(manager.broadcast_to_session({"type": "x"}, 999999, db_session))

    assert ws.sent == []


def test_team_event_with_owner_offline_reaches_other_members(
    db_session, team, team_session, sample_user, team_member_user
):
    """Recipients with no live connection are simply skipped (no error)."""
    manager = ConnectionManager()

    member_ws = FakeWebSocket()
    # Only the member is connected; the admin has no active connection.
    manager.active_connections[team_member_user.id] = [member_ws]

    message = {"type": "item_changed", "session_id": team_session.id}
    _run(manager.broadcast_to_session(message, team_session.id, db_session))

    assert member_ws.sent == [message]


def test_send_exception_does_not_break_other_recipients(
    db_session, team, team_session, sample_user, team_member_user
):
    """A failing connection is logged/guarded and does not stop delivery."""
    manager = ConnectionManager()

    class ExplodingWebSocket(FakeWebSocket):
        async def send_json(self, message):
            raise RuntimeError("connection gone")

    bad_ws = ExplodingWebSocket()
    good_ws = FakeWebSocket()

    manager.active_connections[sample_user.id] = [bad_ws]
    manager.active_connections[team_member_user.id] = [good_ws]

    message = {"type": "item_added", "session_id": team_session.id}
    # Should not raise despite the exploding connection.
    _run(manager.broadcast_to_session(message, team_session.id, db_session))

    assert good_ws.sent == [message]
