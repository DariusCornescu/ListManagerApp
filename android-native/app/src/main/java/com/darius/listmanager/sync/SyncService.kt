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
import com.darius.listmanager.data.repository.SessionWriteCoordinator
import com.darius.listmanager.data.repository.AuthState
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class SyncStatus( val isSyncing: Boolean = false, val pendingCount: Int = 0, val failedCount: Int = 0, val lastSyncTime: Long? = null, val lastError: String? = null, val currentOperation: String? = null )

class SyncService(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context),
    private val api: ListManagerApi = RetrofitClient.api,
    private val currentUserId: () -> Long? = { AuthState.userId.value },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val pendingOperationRepo = PendingOperationRepository(
        database.pendingOperationDao(),
        currentUserId,
    )

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
        private const val PREF_USER_ID = "authenticated_user_id"
    }

    // ===== AUTHENTICATION =====

    fun saveAuthToken(token: String) {
        prefs.edit().putString("auth_token", token).apply()
        RetrofitClient.setAuthToken(token)
    }

    fun getAuthToken(): String? {
        return prefs.getString("auth_token", null)
    }

    fun saveAuthenticatedUserId(userId: Long) {
        prefs.edit().putLong(PREF_USER_ID, userId).apply()
    }

    fun getAuthenticatedUserId(): Long? =
        if (prefs.contains(PREF_USER_ID)) prefs.getLong(PREF_USER_ID, 0L) else null

    fun clearAuthenticatedUserId() {
        prefs.edit().remove(PREF_USER_ID).apply()
    }

    fun clearAuthToken() {
        prefs.edit().remove("auth_token").remove(PREF_USER_ID).apply()
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
        SessionWriteCoordinator.mutex.withLock {
            try { syncPendingOperationsLocked() } finally {
                _syncStatus.value = _syncStatus.value.copy(isSyncing = false, currentOperation = null)
            }
        }
    }

    private suspend fun syncPendingOperationsLocked(): SyncResult {
        _syncStatus.value = _syncStatus.value.copy(isSyncing = true, lastError = null)
        if (currentUserId() == null) {
            return SyncResult.Error("Sign in to resume synchronization", retryable = true)
        }
        try {
            // Global FIFO is conservative: do not overtake a delayed or rejected edit.
            // The shared process lock excludes other workers and foreground writes.
            for (queued in pendingOperationRepo.getUnfinished()) {
                val operation = database.pendingOperationDao().getById(queued.id) ?: continue
                if (operation.status == "COMPLETED") continue
                if (operation.scheduledFor == Long.MAX_VALUE) {
                    return SyncResult.Error(operation.lastError ?: "Needs review", retryable = false)
                }
                if (operation.status != "IN_PROGRESS" && operation.scheduledFor > now()) {
                    return SyncResult.Error("Waiting for retry", retryable = true)
                }
                if (operation.status == "IN_PROGRESS" && !safeToRecover(operation)) {
                    pendingOperationRepo.holdForReview(operation.id, "Interrupted request: check server before retrying")
                    return SyncResult.Error("Interrupted request needs review", retryable = false)
                }
                _syncStatus.value = _syncStatus.value.copy(currentOperation = "${operation.operationType} (${operation.id})")
                when (val result = processOperation(operation)) {
                    is SyncResult.Success -> pendingOperationRepo.markAsCompleted(operation.id)
                    is SyncResult.Error -> {
                        if (result.retryable) pendingOperationRepo.markAsFailed(operation.id, result.message)
                        else pendingOperationRepo.holdForReview(operation.id, result.message)
                        _syncStatus.value = _syncStatus.value.copy(lastError = result.message)
                        return result
                    }
                    is SyncResult.Conflict -> {
                        // Never retry the same stale version forever or discard local intent.
                        pendingOperationRepo.holdForReview(operation.id, result.message)
                        _syncStatus.value = _syncStatus.value.copy(lastError = result.message)
                        return SyncResult.Error(result.message, retryable = false)
                    }
                }
            }
            pendingOperationRepo.deleteOldCompleted()
            _syncStatus.value = _syncStatus.value.copy(lastSyncTime = now())
            com.darius.listmanager.data.workspace.SessionEvents.requestRefresh()
            return SyncResult.Success()
        } catch (e: CancellationException) {
            throw e // Durable IN_PROGRESS is recovered by the next lock holder.
        } catch (e: Exception) {
            _syncStatus.value = _syncStatus.value.copy(lastError = e.message)
            return SyncResult.Error(e.message ?: "Sync failed", retryable = true)
        }
    }

    private fun safeToRecover(operation: PendingOperationEntity): Boolean {
        val data = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<OperationData>(operation.operationData)
        }.getOrNull()
        return when (data) {
            is OperationData.AddSessionItem -> data.idempotencyKey != null
            is OperationData.UpdateSessionItem -> data.idempotencyKey != null
            is OperationData.DeleteSessionItem, is OperationData.DeleteProduct,
            is OperationData.DeleteDistributor -> true
            else -> false // Creation and clear can have ambiguous effects after interruption.
        }
    }

    // ===== PROCESS INDIVIDUAL OPERATION =====

    private suspend fun processOperation(queuedOperation: PendingOperationEntity): SyncResult {
        try {
            val operation = if (queuedOperation.operationType in setOf(
                    OperationType.ADD_SESSION_ITEM.name, OperationType.UPDATE_SESSION_ITEM.name
                )) pendingOperationRepo.prepareForReplay(queuedOperation) else queuedOperation
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
                    addSessionItem(data, operation)
                }
                OperationType.UPDATE_SESSION_ITEM -> {
                    val data = json.decodeFromString<OperationData.UpdateSessionItem>(operation.operationData)
                    updateSessionItem(data, operation)
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

        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            return SyncResult.Error("Invalid saved operation; review required", retryable = false)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing operation ${queuedOperation.id}", e)
            return SyncResult.Error(e.message ?: "Unknown error", retryable = true)
        }
    }

    private fun mapHttpErrorToSyncResult(code: Int): SyncResult = when {
        code == 409 -> SyncResult.Conflict("Server data changed; review the local edit")
        code == 401 -> SyncResult.Error("Sign in again to resume synchronization", retryable = true)
        code == 408 || code == 429 || code >= 500 -> SyncResult.Error("Temporary server failure: $code", retryable = true)
        else -> SyncResult.Error("Server rejected this edit ($code); review required", retryable = false)
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Network error", retryable = true)
        }
    }

    // ===== SESSION ITEM OPERATIONS =====

    private suspend fun addSessionItem(data: OperationData.AddSessionItem, operation: PendingOperationEntity): SyncResult {
        return try {
            val response = SessionItemOperationSender(api).send(data)

            if (response.isSuccessful && response.body() == null) {
                SyncResult.Error("Empty acknowledgement", retryable = true)
            } else if (response.isSuccessful) {
                SessionAcknowledgement(database).apply(operation, response.body()!!)
                SyncResult.Success()
            } else {
                mapHttpErrorToSyncResult(response.code())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Network error", retryable = true)
        }
    }

    private suspend fun updateSessionItem(data: OperationData.UpdateSessionItem, operation: PendingOperationEntity): SyncResult {
        return try {
            val response = SessionItemOperationSender(api).send(data)

            if (response.isSuccessful && response.body() == null) {
                SyncResult.Error("Empty acknowledgement", retryable = true)
            } else if (response.isSuccessful) {
                SessionAcknowledgement(database).apply(operation, response.body()!!)
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Network error", retryable = true)
        }
    }

    private suspend fun clearSession(data: OperationData.ClearSession): SyncResult {
        return try {
            val response = api.clearSession(data.sessionId)

            if (response.isSuccessful) {
                SyncResult.Success()
            } else if (response.code() == 408 || response.code() >= 500) {
                SyncResult.Error("Clear outcome unknown; review server state", retryable = false)
            } else {
                mapHttpErrorToSyncResult(response.code())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Error("Clear outcome unknown; review server state", retryable = false)
        }
    }

    // ===== UTILITY =====

    fun getLastSyncTime(): Long? { return prefs.getLong(PREF_LAST_SYNC, -1).takeIf { it != -1L } }
    suspend fun clearAllPendingOperations() { pendingOperationRepo.deleteAll() }
}
