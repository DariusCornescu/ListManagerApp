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
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.network.*
import com.darius.listmanager.data.repository.SessionWriteCoordinator
import com.darius.listmanager.data.repository.AuthState
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import retrofit2.Response

data class SyncStatus( val isSyncing: Boolean = false, val pendingCount: Int = 0, val failedCount: Int = 0, val lastSyncTime: Long? = null, val lastError: String? = null, val currentOperation: String? = null )

class SyncService(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context),
    private val api: ListManagerApi = RetrofitClient.api,
    private val currentUserId: () -> Long? = { AuthState.userId.value },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val operationJson = Json { ignoreUnknownKeys = true }
    private val inventorySender by lazy { InventoryOperationSender(database, api) }

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
        private const val PREF_USER_ID = "authenticated_user_id"
        private const val PREF_REFRESH_TOKEN = "refresh_token"
        const val VERSION_CONFLICT_MESSAGE = "Version conflict - item was modified by another user"
    }

    // ===== AUTHENTICATION =====

    fun saveAuthToken(token: String) {
        prefs.edit().putString("auth_token", token).apply()
        RetrofitClient.setAuthToken(token)
    }

    fun getAuthToken(): String? {
        return prefs.getString("auth_token", null)
    }

    fun getRefreshToken(): String? = prefs.getString(PREF_REFRESH_TOKEN, null)

    /** A null token (a server that predates refresh) removes any stale one. */
    fun saveRefreshToken(token: String?) {
        prefs.edit().putString(PREF_REFRESH_TOKEN, token).apply()
    }

    /**
     * Stores a rotated pair. The old refresh token is now spent, so this is written
     * synchronously: losing the new one to process death would end the login.
     * Only called from the network layer's background thread.
     */
    fun saveRotatedTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString("auth_token", accessToken)
            .putString(PREF_REFRESH_TOKEN, refreshToken)
            .commit()
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
        prefs.edit().remove("auth_token").remove(PREF_REFRESH_TOKEN).remove(PREF_USER_ID).apply()
        RetrofitClient.setAuthToken(null)
    }

    fun isLoggedIn(): Boolean { return getAuthToken() != null }

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
            com.darius.listmanager.data.workspace.InventoryEvents.requestRefresh()
            return SyncResult.Success()
        } catch (e: CancellationException) {
            throw e // Durable IN_PROGRESS is recovered by the next lock holder.
        } catch (e: Exception) {
            _syncStatus.value = _syncStatus.value.copy(lastError = e.message)
            return SyncResult.Error(e.message ?: "Sync failed", retryable = true)
        }
    }

    private inline fun inventory(send: () -> InventorySend): SyncResult = guarded {
        when (val result = send()) {
            InventorySend.Done -> SyncResult.Success()
            is InventorySend.Failed -> when (result.code) {
                409 -> SyncResult.Conflict(message = VERSION_CONFLICT_MESSAGE)
                404 -> SyncResult.Error("Resource not found", retryable = false)
                else -> mapHttpErrorToSyncResult(result.code)
            }
        }
    }

    private fun safeToRecover(operation: PendingOperationEntity): Boolean {
        val data = runCatching {
            operationJson.decodeFromString<OperationData>(operation.operationData)
        }.getOrNull()
        return when (data) {
            is OperationData.CreateSession, is OperationData.CompleteSession -> true
            is OperationData.AddSessionItem -> data.idempotencyKey != null
            is OperationData.UpdateSessionItem -> data.idempotencyKey != null
            is OperationData.DeleteSessionItem, is OperationData.DeleteProduct,
            is OperationData.DeleteDistributor -> true
            // The server identifies all of these, so a repeat cannot apply twice.
            is OperationData.AddInventoryItem, is OperationData.UpdateInventoryItem,
            is OperationData.DeleteInventoryItem, is OperationData.ClearInventory -> true
            else -> false // Non-idempotent creation and clear can have ambiguous effects.
        }
    }

    // ===== PROCESS INDIVIDUAL OPERATION =====

    private suspend fun processOperation(queuedOperation: PendingOperationEntity): SyncResult {
        try {
            // Promotion can rewrite operations already captured by the outer
            // FIFO snapshot, so always reload the latest durable payload.
            val operation = pendingOperationRepo.prepareForReplay(queuedOperation)
            pendingOperationRepo.markAsInProgress(operation.id)

            return when (val data = operationJson.decodeFromString<OperationData>(operation.operationData)) {
                is OperationData.CreateDistributor -> created({ api.createDistributor(DistributorCreate(distributor_name = data.distributorName)) }) { it.id }
                is OperationData.UpdateDistributor -> updated { api.updateDistributor(data.distributorId, DistributorCreate(distributor_name = data.distributorName)) }
                is OperationData.DeleteDistributor -> deleted { api.deleteDistributor(data.distributorId) }

                is OperationData.CreateProduct -> created({ api.createProduct(ProductCreate(name = data.name, distributor_id = data.distributorId, aliases = data.aliases)) }) { it.id }
                is OperationData.UpdateProduct -> updated { api.updateProduct(data.productId, ProductCreate(name = data.name, distributor_id = data.distributorId, aliases = data.aliases)) }
                is OperationData.DeleteProduct -> deleted { api.deleteProduct(data.productId) }

                is OperationData.CreateSession -> createSession(data, operation)
                is OperationData.CompleteSession -> guarded { api.completeSession(data.sessionId).toResult() }

                is OperationData.AddSessionItem -> addSessionItem(data, operation)
                is OperationData.UpdateSessionItem -> updateSessionItem(data, operation)
                is OperationData.DeleteSessionItem -> deleted { api.deleteSessionItem(data.itemId) }
                is OperationData.ClearSession -> clearSession(data)

                is OperationData.AddInventoryItem -> inventory { inventorySender.add(data) }
                is OperationData.UpdateInventoryItem -> inventory { inventorySender.update(data) }
                is OperationData.DeleteInventoryItem -> inventory { inventorySender.delete(data) }
                is OperationData.ClearInventory -> inventory { inventorySender.clear(data) }
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

    // ===== REQUEST OUTCOMES =====

    /** A transport failure is always retryable; cancellation is never swallowed. */
    private inline fun guarded(request: () -> SyncResult): SyncResult = try {
        request()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SyncResult.Error(e.message ?: "Network error", retryable = true)
    }

    private fun Response<*>.toResult(): SyncResult =
        if (isSuccessful) SyncResult.Success() else mapHttpErrorToSyncResult(code())

    private inline fun <T : Any> created(request: () -> Response<T>, id: (T) -> Long): SyncResult = guarded {
        val response = request()
        if (response.isSuccessful) SyncResult.Success(resourceId = id(response.body()!!))
        else mapHttpErrorToSyncResult(response.code())
    }

    private inline fun updated(request: () -> Response<*>): SyncResult = guarded {
        val response = request()
        if (response.code() == 404) SyncResult.Error("Resource not found", retryable = false)
        else response.toResult()
    }

    /** Already gone is what a delete wanted. */
    private inline fun deleted(request: () -> Response<*>): SyncResult = guarded {
        val response = request()
        if (response.code() == 404) SyncResult.Success() else response.toResult()
    }

    private suspend fun acknowledged(
        operation: PendingOperationEntity,
        response: Response<GlobalSessionItemDTO>,
    ): SyncResult? {
        if (!response.isSuccessful) return null
        val dto = response.body() ?: return SyncResult.Error("Empty acknowledgement", retryable = true)
        SessionAcknowledgement(database).apply(operation, dto)
        return SyncResult.Success()
    }

    // ===== SESSION ITEM OPERATIONS =====

    private suspend fun createSession(
        data: OperationData.CreateSession,
        operation: PendingOperationEntity,
    ): SyncResult = guarded {
        val response = api.createSession(
            CreateSessionRequest(
                name = data.name,
                team_id = data.teamId,
                client_uuid = data.clientUuid,
            )
        )
        val dto = response.body()
        if (response.isSuccessful && dto != null) {
            val owner = operation.userId
                ?: return SyncResult.Error("Session owner is missing", retryable = false)
            SessionPromotion(database).apply(
                localSessionId = data.localSessionId,
                accountUserId = owner,
                dto = dto,
            )
            SyncResult.Success(resourceId = dto.id)
        } else if (response.isSuccessful) {
            SyncResult.Error("Empty acknowledgement", retryable = true)
        } else {
            mapHttpErrorToSyncResult(response.code())
        }
    }

    private suspend fun addSessionItem(data: OperationData.AddSessionItem, operation: PendingOperationEntity): SyncResult = guarded {
        val response = SessionItemOperationSender(api).send(data)
        acknowledged(operation, response) ?: mapHttpErrorToSyncResult(response.code())
    }

    private suspend fun updateSessionItem(data: OperationData.UpdateSessionItem, operation: PendingOperationEntity): SyncResult = guarded {
        val response = SessionItemOperationSender(api).send(data)
        acknowledged(operation, response) ?: when (response.code()) {
            409 -> SyncResult.Conflict(message = VERSION_CONFLICT_MESSAGE, serverData = response.body())
            404 -> SyncResult.Error("Resource not found", retryable = false)
            else -> mapHttpErrorToSyncResult(response.code())
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
}
