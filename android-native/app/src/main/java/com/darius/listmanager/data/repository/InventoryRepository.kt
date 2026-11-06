package com.darius.listmanager.data.repository

import androidx.room.withTransaction
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.InventoryItemEntity
import com.darius.listmanager.data.model.OperationData
import com.darius.listmanager.data.model.OperationType
import com.darius.listmanager.data.model.ResourceType
import com.darius.listmanager.network.InventoryItemDTO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.util.UUID

/**
 * Local-first shared inventory (EX-44). Every edit is saved to Room together with its
 * outbox entry, so it survives restarts and is visible at once; the sync worker sends it.
 *
 * Rows are addressed by the UUID chosen here, so nothing is rewritten when the server
 * acknowledges an add. Later operations can follow an unacknowledged add safely.
 */
class InventoryRepository(
    private val database: AppDatabase,
    private val currentUserId: () -> Long? = { AuthState.userId.value },
    private val pendingOps: PendingOperationRepository =
        PendingOperationRepository(database.pendingOperationDao(), currentUserId),
) {
    private val dao = database.inventoryItemDao()

    private fun account(): Long = currentUserId() ?: PendingOperationRepository.GUEST_ACCOUNT_ID

    fun getAllFlow(teamId: Long?): Flow<List<InventoryItemEntity>> = dao.getAllFlow(account(), teamId)

    suspend fun insert(
        teamId: Long?,
        name: String,
        productId: Long?,
        quantity: Double?,
        priceBani: Long?,
        createdAt: Long,
    ): Long = SessionWriteCoordinator.mutex.withLock {
        database.withTransaction {
            val row = InventoryItemEntity(
                name = name,
                productId = productId,
                quantity = quantity,
                priceBani = priceBani,
                createdAt = createdAt,
                itemUuid = UUID.randomUUID().toString(),
                accountUserId = account(),
                teamId = teamId,
            )
            val id = dao.insert(row)
            queueAdd(row)
            id
        }
    }

    private suspend fun queueAdd(row: InventoryItemEntity) {
        queue(
            OperationType.ADD_INVENTORY_ITEM,
            OperationData.AddInventoryItem(
                itemUuid = row.itemUuid,
                teamId = row.teamId,
                name = row.name,
                productId = row.productId,
                quantity = row.quantity,
                priceBani = row.priceBani,
            ),
        )
    }

    suspend fun update(item: InventoryItemEntity) = SessionWriteCoordinator.mutex.withLock {
        database.withTransaction {
            dao.update(item)
            queue(
                OperationType.UPDATE_INVENTORY_ITEM,
                OperationData.UpdateInventoryItem(
                    itemUuid = item.itemUuid,
                    teamId = item.teamId,
                    idempotencyKey = UUID.randomUUID().toString(),
                ),
            )
        }
    }

    suspend fun delete(item: InventoryItemEntity) = SessionWriteCoordinator.mutex.withLock {
        database.withTransaction {
            dao.deleteById(item.id)
            queue(
                OperationType.DELETE_INVENTORY_ITEM,
                OperationData.DeleteInventoryItem(itemUuid = item.itemUuid, teamId = item.teamId),
            )
        }
    }

    suspend fun clear(teamId: Long?) = SessionWriteCoordinator.mutex.withLock {
        database.withTransaction {
            dao.deleteAll(account(), teamId)
            queue(
                OperationType.CLEAR_INVENTORY,
                OperationData.ClearInventory(teamId = teamId, idempotencyKey = UUID.randomUUID().toString()),
            )
        }
    }

    /**
     * Rows from before sharing belong to whoever was using the phone, which the migration
     * could not know. The first signed-in account to open the inventory takes them, so the
     * list is not lost, and they upload like any other new rows.
     */
    suspend fun adoptLegacyRows() = SessionWriteCoordinator.mutex.withLock {
        val owner = currentUserId() ?: return@withLock
        database.withTransaction {
            dao.getUnclaimed().forEach { legacy ->
                val row = legacy.copy(accountUserId = owner, teamId = null)
                dao.update(row)
                queueAdd(row)
            }
        }
    }

    /**
     * Replaces the local rows with the server's, under the write lock so a response cannot
     * overwrite a newer edit. Deferred while any inventory change is still unsent; the
     * refresh after that change lands will bring both sides together.
     */
    suspend fun refresh(
        teamId: Long?,
        fetch: suspend () -> Response<com.darius.listmanager.network.InventoryDTO>,
    ): Boolean = SessionWriteCoordinator.mutex.withLock {
        val blocked = pendingOps.getUnfinished().any { it.resourceType == ResourceType.INVENTORY_ITEM.name }
        if (blocked) return@withLock false

        val response = fetch()
        val serverItems = response.body()?.items
        if (!response.isSuccessful || serverItems == null) return@withLock false

        database.withTransaction { applySnapshot(teamId, serverItems) }
        true
    }

    private suspend fun applySnapshot(teamId: Long?, serverItems: List<InventoryItemDTO>) {
        val local = dao.getAll(account(), teamId).associateBy { it.itemUuid }
        val onServer = serverItems.mapTo(mutableSetOf()) { it.item_uuid }
        local.values.filter { it.itemUuid !in onServer }.forEach { dao.deleteById(it.id) }
        serverItems.forEach { item ->
            val existing = local[item.item_uuid]
            if (existing == null) {
                dao.insert(
                    InventoryItemEntity(
                        name = item.name,
                        productId = item.product_id?.takeIf { database.productDao().getById(it) != null },
                        quantity = item.quantity,
                        priceBani = item.price_bani,
                        createdAt = System.currentTimeMillis(),
                        itemUuid = item.item_uuid,
                        version = item.version,
                        accountUserId = account(),
                        teamId = teamId,
                    )
                )
            } else {
                dao.update(
                    existing.copy(
                        name = item.name,
                        productId = item.product_id?.takeIf { database.productDao().getById(it) != null },
                        quantity = item.quantity,
                        priceBani = item.price_bani,
                        version = item.version,
                    )
                )
            }
        }
    }

    /** New entries wait their turn behind anything unsent, like session edits do. */
    private suspend fun queue(type: OperationType, data: OperationData) {
        val waiting = pendingOps.getUnfinished().isNotEmpty()
        val id = pendingOps.queueOperation(type, ResourceType.INVENTORY_ITEM, operationData = data)
        if (waiting) pendingOps.markAsWaiting(id)
    }
}
