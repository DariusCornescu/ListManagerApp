package com.darius.listmanager.data.websocket

sealed class WebSocketState {
    object Connected : WebSocketState()
    object Connecting : WebSocketState()
    object Disconnected : WebSocketState()
    data class Error(val message: String) : WebSocketState()
}