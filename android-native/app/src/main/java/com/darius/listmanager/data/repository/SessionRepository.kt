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
import com.darius.listmanager.network.AddItemRequest
import com.darius.listmanager.network.GlobalSessionItemDTO
import com.darius.listmanager.network.ListManagerApi
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.network.UpdateItemRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Room is the local source of truth (instant UI); item mutations are then
 * written through to the server so other devices see them (WS fan-out).
 *
 * - Sessions/items with POSITIVE ids are server-mirrored → write through.
 * - NEGATIVE ids are local offline fallbacks → local-only (no network, no queue).
 * - Offline (IOException) → enqueue a pending op for SyncService replay.
 * - Other failures (409/404/5xx/parse) → log and rely on WS/pull self-heal.
 *
 * Item mutations are serialized through [mutex] so two rapid taps can't both
 * capture the same optimistic-lock version (the second would silently 409).
 */
class SessionRepository(
    private val sessionDao: SessionDao,
    private val sessionItemDao: SessionItemDao,
    private val api: ListManagerApi = RetrofitClient.api,
    private val pendingOps: PendingOperationRepository? = null,
) {
    private val mutex = Mutex()

    fun getActiveSessionFlow(teamId: Long?): Flow<SessionEntity?> = sessionDao.getActiveSessionFlow(teamId)

    suspend fun getActiveSession(teamId: Long?): SessionEntity? = sessionDao.getActiveSession(teamId)

    suspend fun getOrCreateActiveSession(teamId: Long?): SessionEntity {
        return sessionDao.getOrCreateActiveSession(teamId)
    }

    suspend fun activateServerSession(serverId: Long, name: String, teamId: Long?) {
        sessionDao.activateServerSession(serverId, name, teamId)
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
        sessionItemDao.addOrIncrement(sessionId, productId, quantity)
        if (sessionId <= 0) return@withLock
        try {
            val response = api.addSessionItem(sessionId, AddItemRequest(product_id = productId, quantity = quantity))
            val dto = response.body()
            if (response.isSuccessful && dto != null) {
                upsertFromServer(dto)
            } else if (!response.isSuccessful) {
                Log.w(TAG, "addSessionItem HTTP ${response.code()}; relying on next pull")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            pendingOps?.queueOperation(
                OperationType.ADD_SESSION_ITEM, ResourceType.SESSION_ITEM, null,
                OperationData.AddSessionItem(sessionId, productId, quantity)
            )
        } catch (e: Exception) {
            Log.w(TAG, "addSessionItem failed", e)
        }
    }

    suspend fun setItemQuantity(sessionId: Long, productId: Long, quantity: Int) = mutex.withLock {
        // Capture pre-state BEFORE the local write: we need the server item id
        // and optimistic-lock version of the row being changed.
        val existing = sessionItemDao.getItem(sessionId, productId)
        sessionItemDao.setQuantity(sessionId, productId, quantity)
        if (sessionId <= 0) return@withLock
        try {
            when {
                quantity <= 0 && existing != null -> {
                    val response = api.deleteSessionItem(existing.id)
                    // 404 = already gone on the server: that's the state we want.
                    if (!response.isSuccessful && response.code() != 404) {
                        Log.w(TAG, "deleteSessionItem HTTP ${response.code()}; relying on next pull")
                    }
                }
                quantity > 0 && existing != null -> {
                    val response = api.updateSessionItem(
                        existing.id,
                        UpdateItemRequest(quantity = quantity, version = existing.version)
                    )
                    val dto = response.body()
                    if (response.isSuccessful && dto != null) {
                        upsertFromServer(dto)
                    } else if (!response.isSuccessful) {
                        // 409 stale version / 404 gone — WS event or next pull self-heals.
                        Log.w(TAG, "updateSessionItem HTTP ${response.code()}; relying on next pull")
                    }
                }
                existing == null && quantity > 0 -> {
                    val response = api.addSessionItem(
                        sessionId, AddItemRequest(product_id = productId, quantity = quantity)
                    )
                    val dto = response.body()
                    if (response.isSuccessful && dto != null) {
                        upsertFromServer(dto)
                    } else if (!response.isSuccessful) {
                        Log.w(TAG, "addSessionItem HTTP ${response.code()}; relying on next pull")
                    }
                }
                // existing == null && quantity <= 0 → nothing to do anywhere.
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            when {
                quantity <= 0 && existing != null -> pendingOps?.queueOperation(
                    OperationType.DELETE_SESSION_ITEM, ResourceType.SESSION_ITEM, existing.id,
                    OperationData.DeleteSessionItem(existing.id)
                )
                quantity > 0 && existing != null -> pendingOps?.queueOperation(
                    OperationType.UPDATE_SESSION_ITEM, ResourceType.SESSION_ITEM, existing.id,
                    OperationData.UpdateSessionItem(existing.id, quantity, existing.version)
                )
                existing == null && quantity > 0 -> pendingOps?.queueOperation(
                    OperationType.ADD_SESSION_ITEM, ResourceType.SESSION_ITEM, null,
                    OperationData.AddSessionItem(sessionId, productId, quantity)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "setItemQuantity failed", e)
        }
    }

    suspend fun deleteItem(itemId: Long) = mutex.withLock {
        // Item rows always have positive autogenerated ids — even in negative-id
        // (offline fallback) sessions — so the item id alone can't tell us
        // whether the row is server-mirrored. Check its SESSION id instead.
        val row = sessionItemDao.getById(itemId)
        sessionItemDao.deleteById(itemId)
        if (row == null || row.sessionId <= 0) return@withLock
        try {
            val response = api.deleteSessionItem(itemId)
            if (!response.isSuccessful && response.code() != 404) {
                Log.w(TAG, "deleteSessionItem HTTP ${response.code()}; relying on next pull")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            pendingOps?.queueOperation(
                OperationType.DELETE_SESSION_ITEM, ResourceType.SESSION_ITEM, itemId,
                OperationData.DeleteSessionItem(itemId)
            )
        } catch (e: Exception) {
            Log.w(TAG, "deleteSessionItem failed", e)
        }
    }

    suspend fun clearSession(sessionId: Long) = mutex.withLock {
        sessionItemDao.deleteAllInSession(sessionId)
        if (sessionId <= 0) return@withLock
        try {
            val response = api.clearSession(sessionId)
            if (!response.isSuccessful) {
                Log.w(TAG, "clearSession HTTP ${response.code()}; relying on next pull")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            pendingOps?.queueOperation(
                OperationType.CLEAR_SESSION, ResourceType.SESSION, sessionId,
                OperationData.ClearSession(sessionId)
            )
        } catch (e: Exception) {
            Log.w(TAG, "clearSession failed", e)
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
