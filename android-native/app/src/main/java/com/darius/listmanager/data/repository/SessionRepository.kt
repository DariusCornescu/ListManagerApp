package com.darius.listmanager.data.repository

import android.util.Log
import com.darius.listmanager.data.local.dao.SessionDao
import com.darius.listmanager.data.local.dao.SessionItemDao
import com.darius.listmanager.data.local.dao.SessionItemWithProduct
import com.darius.listmanager.data.local.entity.SessionEntity
import com.darius.listmanager.data.local.entity.SessionItemEntity
import com.darius.listmanager.data.model.OperationData
import com.darius.listmanager.data.model.OperationType
import com.darius.listmanager.data.model.ResourceType
import com.darius.listmanager.data.model.withOperationIdentity
import com.darius.listmanager.network.SessionItemOperationSender
import com.darius.listmanager.network.GlobalSessionItemDTO
import com.darius.listmanager.network.ListManagerApi
import com.darius.listmanager.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Room is the local source of truth (instant UI); item mutations are then
 * written through to the server so other devices see them (WS fan-out).
 *
 * - Sessions/items with POSITIVE ids are server-mirrored → write through.
 * - NEGATIVE ids are local offline fallbacks → local-only (no network, no queue).
 * - Adds/updates persist an operation together with the local edit BEFORE sending.
 * - A successful response atomically updates Room and acknowledges the operation.
 * - Deletes/clears also persist before sending; ambiguous clears require review.
 * - Later edits queue behind unresolved writes. Offline completion is a later milestone.
 *
 * Item mutations are serialized through [mutex] so two rapid taps can't both
 * capture the same optimistic-lock version (the second would silently 409).
 */
