package com.darius.listmanager

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.local.entity.SessionItemEntity
import com.darius.listmanager.data.repository.*
import com.darius.listmanager.data.websocket.WebSocketService
import com.darius.listmanager.data.workspace.SessionEvents
import com.darius.listmanager.data.websocket.WebSocketMessage
import com.darius.listmanager.data.websocket.WebSocketState
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.sync.SyncService
import com.darius.listmanager.sync.SyncWorkManager
import com.darius.listmanager.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    
    private val webSocketService = WebSocketService.getInstance()
    private lateinit var syncService: SyncService
    private lateinit var database: AppDatabase
    
    companion object { private const val TAG = "MainActivity"}
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        Log.d(TAG, "MainActivity starting...")
        database = AppDatabase.getInstance(applicationContext)
        syncService = SyncService(applicationContext)
        
        // Set auth token for Retrofit if available
        syncService.getAuthToken()?.let { token ->
            RetrofitClient.setAuthToken(token)
        }

        // Restore observable login state from persisted token
        if (syncService.isLoggedIn()) {
            val authPrefs = getSharedPreferences("auth", 0)
            com.darius.listmanager.data.repository.AuthState.setLoggedIn(
                true,
                authPrefs.getString("saved_username", null)
            )
            // Last-known role so role-gated UI (catalog editing) works offline
            // too; the server refresh overwrites it as soon as we're reachable.
            // Guarded so a live in-memory role is never downgraded by disk.
            if (com.darius.listmanager.data.repository.AuthState.role.value == null) {
                com.darius.listmanager.data.repository.AuthState.setRole(
                    authPrefs.getString("saved_role", null)
                )
            }
        }

        // Handle token expiry (HTTP 401 on an authenticated request): clear session,
        // disconnect the WebSocket and signal the app to route to login.
        RetrofitClient.onUnauthorized = {
            Log.w(TAG, "Received 401 - session expired, clearing auth")
            syncService.clearAuthToken()
            webSocketService.disconnect()
            com.darius.listmanager.data.repository.AuthState.notifySessionExpired()
        }

        SyncWorkManager.initialize(applicationContext)
        Log.d(TAG, "SyncWorkManager initialized")
        
        setupWebSocket()

        setContent {
            AppTheme {
                AppContent()
            }
        }
    }

    private fun setupWebSocket() {
        lifecycleScope.launch {
            val token = getAuthToken()
            
            if (token != null) {
                Log.d(TAG, "Connecting WebSocket with token...")
                webSocketService.connect(token)
                webSocketService.connectionState.collect { state ->
                    when (state) {
                        is WebSocketState.Connected -> { Log.d(TAG, "WebSocket connected successfully") }
                        is WebSocketState.Connecting -> { Log.d(TAG, "WebSocket connecting...") }
                        is WebSocketState.Disconnected -> { Log.d(TAG, "WebSocket disconnected") }
                        is WebSocketState.Error -> { Log.e(TAG, "WebSocket error: ${state.message}") }
                    }
                }
            } else {
                Log.d(TAG, "No auth token found - WebSocket not started")
                Log.d(TAG, "WebSocket will connect after login")
            }
        }
        
        // Collect WebSocket messages and update local database
        lifecycleScope.launch {
            webSocketService.messages.collect { message ->
                when (message) {
                    is WebSocketMessage.DistributorCreated -> {
                        Log.d(TAG, "WS: Distributor created - ${message.distributorName}")
                        updateLocalDistributor(message.distributorId, message.distributorName)
                    }
                    
                    is WebSocketMessage.DistributorUpdated -> {
                        Log.d(TAG, "WS: Distributor updated - ${message.distributorName}")
                        updateLocalDistributor(message.distributorId, message.distributorName)
                    }
                    
                    is WebSocketMessage.DistributorDeleted -> {
                        Log.d(TAG, "WS: Distributor deleted - ID ${message.distributorId}")
                        deleteLocalDistributor(message.distributorId)
                    }
                    
                    is WebSocketMessage.ProductCreated -> {
                        Log.d(TAG, "WS: Product created - ${message.productName}")
                        updateLocalProduct( message.productId, message.productName, message.distributorId,  message.aliases )
                    }
                    
                    is WebSocketMessage.ProductUpdated -> {
                        Log.d(TAG, "WS: Product updated - ${message.productName}")
                        updateLocalProduct(message.productId, message.productName, message.distributorId, message.aliases )
                    }
                    
                    is WebSocketMessage.ProductDeleted -> {
                        Log.d(TAG, "WS: Product deleted - ID ${message.productId}")
                        deleteLocalProduct(message.productId)
                    }
                    
                    is WebSocketMessage.ConnectionStatus -> {
                        Log.d(TAG, "WS: ${message.message}")
                    }
                    
                    // ==== SESSION MESSAGES - Real-time sync pentru multi-device ====

                    is WebSocketMessage.SessionItemAdded -> {
                        Log.d(TAG, "WS: Session item added - ${message.productName} x${message.quantity}")
                        updateLocalSessionItem( message.sessionId, message.itemId, message.productId, message.quantity, message.version )
                    }
                    
                    is WebSocketMessage.SessionItemUpdated -> {
                        Log.d(TAG, "WS: Session item updated - ${message.productName}: ${message.oldQuantity} → ${message.newQuantity}")
                        updateLocalSessionItem( message.sessionId, message.itemId, message.productId, message.newQuantity, message.version )
                    }
                    
                    is WebSocketMessage.SessionItemDeleted -> {
                        Log.d(TAG, "WS: Session item deleted - ${message.productName}")
                        deleteLocalSessionItem(message.itemId)
                    }
                    
                    is WebSocketMessage.SessionCleared -> {
                        Log.d(TAG, "WS: Session cleared - ID ${message.sessionId}")
                        clearLocalSession(message.sessionId)
                    }
                    
                    is WebSocketMessage.SessionCompleted -> {
                        Log.d(TAG, "WS: Session completed - ID ${message.sessionId}")
                        completeLocalSession(message.sessionId)
                    }

                    is WebSocketMessage.SessionCreated -> {
                        Log.d(TAG, "WS: Session created - ${message.sessionName}")
                        SessionEvents.requestRefresh()
                    }
                    else -> { }
                }
            }
        }
    }
    
    // ===== DATABASE UPDATE FUNCTIONS FOR WEBSOCKET MESSAGES =====
    
    private fun updateLocalDistributor(id: Long, name: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val entity = DistributorEntity(id = id, distributorName = name)
                    database.distributorDao().upsert(entity)
                    Log.d(TAG, "Local DB: Upserted distributor $id - $name")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update local distributor: ${e.message}", e)
                }
            }
        }
    }
    
    private fun deleteLocalDistributor(id: Long) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    database.distributorDao().deleteById(id)
                    Log.d(TAG, "Local DB: Deleted distributor $id")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete local distributor: ${e.message}", e)
                }
            }
        }
    }
    
    private fun updateLocalProduct(id: Long, name: String, distributorId: Long, aliases: String?) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val entity = ProductEntity(
                        id = id, 
                        name = name, 
                        distributorId = distributorId, 
                        aliases = aliases
                    )
                    database.productDao().insert(entity)
                    Log.d(TAG, "Local DB: Upserted product $id - $name")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update local product: ${e.message}", e)
                }
            }
        }
    }
    
    private fun deleteLocalProduct(id: Long) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    database.productDao().deleteById(id)
                    Log.d(TAG, "Local DB: Deleted product $id")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete local product: ${e.message}", e)
                }
            }
        }
    }
    
    // ===== SESSION ITEM UPDATE FUNCTIONS FOR WEBSOCKET =====
    
    private fun updateLocalSessionItem(sessionId: Long, itemId: Long, productId: Long, quantity: Int, version: Int) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val entity = SessionItemEntity(
                        id = itemId,
                        sessionId = sessionId,
                        productId = productId,
                        quantity = quantity,
                        // Preserve the server's optimistic-lock version; REPLACE
                        // insert would otherwise reset it to the default (1).
                        version = version
                    )
                    database.sessionItemDao().insert(entity)
                    Log.d(TAG, "Local DB: Upserted session item $itemId (product $productId, qty $quantity)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update local session item: ${e.message}", e)
                }
            }
        }
    }
    
    private fun deleteLocalSessionItem(itemId: Long) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    database.sessionItemDao().deleteById(itemId)
                    Log.d(TAG, "Local DB: Deleted session item $itemId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete local session item: ${e.message}", e)
                }
            }
        }
    }
    
    private fun completeLocalSession(sessionId: Long) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    database.sessionDao().completeSession(sessionId)
                    Log.d(TAG, "Local DB: Marked session $sessionId completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to complete local session: ${e.message}", e)
                }
            }
            // Re-resolve after the local state is updated so the session
            // screen picks up the replacement active session.
            SessionEvents.requestRefresh()
        }
    }

    private fun clearLocalSession(sessionId: Long) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    database.sessionItemDao().deleteAllInSession(sessionId)
                    Log.d(TAG, "Local DB: Cleared all items from session $sessionId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear local session: ${e.message}", e)
                }
            }
        }
    }
    
    private fun getAuthToken(): String? {        
        return syncService.getAuthToken()
    }
    
    private fun testDatabaseAccess(database: AppDatabase) {
        lifecycleScope.launch {
            try {
                val pendingOperationRepo = PendingOperationRepository(database.pendingOperationDao())
                val distributorRepo = DistributorRepository(
                    database.distributorDao(),
                    RetrofitClient.api,
                    pendingOperationRepo,
                    applicationContext
                )
                val productRepo = ProductRepository(
                    database.productDao(),
                    pendingOperationRepo,
                    applicationContext,
                    RetrofitClient.api
                )
                
                val distributors = distributorRepo.getAll()
                val products = productRepo.getAll()
                
                Log.d(TAG, "DATABASE TEST:")
                Log.d(TAG, "   Distributors: ${distributors.size}")
                Log.d(TAG, "   Products: ${products.size}")
                
                distributors.take(3).forEach {
                    Log.d(TAG, "   ${it.distributorName}")
                }
                products.take(5).forEach {
                    Log.d(TAG, "   ${it.name} (${it.aliases})")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Database test failed: ${e.message}", e)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        webSocketService.disconnect()
        Log.d(TAG, "WebSocket disconnected")
    }
}