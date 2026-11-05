package com.darius.listmanager.sync

import androidx.room.withTransaction
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.data.local.entity.SessionItemEntity
import com.darius.listmanager.data.model.OperationData
import com.darius.listmanager.network.GlobalSessionItemDTO
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Called under the shared write lock, before allowing the next queued edit. */
internal class SessionAcknowledgement(private val database: AppDatabase) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun apply(operation: PendingOperationEntity, dto: GlobalSessionItemDTO) = database.withTransaction {
        val items = database.sessionItemDao()
        val pending = database.pendingOperationDao()
        val local = items.getItem(dto.session_id, dto.product_id)
        var laterEdit = false
        var laterRemoval = false
        val accountUserId = operation.userId ?: return@withTransaction
        for (next in pending.getUnfinishedForUser(accountUserId).filter { it.id != operation.id }) {
            val data = runCatching { json.decodeFromString<OperationData>(next.operationData) }.getOrNull()
            val matches = when (data) {
                is OperationData.UpdateSessionItem -> data.itemId == dto.id || data.itemId == local?.id || data.itemId == operation.resourceId
                is OperationData.DeleteSessionItem -> data.itemId == dto.id || data.itemId == local?.id || data.itemId == operation.resourceId
                is OperationData.AddSessionItem -> data.sessionId == dto.session_id && data.productId == dto.product_id
                is OperationData.ClearSession -> data.sessionId == dto.session_id
                else -> false
            }
            if (!matches) continue
            laterEdit = true
            laterRemoval = laterRemoval || data is OperationData.DeleteSessionItem || data is OperationData.ClearSession
            // Only untouched queued requests can be rebased. Never change a
            // payload whose idempotency key may already have reached the server.
            if (next.status == "WAITING") {
                val replacement = when (data) {
                    is OperationData.UpdateSessionItem -> data.copy(itemId = dto.id, version = dto.version)
                    is OperationData.DeleteSessionItem -> data.copy(itemId = dto.id)
                    else -> data
                }
                if (replacement != data && replacement != null) pending.update(next.copy(
                    resourceId = dto.id, operationData = json.encodeToString(replacement)))
            }
        }
        if (!laterRemoval) items.insert(SessionItemEntity(
            id = dto.id, sessionId = dto.session_id, productId = dto.product_id,
            quantity = if (laterEdit && local != null) local.quantity else dto.quantity,
            version = dto.version,
        ))
        pending.markAsCompleted(operation.id)
    }
}
