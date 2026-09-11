package com.darius.listmanager.network

import com.darius.listmanager.data.model.OperationData
import retrofit2.Response

/** Both foreground and replay send the identity saved in the outbox. */
class SessionItemOperationSender(private val api: ListManagerApi) {
    suspend fun send(data: OperationData): Response<GlobalSessionItemDTO> = when (data) {
        is OperationData.AddSessionItem -> api.addSessionItem(
            data.sessionId,
            AddItemRequest(data.productId, data.quantity, requireNotNull(data.idempotencyKey)),
        )
        is OperationData.UpdateSessionItem -> api.updateSessionItem(
            data.itemId,
            UpdateItemRequest(data.quantity, data.version, requireNotNull(data.idempotencyKey)),
        )
        else -> error("Not an add/update session operation")
    }
}
