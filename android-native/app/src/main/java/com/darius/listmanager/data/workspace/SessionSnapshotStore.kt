package com.darius.listmanager.data.workspace

import androidx.room.withTransaction
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.SessionItemEntity
import com.darius.listmanager.data.repository.SessionWriteCoordinator
import com.darius.listmanager.network.GlobalSessionItemDTO
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

/** Pull under the write lock: a response cannot overwrite a newer local edit.
 * Conservatively defer session snapshots while ANY session intent is unresolved;
 * legacy delete/update payloads do not identify their parent session reliably.
 */
class SessionSnapshotStore(private val database: AppDatabase) {
    suspend fun refresh(
        sessionId: Long,
        fetch: suspend () -> Response<List<GlobalSessionItemDTO>>,
    ): Boolean = SessionWriteCoordinator.mutex.withLock {
        val blocked = database.pendingOperationDao().getUnfinished().any {
            it.resourceType == "SESSION_ITEM" || it.resourceType == "SESSION"
        }
        if (blocked) return@withLock false
        val response = fetch()
        val items = response.body()
        if (!response.isSuccessful || items == null) return@withLock false
        require(items.all { it.session_id == sessionId }) { "Snapshot session mismatch" }
        database.withTransaction {
            database.sessionItemDao().deleteAllInSession(sessionId)
            items.forEach { item ->
                database.sessionItemDao().insert(SessionItemEntity(
                    id = item.id, sessionId = sessionId, productId = item.product_id,
                    quantity = item.quantity, version = item.version,
                ))
            }
        }
        true
    }
}
