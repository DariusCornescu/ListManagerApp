package com.darius.listmanager.data.websocket

import android.util.Log
import com.darius.listmanager.network.ApiConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject

class WebSocketService private constructor() {
    
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()
    
    private var webSocket: WebSocket? = null
    
    private val _connectionState = MutableStateFlow<WebSocketState>(WebSocketState.Disconnected)
    val connectionState: StateFlow<WebSocketState> = _connectionState.asStateFlow()
    
    private val _messages = MutableStateFlow<WebSocketMessage?>(null)
    val messages: StateFlow<WebSocketMessage?> = _messages.asStateFlow()
    
    companion object {
        private const val TAG = "WebSocketService"
        
        @Volatile
        private var INSTANCE: WebSocketService? = null
        
        fun getInstance(): WebSocketService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebSocketService().also { INSTANCE = it }
            }
        }
    }
    
    fun connect(token: String) {
        if (_connectionState.value is WebSocketState.Connected) {
            Log.d(TAG, "Already connected")
            return
        }
        
        _connectionState.value = WebSocketState.Connecting
        
        // Build WebSocket URL with token using ApiConfig
        val httpUrl = ApiConfig.BASE_URL.replace("http://", "").replace("https://", "").trimEnd('/')
        val wsProtocol = if (ApiConfig.USE_PRODUCTION) "wss" else "ws"
        val wsUrl = "$wsProtocol://$httpUrl/ws?token=$token"
        
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")
        
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                _connectionState.value = WebSocketState.Connected
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message: $text")
                
                try {
                    val json = JSONObject(text)
                    val type = json.getString("type")
                    
                    val message = when (type) {
                        // Connection
                        "connection" -> WebSocketMessage.ConnectionStatus(
                            message = json.getString("message"),
                            userId = json.optLong("user_id"),
                            username = json.optString("username")
                        )
                        
                        // Distributors
                        "distributor_created" -> {
                            val data = json.getJSONObject("data")
                            WebSocketMessage.DistributorCreated(
                                distributorId = data.getLong("distributor_id"),
                                distributorName = data.getString("distributor_name")
                            )
                        }
                        
                        "distributor_updated" -> {
                            val data = json.getJSONObject("data")
                            WebSocketMessage.DistributorUpdated(
                                distributorId = data.getLong("distributor_id"),
                                distributorName = data.getString("distributor_name")
                            )
                        }
                        
                        "distributor_deleted" -> {
                            val data = json.getJSONObject("data")
                            WebSocketMessage.DistributorDeleted(
                                distributorId = data.getLong("distributor_id")
                            )
                        }
                        
                        // Products
                        "product_created" -> {
                            val data = json.getJSONObject("data")
                            WebSocketMessage.ProductCreated(
                                productId = data.getLong("product_id"),
                                productName = data.getString("product_name"),
                                distributorId = data.getLong("distributor_id"),
                                aliases = data.optString("aliases")
                            )
                        }
                        
                        "product_updated" -> {
                            val data = json.getJSONObject("data")
                            WebSocketMessage.ProductUpdated(
                                productId = data.getLong("product_id"),
                                productName = data.getString("product_name"),
                                distributorId = data.getLong("distributor_id"),
                                aliases = data.optString("aliases")
                            )
                        }
                        
                        "product_deleted" -> {
                            val data = json.getJSONObject("data")
                            WebSocketMessage.ProductDeleted(
                                productId = data.getLong("product_id")
                            )
                        }
                        
                        // Sessions
                        "session_created" -> {
                            val data = json.getJSONObject("data")
                            WebSocketMessage.SessionCreated(
                                sessionId = data.getLong("session_id"),
                                sessionName = data.getString("session_name"),
                                userId = data.getLong("user_id"),
                                username = data.getString("username")
                            )
                        }
                        
                        "session_completed" -> {
                            val data = json.getJSONObject("data")
                            WebSocketMessage.SessionCompleted(
                                sessionId = data.getLong("session_id"),
                                totalItems = data.getInt("total_items"),
                                distributorCount = data.getInt("distributor_count"),
                                pdfGenerated = data.getBoolean("pdf_generated")
                            )
                        }
                        
                        "session_cleared" -> {
                            val data = json.getJSONObject("data")
                            WebSocketMessage.SessionCleared(
                                sessionId = data.getLong("session_id")
                            )
                        }
                        
                        // Session Items
                        "session_item_added", "session_item_updated" -> {
                            val data = json.getJSONObject("data")
                            if (type == "session_item_added") {
                                WebSocketMessage.SessionItemAdded(
                                    sessionId = data.getLong("session_id"),
                                    itemId = data.getLong("item_id"),
                                    productId = data.getLong("product_id"),
                                    productName = data.getString("product_name"),
                                    quantity = data.getInt("quantity"),
                                    userId = data.getLong("user_id"),
                                    username = data.getString("username"),
                                    // Older servers omit "version"; default to 1.
                                    version = data.optInt("version", 1)
                                )
                            } else {
                                WebSocketMessage.SessionItemUpdated(
                                    sessionId = data.getLong("session_id"),
                                    itemId = data.getLong("item_id"),
                                    productId = data.getLong("product_id"),
                                    productName = data.getString("product_name"),
                                    oldQuantity = data.getInt("old_quantity"),
                                    newQuantity = data.getInt("new_quantity"),
                                    userId = data.getLong("user_id"),
                                    username = data.getString("username"),
                                    // Older servers omit "version"; default to 1.
                                    version = data.optInt("version", 1)
                                )
                            }
                        }
                        
                        "session_item_deleted" -> {
                            val data = json.getJSONObject("data")
                            WebSocketMessage.SessionItemDeleted(
                                sessionId = data.getLong("session_id"),
                                itemId = data.getLong("item_id"),
                                productId = data.getLong("product_id"),
                                productName = data.getString("product_name"),
                                userId = data.getLong("user_id"),
                                username = data.getString("username")
                            )
                        }
                        
                        // Pong
                        "pong" -> WebSocketMessage.Pong
                        
                        else -> {
                            Log.w(TAG, "Unknown message type: $type")
                            null
                        }
                    }
                    
                    _messages.value = message
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message: ${e.message}", e)
                }
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
                _connectionState.value = WebSocketState.Disconnected
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                _connectionState.value = WebSocketState.Disconnected
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}", t)
                _connectionState.value = WebSocketState.Error(t.message ?: "Unknown error")
            }
        })
    }
    
    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = WebSocketState.Disconnected
    }
    
    fun sendPing() {
        webSocket?.send("ping")
    }
}