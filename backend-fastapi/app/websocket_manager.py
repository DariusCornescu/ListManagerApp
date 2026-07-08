from fastapi import WebSocket
from typing import List, Dict
import json
import logging

logger = logging.getLogger(__name__)

class ConnectionManager:

    def __init__(self):
        # Store active connections: {user_id: [websocket1, websocket2, ...]}
        self.active_connections: Dict[int, List[WebSocket]] = {}
        # Last known username per connected user (for presence display)
        self.usernames: Dict[int, str] = {}

    async def connect(self, websocket: WebSocket, user_id: int, username: str = None):
        """Accept WebSocket connection"""
        await websocket.accept()

        if user_id not in self.active_connections:
            self.active_connections[user_id] = []

        self.active_connections[user_id].append(websocket)
        if username:
            self.usernames[user_id] = username
        logger.info(f"User {user_id} connected. Total connections: {self.get_total_connections()}")

    def disconnect(self, websocket: WebSocket, user_id: int):
        """Remove WebSocket connection"""
        if user_id in self.active_connections:
            self.active_connections[user_id].remove(websocket)

            # Remove user entry if no more connections
            if not self.active_connections[user_id]:
                del self.active_connections[user_id]
                self.usernames.pop(user_id, None)

        logger.info(f"User {user_id} disconnected. Total connections: {self.get_total_connections()}")

    def online_users(self) -> List[dict]:
        """Currently connected users (deduplicated), sorted by username."""
        users = [
            {"user_id": user_id, "username": self.usernames.get(user_id, "")}
            for user_id in self.active_connections
        ]
        return sorted(users, key=lambda u: u["username"].lower())

    async def broadcast_presence(self):
        """Tell every connected client who is online right now."""
        await self.broadcast({
            "type": "presence",
            "data": {"online": self.online_users()}
        })
    
    async def send_personal_message(self, message: dict, user_id: int):
        """Send message to specific user (all their connections)"""
        if user_id in self.active_connections:
            for connection in self.active_connections[user_id]:
                try:
                    await connection.send_json(message)
                except Exception as e:
                    logger.error(f"Error sending to user {user_id}: {e}")
    
    async def broadcast(self, message: dict, exclude_user_id: int = None):
        """Broadcast message to all connected users"""
        for user_id, connections in self.active_connections.items():
            if exclude_user_id and user_id == exclude_user_id:
                continue
            
            for connection in connections:
                try:
                    await connection.send_json(message)
                except Exception as e:
                    logger.error(f"Error broadcasting to user {user_id}: {e}")
    
    async def broadcast_to_session(self, message: dict, session_id: int, db):
        """Broadcast a message only to users authorized for the given session.

        Recipient resolution:
          - personal session (owner_user_id set) -> {owner_user_id}
          - team session (team_id set) -> all TeamMember.user_id for that team
        If the session does not exist, the message is sent to no one.
        """
        from app import models

        session = (
            db.query(models.GlobalSession)
            .filter(models.GlobalSession.id == session_id)
            .first()
        )
        if session is None:
            return

        if session.owner_user_id is not None:
            recipient_ids = {session.owner_user_id}
        elif session.team_id is not None:
            rows = (
                db.query(models.TeamMember.user_id)
                .filter(models.TeamMember.team_id == session.team_id)
                .all()
            )
            recipient_ids = {row[0] for row in rows}
        else:
            recipient_ids = set()

        for user_id in recipient_ids:
            connections = self.active_connections.get(user_id)
            if not connections:
                continue
            for connection in connections:
                try:
                    await connection.send_json(message)
                except Exception as e:
                    logger.error(f"Error broadcasting to user {user_id}: {e}")

    def get_total_connections(self) -> int:
        """Get total number of active connections"""
        return sum(len(connections) for connections in self.active_connections.values())

# Global instance
manager = ConnectionManager()
