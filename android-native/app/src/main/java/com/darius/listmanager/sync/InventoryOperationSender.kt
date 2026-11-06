package com.darius.listmanager.sync

import androidx.room.withTransaction
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.model.OperationData
import com.darius.listmanager.network.InventoryClearRequest
import com.darius.listmanager.network.InventoryItemCreate
import com.darius.listmanager.network.InventoryItemDTO
import com.darius.listmanager.network.InventoryItemUpdate
import com.darius.listmanager.network.ListManagerApi
import retrofit2.Response

internal sealed interface InventorySend {
    data object Done : InventorySend
    data class Failed(val code: Int) : InventorySend
}

/**
 * Sends one queued inventory operation. The row's current values and version are read at
 * send time, so edits queued behind this one need no rewriting when it is acknowledged.
 */
internal class InventoryOperationSender(
    private val database: AppDatabase,
    private val api: ListManagerApi,
) {
    private val dao = database.inventoryItemDao()
    private val inventoryIds = mutableMapOf<Long?, Long>()

    private suspend fun inventoryId(teamId: Long?): Long? {
        inventoryIds[teamId]?.let { return it }
        val response = api.getInventory(teamId)
        val id = response.body()?.id
        if (response.isSuccessful && id != null) inventoryIds[teamId] = id
        return id
    }

    /** A failure to look the inventory up is reported like the request it blocked. */
    private suspend inline fun withInventory(teamId: Long?, send: (Long) -> InventorySend): InventorySend {
        val id = inventoryId(teamId) ?: return InventorySend.Failed(500)
        return send(id)
    }

    suspend fun add(data: OperationData.AddInventoryItem): InventorySend = withInventory(data.teamId) { id ->
        val response = api.addInventoryItem(
            id,
            InventoryItemCreate(
                item_uuid = data.itemUuid,
                name = data.name,
                product_id = data.productId,
                quantity = data.quantity,
                price_bani = data.priceBani,
            ),
        )
        acknowledge(data.itemUuid, response)
    }

    suspend fun update(data: OperationData.UpdateInventoryItem): InventorySend = withInventory(data.teamId) { id ->
        // Deleted locally since: the delete queued behind this has the last word.
        val row = dao.getByUuid(data.itemUuid) ?: return InventorySend.Done
        val response = api.updateInventoryItem(
            id,
            row.itemUuid,
            InventoryItemUpdate(
                name = row.name,
                product_id = row.productId,
                quantity = row.quantity,
                price_bani = row.priceBani,
                version = row.version,
                idempotency_key = data.idempotencyKey,
            ),
        )
        acknowledge(data.itemUuid, response)
    }

    suspend fun delete(data: OperationData.DeleteInventoryItem): InventorySend = withInventory(data.teamId) { id ->
        val response = api.deleteInventoryItem(id, data.itemUuid)
        if (response.isSuccessful) InventorySend.Done else InventorySend.Failed(response.code())
    }

    suspend fun clear(data: OperationData.ClearInventory): InventorySend = withInventory(data.teamId) { id ->
        val response = api.clearInventory(id, InventoryClearRequest(data.idempotencyKey))
        if (response.isSuccessful) InventorySend.Done else InventorySend.Failed(response.code())
    }

    /** Takes the server's version for the next edit; local values stay, as they may be newer. */
    private suspend fun acknowledge(uuid: String, response: Response<InventoryItemDTO>): InventorySend {
        if (!response.isSuccessful) return InventorySend.Failed(response.code())
        val dto = response.body() ?: return InventorySend.Failed(500)
        database.withTransaction {
            val row = dao.getByUuid(uuid) ?: return@withTransaction
            if (dto.deleted) dao.deleteById(row.id) else dao.update(row.copy(version = dto.version))
        }
        return InventorySend.Done
    }
}
