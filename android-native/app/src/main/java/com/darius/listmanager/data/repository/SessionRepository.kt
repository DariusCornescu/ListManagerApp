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
import java.util.UUID

/**
 * Room is the local source of truth (instant UI); item mutations are then
 * written through to the server so other devices see them (WS fan-out).
 *
 * - Sessions/items with POSITIVE ids are server-mirrored → write through.
 * - NEGATIVE ids are local offline fallbacks whose writes wait behind CREATE_SESSION.
 * - Adds/updates persist an operation together with the local edit BEFORE sending.
 * - A successful response atomically updates Room and acknowledges the operation.
 * - Deletes/clears also persist before sending; ambiguous clears require review.
 * - Later edits and completion queue behind unresolved writes.
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

    suspend fun getOrCreateActiveSession(teamId: Long?): SessionEntity = mutex.withLock {
        var resolved: SessionEntity? = null
        transaction.run {
            val accountUserId = accountScope()
            var session = sessionDao.getActiveSession(accountUserId, teamId)
                ?: sessionDao.getOrCreateActiveSession(
                    accountUserId,
                    teamId,
                    UUID.randomUUID().toString(),
                )

            if (session.id < 0) {
                val clientUuid = session.clientUuid ?: UUID.randomUUID().toString()
                if (session.clientUuid == null) {
                    session = session.copy(clientUuid = clientUuid)
                    sessionDao.update(session)
                }
                val alreadyQueued = pendingOps.getUnfinished().any {
                    it.operationType == OperationType.CREATE_SESSION.name &&
                        it.resourceId == session.id
                }
                if (!alreadyQueued) {
                    val hadPreviousWork = pendingOps.getUnfinished().isNotEmpty()
                    val createId = pendingOps.queueOperation(
                        OperationType.CREATE_SESSION,
                        ResourceType.SESSION,
                        session.id,
                        OperationData.CreateSession(
                            localSessionId = session.id,
                            clientUuid = clientUuid,
                            name = session.name,
                            teamId = session.teamId,
                        ),
                    )
                    if (hadPreviousWork) pendingOps.markAsWaiting(createId)

                    // Version-8 and older offline sessions had local rows but
                    // no durable item intents. Seed them behind CREATE_SESSION.
                    sessionItemDao.getItems(session.id).forEach { item ->
                        val addId = pendingOps.queueOperation(
                            OperationType.ADD_SESSION_ITEM,
                            ResourceType.SESSION_ITEM,
                            item.id,
                            OperationData.AddSessionItem(
                                session.id,
                                item.productId,
                                item.quantity,
                            ),
                        )
                        pendingOps.markAsWaiting(addId)
                    }
                }
            }
            resolved = session
        }
        requireNotNull(resolved)
    }

    suspend fun activateServerSession(serverId: Long, name: String, teamId: Long?) {
        sessionDao.activateServerSession(serverId, name, accountScope(), teamId)
    }

    suspend fun getSessionById(id: Long): SessionEntity? = sessionDao.getById(id)

    suspend fun completeSession(sessionId: Long) = sessionDao.completeSession(sessionId)

    /** Complete locally and persist an idempotent completion intent first. */
    suspend fun completeSessionWriteThrough(sessionId: Long) = mutex.withLock {
        val waiting = pendingOps.getUnfinished().isNotEmpty() || sessionId <= 0
        var operationId = 0L
        transaction.run {
            sessionDao.completeSession(sessionId)
            operationId = pendingOps.queueOperation(
                OperationType.COMPLETE_SESSION,
                ResourceType.SESSION,
                sessionId,
                OperationData.CompleteSession(sessionId),
            )
            if (waiting) pendingOps.markAsWaiting(operationId)
        }
        if (waiting) return@withLock

        pendingOps.markAsInProgress(operationId)
        try {
            val response = api.completeSession(sessionId)
            if (response.isSuccessful) {
                pendingOps.deleteById(operationId)
            } else if (response.code() == 408 || response.code() == 429 || response.code() >= 500) {
                pendingOps.markAsFailed(operationId, "Server returned ${response.code()}")
            } else {
                pendingOps.holdForReview(operationId, "Server returned ${response.code()}; review required")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pendingOps.markAsFailed(operationId, e.message ?: "Network failure")
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
        val operation = OperationData.AddSessionItem(sessionId, productId, quantity).withOperationIdentity()
        persistAndSend(operation, forceWaiting = sessionId < 0) {
            sessionItemDao.addOrIncrement(sessionId, productId, quantity)
        }
    }

    suspend fun setItemQuantity(sessionId: Long, productId: Long, quantity: Int) = mutex.withLock {
        val existing = sessionItemDao.getItem(sessionId, productId)
        if (quantity > 0) {
            val operation = if (existing == null) {
                OperationData.AddSessionItem(sessionId, productId, quantity)
            } else {
                OperationData.UpdateSessionItem(existing.id, quantity, existing.version)
            }.withOperationIdentity()
            persistAndSend(operation, forceWaiting = sessionId < 0) {
                sessionItemDao.setQuantity(sessionId, productId, quantity)
            }
            return@withLock
        }
        if (existing == null) return@withLock
        persistRemoval(
            OperationData.DeleteSessionItem(existing.id),
            forceWaiting = sessionId < 0,
        ) {
            sessionItemDao.setQuantity(sessionId, productId, quantity)
        }
    }

    private suspend fun persistAndSend(
        operation: OperationData,
        forceWaiting: Boolean = false,
        localWrite: suspend () -> Unit,
    ) {
        var operationId = 0L
        val waiting = pendingOps.getUnfinished().isNotEmpty() || forceWaiting
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
        persistRemoval(
            OperationData.DeleteSessionItem(itemId),
            forceWaiting = row.sessionId < 0,
        ) { sessionItemDao.deleteById(itemId) }
    }

    suspend fun clearSession(sessionId: Long) = mutex.withLock {
        persistRemoval(
            OperationData.ClearSession(sessionId),
            forceWaiting = sessionId < 0,
        ) { sessionItemDao.deleteAllInSession(sessionId) }
    }

    private suspend fun persistRemoval(
        operation: OperationData,
        forceWaiting: Boolean = false,
        localWrite: suspend () -> Unit,
    ) {
        var id = 0L
        val waiting = pendingOps.getUnfinished().isNotEmpty() || forceWaiting
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
