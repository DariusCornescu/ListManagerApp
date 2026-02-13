from fastapi import WebSocket
from typing import List, Dict
import json
import logging

logger = logging.getLogger(__name__)

class ConnectionManager:

    def __init__(self):
        # Store active connections: {user_id: [websocket1, websocket2, ...]}
        self.active_connections: Dict[int, List[WebSocket]] = {}
    
    async def connect(self, websocket: WebSocket, user_id: int):
        """Accept WebSocket connection"""
        await websocket.accept()
        
        if user_id not in self.active_connections:
            self.active_connections[user_id] = []
        
        self.active_connections[user_id].append(websocket)
        logger.info(f"User {user_id} connected. Total connections: {self.get_total_connections()}")
    
    def disconnect(self, websocket: WebSocket, user_id: int):
        """Remove WebSocket connection"""
        if user_id in self.active_connections:
            self.active_connections[user_id].remove(websocket)
            
            # Remove user entry if no more connections
            if not self.active_connections[user_id]:
                del self.active_connections[user_id]
        
        logger.info(f"User {user_id} disconnected. Total connections: {self.get_total_connections()}")
    
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
    
    def get_total_connections(self) -> int:
        """Get total number of active connections"""
        return sum(len(connections) for connections in self.active_connections.values())

# Global instance
manager = ConnectionManager()
