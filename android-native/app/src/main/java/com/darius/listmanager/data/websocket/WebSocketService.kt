package com.darius.listmanager.data.websocket

import android.os.SystemClock
import android.util.Log
import com.darius.listmanager.network.ApiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketService private constructor() {

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        // Without pings a silently dead socket would keep reporting Connected.
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Called when the server refuses the token, so the app can try to refresh it. */
    @Volatile
    var onTokenRejected: (() -> Unit)? = null

    private val connection = ReconnectingSocket<WebSocket>(
        open = ::openSocket,
        close = { it.close(1000, "User disconnected") },
        schedule = ::runAfter,
        now = SystemClock::elapsedRealtime,
        setState = ::publishState,
        onTokenRejected = { onTokenRejected?.invoke() },
    )

    private val _connectionState = MutableStateFlow<WebSocketState>(WebSocketState.Disconnected)
    val connectionState: StateFlow<WebSocketState> = _connectionState.asStateFlow()
    
    private val _messages = MutableStateFlow<WebSocketMessage?>(null)
    val messages: StateFlow<WebSocketMessage?> = _messages.asStateFlow()

    private val _onlineUsers = MutableStateFlow<List<OnlineUser>>(emptyList())
    val onlineUsers: StateFlow<List<OnlineUser>> = _onlineUsers.asStateFlow()

    /** Also fed from the REST fallback (GET /api/presence) when the drawer opens. */
    fun setOnlineUsers(users: List<OnlineUser>) { _onlineUsers.value = users }
    
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
    
    fun connect(token: String) = connection.connect(token)

    /** For logout: stops the socket and every retry until the next [connect]. */
    fun disconnect() = connection.disconnect()

    /** Retries only run in the foreground, and returning to it reconnects at once. */
    fun setForeground(foreground: Boolean) {
        Log.d(TAG, if (foreground) "App in foreground" else "App in background")
        connection.setForeground(foreground)
    }

    fun sendPing() {
        connection.current()?.send("ping")
    }

    private fun publishState(state: WebSocketState) {
        _connectionState.value = state
        if (state is WebSocketState.Disconnected || state is WebSocketState.Error) {
            _onlineUsers.value = emptyList()
        }
    }

    private fun runAfter(delayMillis: Long, action: () -> Unit): () -> Unit {
        Log.d(TAG, "Retrying WebSocket in $delayMillis ms")
        val job = retryScope.launch {
            delay(delayMillis)
            action()
        }
        return { job.cancel() }
    }

    private fun openSocket(token: String): WebSocket {
        // Build WebSocket URL with token using ApiConfig
        val httpUrl = ApiConfig.BASE_URL.replace("http://", "").replace("https://", "").trimEnd('/')
        val wsProtocol = if (ApiConfig.USE_PRODUCTION) "wss" else "ws"
        val wsUrl = "$wsProtocol://$httpUrl/ws?token=$token"
        
        // The URL carries the access token, so it must not be logged.
        Log.d(TAG, "Connecting to WebSocket")
        
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        
        return client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                connection.onOpened(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // A socket that was replaced or closed must not touch the current state.
                if (!connection.isCurrent(webSocket)) return
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

                        // Presence ("Online acum")
                        "presence" -> {
                            val data = json.getJSONObject("data")
                            val arr = data.getJSONArray("online")
                            val users = buildList {
                                for (i in 0 until arr.length()) {
                                    val o = arr.getJSONObject(i)
                                    add(OnlineUser(o.getLong("user_id"), o.optString("username")))
                                }
                            }
                            _onlineUsers.value = users
                            WebSocketMessage.PresenceUpdate(users)
                        }
                        
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
                        
                        "catalog_changed" -> {
                            val data = json.getJSONObject("data")
                            WebSocketMessage.CatalogChanged(changeId = data.getString("change_id"))
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
                        
                        "inventory_changed" -> WebSocketMessage.InventoryChanged(
                            inventoryId = json.getJSONObject("data").getLong("inventory_id")
                        )

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
                // Finish the closing handshake. Not with the received code, which may be
                // one OkHttp refuses to send, such as 1005 when the server gave none.
                webSocket.close(1000, null)
                connection.onDropped(webSocket, WebSocketState.Disconnected, isRejectedClose(code))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                connection.onDropped(webSocket, WebSocketState.Disconnected, isRejectedClose(code))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}", t)
                connection.onDropped(
                    webSocket,
                    WebSocketState.Error(t.message ?: "Unknown error"),
                    isRejectedHandshake(response?.code),
                )
            }
        })
    }
}