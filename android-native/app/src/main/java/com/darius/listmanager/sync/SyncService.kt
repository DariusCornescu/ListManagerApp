package com.darius.listmanager.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.data.model.*
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class SyncStatus( val isSyncing: Boolean = false, val pendingCount: Int = 0, val failedCount: Int = 0, val lastSyncTime: Long? = null, val lastError: String? = null, val currentOperation: String? = null )

class SyncService(private val context: Context) {
    
    private val database = AppDatabase.getInstance(context)
    private val api = RetrofitClient.api
    private val pendingOperationRepo = PendingOperationRepository(database.pendingOperationDao())

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "sync_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Sync status flow
    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    companion object {
        private const val TAG = "SyncService"
        private const val PREF_LAST_SYNC = "last_sync_time"
    }

    // ===== AUTHENTICATION =====

    fun saveAuthToken(token: String) {
        prefs.edit().putString("auth_token", token).apply()
        RetrofitClient.setAuthToken(token)
    }

    fun getAuthToken(): String? {
        return prefs.getString("auth_token", null)
    }

    fun clearAuthToken() {
        prefs.edit().remove("auth_token").apply()
        RetrofitClient.setAuthToken(null)
    }

    fun isLoggedIn(): Boolean { return getAuthToken() != null }

    // ===== CATALOG SYNC (Download from Server) =====

    suspend fun syncCatalog() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting catalog sync...")

            val productsResponse = api.getProducts()
            val distributorsResponse = api.getDistributors()

