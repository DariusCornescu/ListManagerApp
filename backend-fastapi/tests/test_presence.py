import asyncio

from fastapi import status

from app.websocket_manager import ConnectionManager, manager as global_manager


def run(coro):
    return asyncio.new_event_loop().run_until_complete(coro)


class FakeWebSocket:
    def __init__(self):
        self.sent = []
        self.accepted = False

    async def accept(self):
        self.accepted = True

    async def send_json(self, message):
        self.sent.append(message)


# ==================== ConnectionManager unit tests ====================

def test_connect_tracks_username_and_online_list():
    m = ConnectionManager()
    ws = FakeWebSocket()
    run(m.connect(ws, user_id=1, username="tata"))
    assert m.online_users() == [{"user_id": 1, "username": "tata"}]


def test_online_users_sorted_and_deduplicated():
    m = ConnectionManager()
    run(m.connect(FakeWebSocket(), 2, "mihai"))
    run(m.connect(FakeWebSocket(), 1, "Ana"))
    run(m.connect(FakeWebSocket(), 1, "Ana"))  # second device, same user
    users = m.online_users()
    assert users == [
        {"user_id": 1, "username": "Ana"},
        {"user_id": 2, "username": "mihai"},
    ]


def test_disconnect_removes_user_only_after_last_connection():
    m = ConnectionManager()
    ws1, ws2 = FakeWebSocket(), FakeWebSocket()
    run(m.connect(ws1, 1, "tata"))
    run(m.connect(ws2, 1, "tata"))
    m.disconnect(ws1, 1)
    assert m.online_users() == [{"user_id": 1, "username": "tata"}]
    m.disconnect(ws2, 1)
    assert m.online_users() == []


def test_broadcast_presence_reaches_all_connections():
    m = ConnectionManager()
    ws1, ws2 = FakeWebSocket(), FakeWebSocket()
    run(m.connect(ws1, 1, "tata"))
    run(m.connect(ws2, 2, "ana"))
    run(m.broadcast_presence())
    for ws in (ws1, ws2):
        presence = [msg for msg in ws.sent if msg.get("type") == "presence"]
        assert presence, "every client gets the presence message"
        online = presence[-1]["data"]["online"]
        assert {"user_id": 1, "username": "tata"} in online
        assert {"user_id": 2, "username": "ana"} in online


# ==================== REST endpoint tests ====================

def test_presence_requires_auth(client):
    resp = client.get("/api/presence")
    assert resp.status_code in (status.HTTP_401_UNAUTHORIZED, status.HTTP_403_FORBIDDEN)


def test_presence_returns_online_users(client, auth_headers):
    ws = FakeWebSocket()
    run(global_manager.connect(ws, user_id=99, username="tata"))
    try:
        resp = client.get("/api/presence", headers=auth_headers)
        assert resp.status_code == status.HTTP_200_OK
        assert {"user_id": 99, "username": "tata"} in resp.json()
    finally:
        global_manager.disconnect(ws, 99)


def test_presence_empty_when_nobody_connected(client, auth_headers):
    resp = client.get("/api/presence", headers=auth_headers)
    assert resp.status_code == status.HTTP_200_OK
    assert resp.json() == []