class SessionRepository(
    private val sessionDao: SessionDao,
    private val sessionItemDao: SessionItemDao,
    private val api: ListManagerApi = RetrofitClient.api,
    private val pendingOps: PendingOperationRepository,
    private val transaction: SessionTransaction,
    private val currentUserId: () -> Long? = { AuthState.userId.value },
) {
    private val mutex = SessionWriteCoordinator.mutex
    private val itemSender = SessionItemOperationSender(api)

    private fun accountScope(): Long =
        currentUserId() ?: SessionEntity.GUEST_ACCOUNT_ID

    fun getActiveSessionFlow(teamId: Long?): Flow<SessionEntity?> =
        sessionDao.getActiveSessionFlow(accountScope(), teamId)

    suspend fun getActiveSession(teamId: Long?): SessionEntity? =
        sessionDao.getActiveSession(accountScope(), teamId)

    suspend fun getOrCreateActiveSession(teamId: Long?): SessionEntity {
        return sessionDao.getOrCreateActiveSession(accountScope(), teamId)
    }

    suspend fun activateServerSession(serverId: Long, name: String, teamId: Long?) {
        sessionDao.activateServerSession(serverId, name, accountScope(), teamId)
    }

    suspend fun getSessionById(id: Long): SessionEntity? = sessionDao.getById(id)

    suspend fun completeSession(sessionId: Long) = sessionDao.completeSession(sessionId)

    /**
     * Complete the session locally AND on the server. No pending op on
     * IOException — SyncService has no COMPLETE_SESSION handler.
     */
    suspend fun completeSessionWriteThrough(sessionId: Long) = mutex.withLock {
        sessionDao.completeSession(sessionId)
        if (sessionId <= 0) return@withLock
        try {
            val response = api.completeSession(sessionId)
            if (!response.isSuccessful) Log.w(TAG, "completeSession HTTP ${response.code()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Offline completion stays local-only: the server session remains
            // active and the next resolve restores it (documented edge).
            Log.w(TAG, "completeSession offline; server session still active")
        } catch (e: Exception) {
            Log.w(TAG, "completeSession failed", e)
        }
    }

    // Session Items
    fun getSessionItemsFlow(sessionId: Long): Flow<List<SessionItemWithProduct>> {
        return sessionItemDao.getItemsWithProductFlow(sessionId)
    }

    suspend fun getSessionItems(sessionId: Long): List<SessionItemWithProduct> {
        return sessionItemDao.getItemsWithProduct(sessionId)
    }

    suspend fun addOrIncrementItem(sessionId: Long, productId: Long, quantity: Int = 1) = mutex.withLock {
        if (sessionId <= 0) {
            sessionItemDao.addOrIncrement(sessionId, productId, quantity)
            return@withLock
        }
        val operation = OperationData.AddSessionItem(sessionId, productId, quantity).withOperationIdentity()
        persistAndSend(operation) { sessionItemDao.addOrIncrement(sessionId, productId, quantity) }
    }

    suspend fun setItemQuantity(sessionId: Long, productId: Long, quantity: Int) = mutex.withLock {
        val existing = sessionItemDao.getItem(sessionId, productId)
        if (sessionId <= 0) {
            sessionItemDao.setQuantity(sessionId, productId, quantity)
            return@withLock
        }
        if (quantity > 0) {
            val operation = if (existing == null) {
                OperationData.AddSessionItem(sessionId, productId, quantity)
            } else {
                OperationData.UpdateSessionItem(existing.id, quantity, existing.version)
            }.withOperationIdentity()
            persistAndSend(operation) { sessionItemDao.setQuantity(sessionId, productId, quantity) }
            return@withLock
        }
        if (existing == null) return@withLock
        persistRemoval(OperationData.DeleteSessionItem(existing.id)) {
            sessionItemDao.setQuantity(sessionId, productId, quantity)
        }
    }

    private suspend fun persistAndSend(operation: OperationData, localWrite: suspend () -> Unit) {
        var operationId = 0L
        val waiting = pendingOps.getUnfinished().isNotEmpty()
        transaction.run {
            localWrite()
            operationId = when (operation) {
                is OperationData.AddSessionItem -> pendingOps.queueOperation(
                    OperationType.ADD_SESSION_ITEM, ResourceType.SESSION_ITEM,
                    sessionItemDao.getItem(operation.sessionId, operation.productId)?.id, operation
                )
                is OperationData.UpdateSessionItem -> pendingOps.queueOperation(
                    OperationType.UPDATE_SESSION_ITEM, ResourceType.SESSION_ITEM, operation.itemId, operation
                )
                else -> error("Not an item write")
            }
            if (waiting) pendingOps.markAsWaiting(operationId)
        }
        // WAITING explicitly proves this payload has never been sent.
        if (waiting) return
        // The transaction has committed before network I/O. A process death or
        // cancellation cannot leave a local edit without its replayable intent.
        try {
            val response = itemSender.send(operation)
            val dto = response.body()
            if (response.isSuccessful && dto != null) {
                transaction.run {
                    upsertFromServer(dto)
                    pendingOps.deleteById(operationId)
                }
            }
            // Non-success (including 5xx), or an empty body: retain the outbox
            // entry. Conflict/backoff policy is handled by the replay worker.
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // Includes a response lost AFTER server commit. Replay uses the
            // already-persisted key, so it does not apply the mutation twice.
        } catch (e: Exception) {
            Log.w(TAG, "Item write remains queued", e)
        }
    }

    suspend fun deleteItem(itemId: Long) = mutex.withLock {
        val row = sessionItemDao.getById(itemId) ?: return@withLock
        if (row.sessionId <= 0) sessionItemDao.deleteById(itemId)
        else persistRemoval(OperationData.DeleteSessionItem(itemId)) { sessionItemDao.deleteById(itemId) }
    }

    suspend fun clearSession(sessionId: Long) = mutex.withLock {
        if (sessionId <= 0) sessionItemDao.deleteAllInSession(sessionId)
        else persistRemoval(OperationData.ClearSession(sessionId)) { sessionItemDao.deleteAllInSession(sessionId) }
    }

    private suspend fun persistRemoval(operation: OperationData, localWrite: suspend () -> Unit) {
        var id = 0L
        val waiting = pendingOps.getUnfinished().isNotEmpty()
        transaction.run {
            localWrite()
            id = when (operation) {
                is OperationData.DeleteSessionItem -> pendingOps.queueOperation(
                    OperationType.DELETE_SESSION_ITEM, ResourceType.SESSION_ITEM, operation.itemId, operation)
                is OperationData.ClearSession -> pendingOps.queueOperation(
                    OperationType.CLEAR_SESSION, ResourceType.SESSION, operation.sessionId, operation)
                else -> error("Not a removal")
            }
            if (waiting) pendingOps.markAsWaiting(id)
        }
        if (waiting) return
        // Unlike item DELETE, repeating CLEAR after a lost reply could delete
        // newly added remote items. Leave ambiguous clears for review.
        pendingOps.markAsInProgress(id)
        try {
            val response = when (operation) {
                is OperationData.DeleteSessionItem -> api.deleteSessionItem(operation.itemId)
                is OperationData.ClearSession -> api.clearSession(operation.sessionId)
                else -> error("Not a removal")
            }
            if (response.isSuccessful || (operation is OperationData.DeleteSessionItem && response.code() == 404)) {
                pendingOps.deleteById(id)
            } else if ((operation is OperationData.ClearSession && (response.code() == 408 || response.code() >= 500)) ||
                (response.code() in 400..499 && response.code() !in listOf(401, 408, 429))) {
                pendingOps.holdForReview(id, "Server returned ${response.code()}; review required")
            } else {
                pendingOps.markAsFailed(id, "Server returned ${response.code()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (operation is OperationData.ClearSession) pendingOps.holdForReview(id, "Clear outcome unknown; review server state")
            else pendingOps.markAsFailed(id, e.message ?: "Network failure")
        }
    }

    /**
     * Mirror the server's absolute item state into Room. @Insert(REPLACE) plus
     * the unique (sessionId, productId) index re-keys a locally-created row to
     * the server id in one shot.
     */
    private suspend fun upsertFromServer(dto: GlobalSessionItemDTO) {
        sessionItemDao.insert(
            SessionItemEntity(
                id = dto.id,
                sessionId = dto.session_id,
                productId = dto.product_id,
                quantity = dto.quantity,
                version = dto.version,
            )
        )
    }

    private companion object { const val TAG = "SessionRepository" }
}