            if (productsResponse.isSuccessful && distributorsResponse.isSuccessful) {
                val serverProducts = productsResponse.body() ?: emptyList()
                val serverDistributors = distributorsResponse.body() ?: emptyList()

                Log.d(TAG, "Syncing ${serverProducts.size} products, ${serverDistributors.size} distributors")

                // Insert distributors first
                serverDistributors.forEach { dto ->
                    val distributor = DistributorEntity( id = dto.id, distributorName = dto.distributor_name )
                    database.distributorDao().insert(distributor)
                }

                // Insert products
                serverProducts.forEach { dto ->
                    val product = ProductEntity( id = dto.id, name = dto.name, distributorId = dto.distributor_id, aliases = dto.aliases )
                    database.productDao().insert(product)
                }

                // Update last sync time
                prefs.edit().putLong(PREF_LAST_SYNC, System.currentTimeMillis()).apply()

                Log.d(TAG, "Catalog sync completed successfully!")
            } else {
                Log.e(TAG, "Catalog sync failed: ${productsResponse.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Catalog sync exception: ${e.message}", e)
        }
    }

    // ===== PENDING OPERATIONS SYNC (Upload to Server) =====

    suspend fun syncPendingOperations(): SyncResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "syncPendingOperations() started")
        try {
            _syncStatus.value = _syncStatus.value.copy(isSyncing = true)
            Log.d(TAG, "isSyncing = true")

            // Get pending operations
            val pendingOps = pendingOperationRepo.getReadyForSync()
            Log.d(TAG, "Found ${pendingOps.size} pending operations to sync")
            
            pendingOps.forEachIndexed { index, op ->
                Log.d(TAG, "  ${index + 1}. ${op.operationType} (ID: ${op.id}, Status: ${op.status})")
            }

            if (pendingOps.isEmpty()) {
                Log.d(TAG, "No pending operations found - nothing to sync")
                _syncStatus.value = _syncStatus.value.copy( isSyncing = false, lastSyncTime = System.currentTimeMillis() )
                Log.d(TAG, "════════════════════════════════════════════════")
                return@withContext SyncResult.Success()
            }

            var successCount = 0
            var failCount = 0

            // Process each operation
            pendingOps.forEach { operation ->
                _syncStatus.value = _syncStatus.value.copy(
                    currentOperation = "${operation.operationType} (${operation.id})"
                )

                val result = processOperation(operation)

                when (result) {
                    is SyncResult.Success -> {
                        pendingOperationRepo.markAsCompleted(operation.id)
                        successCount++
                        Log.d(TAG, "Operation ${operation.id} completed successfully")
                    }
                    is SyncResult.Error -> {
                        if (result.retryable) {
                            pendingOperationRepo.markAsFailed(operation.id, result.message)
                            Log.e(TAG, "Operation ${operation.id} failed (retryable): ${result.message}")
                        } else {
                            // Non-retryable (401/403/422 etc.) — drop the op so it doesn't loop forever
                            pendingOperationRepo.deleteById(operation.id)
                            Log.e(TAG, "Operation ${operation.id} failed (non-retryable, dropped): ${result.message}")
                        }
                        failCount++
                    }
                    is SyncResult.Conflict -> {
                        // Handle conflict based on strategy
                        handleConflict(operation, result)
                        failCount++
                        Log.w(TAG, "Operation ${operation.id} conflict: ${result.message}")
                    }
                }
            }

            // Clean up old completed operations
            pendingOperationRepo.deleteOldCompleted(olderThanDays = 7)

            _syncStatus.value = _syncStatus.value.copy( isSyncing = false, lastSyncTime = System.currentTimeMillis(), currentOperation = null )
            Log.d(TAG, "Sync completed: $successCount success, $failCount failed")
            Log.d(TAG, "════════════════════════════════════════════════")

            return@withContext if (failCount == 0) {
                SyncResult.Success()
            } else {
                SyncResult.Error("$failCount operations failed", retryable = true)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Sync exception: ${e.javaClass.simpleName}", e)
            Log.e(TAG, "Message: ${e.message}")
            _syncStatus.value = _syncStatus.value.copy(isSyncing = false, lastError = e.message, currentOperation = null )
            Log.d(TAG, "════════════════════════════════════════════════")
            return@withContext SyncResult.Error(e.message ?: "Unknown error", retryable = true)
        }
    }

    // ===== PROCESS INDIVIDUAL OPERATION =====

    private suspend fun processOperation(operation: PendingOperationEntity): SyncResult {
        try {
            pendingOperationRepo.markAsInProgress(operation.id)

            val operationType = OperationType.valueOf(operation.operationType)
            val json = Json { ignoreUnknownKeys = true }

            return when (operationType) {
                // DISTRIBUTOR OPERATIONS
                OperationType.CREATE_DISTRIBUTOR -> {
                    val data = json.decodeFromString<OperationData.CreateDistributor>(operation.operationData)
                    createDistributor(data)
                }
                OperationType.UPDATE_DISTRIBUTOR -> {
                    val data = json.decodeFromString<OperationData.UpdateDistributor>(operation.operationData)
                    updateDistributor(data)
                }
                OperationType.DELETE_DISTRIBUTOR -> {
                    val data = json.decodeFromString<OperationData.DeleteDistributor>(operation.operationData)
                    deleteDistributor(data)
                }

                // PRODUCT OPERATIONS
                OperationType.CREATE_PRODUCT -> {
                    val data = json.decodeFromString<OperationData.CreateProduct>(operation.operationData)
                    createProduct(data)
                }
                OperationType.UPDATE_PRODUCT -> {
                    val data = json.decodeFromString<OperationData.UpdateProduct>(operation.operationData)
                    updateProduct(data)
                }
                OperationType.DELETE_PRODUCT -> {
                    val data = json.decodeFromString<OperationData.DeleteProduct>(operation.operationData)
                    deleteProduct(data)
                }

                // SESSION ITEM OPERATIONS
                OperationType.ADD_SESSION_ITEM -> {
                    val data = json.decodeFromString<OperationData.AddSessionItem>(operation.operationData)
                    addSessionItem(data)
                }
                OperationType.UPDATE_SESSION_ITEM -> {
                    val data = json.decodeFromString<OperationData.UpdateSessionItem>(operation.operationData)
                    updateSessionItem(data)
                }
                OperationType.DELETE_SESSION_ITEM -> {
                    val data = json.decodeFromString<OperationData.DeleteSessionItem>(operation.operationData)
                    deleteSessionItem(data)
                }
                OperationType.CLEAR_SESSION -> {
                    val data = json.decodeFromString<OperationData.ClearSession>(operation.operationData)
                    clearSession(data)
                }

                else -> SyncResult.Error("Unsupported operation type: $operationType", retryable = false)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing operation ${operation.id}", e)
            return SyncResult.Error(e.message ?: "Unknown error", retryable = true)
        }
    }

    /**
     * Maps a non-2xx HTTP status to a non-retryable [SyncResult.Error] when the server has
     * definitively rejected the operation (400 bad request — e.g. session no longer active,
     * 401/403 authorization, 422 validation). Such operations will never succeed on retry,
     * so the caller drops them from the queue.
     * Returns a retryable error for everything else (transient server/5xx issues).
     */
    private fun mapHttpErrorToSyncResult(code: Int): SyncResult {
        return when (code) {
            400, 401, 403, 422 -> SyncResult.Error("Rejected by server: $code", retryable = false)
            else -> SyncResult.Error("Failed: $code", retryable = true)
        }
    }

    // ===== DISTRIBUTOR OPERATIONS =====

    private suspend fun createDistributor(data: OperationData.CreateDistributor): SyncResult {
        return try {
            val request = DistributorCreate(distributor_name = data.distributorName)
            val response = api.createDistributor(request)

            if (response.isSuccessful) {
                val created = response.body()!!
                SyncResult.Success(resourceId = created.id)
            } else {
                mapHttpErrorToSyncResult(response.code())
            }
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Network error", retryable = true)
        }
    }

    private suspend fun updateDistributor(data: OperationData.UpdateDistributor): SyncResult {
        return try {
            val request = DistributorCreate(distributor_name = data.distributorName)
            val response = api.updateDistributor(data.distributorId, request)

            if (response.isSuccessful) {
                SyncResult.Success()
            } else if (response.code() == 404) {
                SyncResult.Error("Resource not found", retryable = false)
            } else {
                mapHttpErrorToSyncResult(response.code())
            }
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Network error", retryable = true)
        }
    }

    private suspend fun deleteDistributor(data: OperationData.DeleteDistributor): SyncResult {
        return try {
            val response = api.deleteDistributor(data.distributorId)

            if (response.isSuccessful) {
                SyncResult.Success()
            } else if (response.code() == 404) {
                SyncResult.Success()
            } else {
                mapHttpErrorToSyncResult(response.code())
            }
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Network error", retryable = true)
        }
    }

    // ===== PRODUCT OPERATIONS =====

    private suspend fun createProduct(data: OperationData.CreateProduct): SyncResult {
        return try {
            val request = ProductCreate( name = data.name, distributor_id = data.distributorId, aliases = data.aliases )
            val response = api.createProduct(request)

            if (response.isSuccessful) {
                val created = response.body()!!
                SyncResult.Success(resourceId = created.id)
            } else {
                mapHttpErrorToSyncResult(response.code())
            }
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Network error", retryable = true)
        }
    }

    private suspend fun updateProduct(data: OperationData.UpdateProduct): SyncResult {
        return try {
            val request = ProductCreate( name = data.name, distributor_id = data.distributorId, aliases = data.aliases )
            val response = api.updateProduct(data.productId, request)

            if (response.isSuccessful) {
                SyncResult.Success()
            } else if (response.code() == 404) {
                SyncResult.Error("Resource not found", retryable = false)
            } else {
                mapHttpErrorToSyncResult(response.code())
            }
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Network error", retryable = true)
        }
    }

    private suspend fun deleteProduct(data: OperationData.DeleteProduct): SyncResult {
        return try {
            val response = api.deleteProduct(data.productId)

            if (response.isSuccessful) {
                SyncResult.Success()
            } else if (response.code() == 404) {
                SyncResult.Success()
            } else {
                mapHttpErrorToSyncResult(response.code())
            }
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Network error", retryable = true)
        }
    }

    // ===== SESSION ITEM OPERATIONS =====

    private suspend fun addSessionItem(data: OperationData.AddSessionItem): SyncResult {
        return try {
            val request = AddItemRequest( product_id = data.productId, quantity = data.quantity )
            val response = api.addSessionItem(data.sessionId, request)

            if (response.isSuccessful) {
                SyncResult.Success()
            } else {
                mapHttpErrorToSyncResult(response.code())
            }
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Network error", retryable = true)
        }
    }

    private suspend fun updateSessionItem(data: OperationData.UpdateSessionItem): SyncResult {
        return try {
            val request = UpdateItemRequest( quantity = data.quantity, version = data.version)
            val response = api.updateSessionItem(data.itemId, request)

            if (response.isSuccessful) {
                SyncResult.Success()
            } else if (response.code() == 409) {
                SyncResult.Conflict(
                    message = "Version conflict - item was modified by another user",
                    serverData = response.body()
                )
            } else if (response.code() == 404) {
                SyncResult.Error("Resource not found", retryable = false)
            } else {
                mapHttpErrorToSyncResult(response.code())
            }
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Network error", retryable = true)
        }
    }

    private suspend fun deleteSessionItem(data: OperationData.DeleteSessionItem): SyncResult {
        return try {
            val response = api.deleteSessionItem(data.itemId)

            if (response.isSuccessful) {
                SyncResult.Success()
            } else if (response.code() == 404) {
                SyncResult.Success()
            } else {
                mapHttpErrorToSyncResult(response.code())
            }
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Network error", retryable = true)
        }
    }

    private suspend fun clearSession(data: OperationData.ClearSession): SyncResult {
        return try {
            val response = api.clearSession(data.sessionId)

            if (response.isSuccessful) {
                SyncResult.Success()
            } else {
                mapHttpErrorToSyncResult(response.code())
            }
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Network error", retryable = true)
        }
    }

    // ===== CONFLICT RESOLUTION =====

    private suspend fun handleConflict( operation: PendingOperationEntity, conflict: SyncResult.Conflict ) {
        val strategy = ConflictStrategy.valueOf(operation.conflictStrategy)

        when (strategy) {
            ConflictStrategy.LATEST_WINS -> {
                Log.d(TAG, "Conflict: LATEST_WINS - will retry operation ${operation.id}")
                pendingOperationRepo.markAsFailed(operation.id, conflict.message)
            }
            ConflictStrategy.SERVER_WINS -> {
                Log.d(TAG, "Conflict: SERVER_WINS - discarding operation ${operation.id}")
                pendingOperationRepo.markAsCompleted(operation.id)
            }
            ConflictStrategy.MANUAL -> {
                Log.d(TAG, "Conflict: MANUAL - user must resolve operation ${operation.id}")
                pendingOperationRepo.markAsFailed(operation.id, "Conflict: ${conflict.message}")
            }
        }
    }

    // ===== UTILITY =====

    fun getLastSyncTime(): Long? { return prefs.getLong(PREF_LAST_SYNC, -1).takeIf { it != -1L } }
    suspend fun clearAllPendingOperations() { pendingOperationRepo.deleteAll() }
}