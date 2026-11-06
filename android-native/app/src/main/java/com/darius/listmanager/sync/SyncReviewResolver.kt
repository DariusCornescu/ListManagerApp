package com.darius.listmanager.sync

import androidx.room.withTransaction
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.data.model.OperationData
import com.darius.listmanager.data.model.OperationType
import com.darius.listmanager.data.repository.SessionWriteCoordinator
import com.darius.listmanager.data.workspace.InventoryEvents
import com.darius.listmanager.data.workspace.SessionEvents
import com.darius.listmanager.network.ListManagerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ReviewOutcome {
    /** The operation reached the server, or was deliberately dropped. */
    RESOLVED,

    /** Released back to the queue; it will retry on its own. */
    QUEUED,

    /** The server could not be asked. The operation is still held, and the user can try later. */
    NO_CONNECTION,

    /** Someone else removed the item, so there is nothing left to overwrite. */
    ITEM_GONE,

    /** Another writer kept winning, even after repeated rebases. Still held, nothing lost. */
    STILL_CONFLICTED,

    /** The server refused it for another reason. Still held, nothing lost. */
    NEEDS_REVIEW,
}

val PendingOperationEntity.isHeldForReview: Boolean
    get() = scheduledFor == Long.MAX_VALUE

val PendingOperationEntity.isVersionConflict: Boolean
    get() = isHeldForReview &&
        (operationType == OperationType.UPDATE_SESSION_ITEM.name ||
            operationType == OperationType.UPDATE_INVENTORY_ITEM.name) &&
        lastError == SyncService.VERSION_CONFLICT_MESSAGE

/**
 * The user's way out of a held operation. A hold blocks the whole FIFO queue and every
 * session refresh, so it must always have an explicit resolution.
 *
 * Version conflicts are never settled silently: absolute quantities cannot be merged, and
 * guessing could erase a teammate's addition. The user chooses, and the server then decides
 * only whether that choice is still based on the latest version.
 */
class SyncReviewResolver(
    private val database: AppDatabase,
    private val api: ListManagerApi,
    private val replay: suspend () -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Re-sends the local quantity on top of the server's current version. Each 409 means
     * another writer got in first, so refetch and try again, a bounded number of times.
     */
    suspend fun keepMine(operationId: Long): ReviewOutcome {
        repeat(MAX_REBASE_ATTEMPTS) {
            when (val rebase = rebaseOntoServer(operationId)) {
                Rebase.Done -> Unit
                is Rebase.Stop -> return rebase.outcome
            }
            replay()
            val operation = database.pendingOperationDao().getById(operationId)
            when {
                operation == null || operation.status == "COMPLETED" -> return ReviewOutcome.RESOLVED
                !operation.isHeldForReview -> return ReviewOutcome.QUEUED
                !operation.isVersionConflict -> return ReviewOutcome.NEEDS_REVIEW
            }
        }
        return ReviewOutcome.STILL_CONFLICTED
    }

    /** Releases any held operation back to the queue, unchanged. */
    suspend fun retry(operationId: Long): ReviewOutcome {
        SessionWriteCoordinator.mutex.withLock {
            val operation = database.pendingOperationDao().getById(operationId)
                ?: return ReviewOutcome.RESOLVED
            database.pendingOperationDao().update(operation.released())
        }
        replay()
        val operation = database.pendingOperationDao().getById(operationId)
        return when {
            operation == null || operation.status == "COMPLETED" -> ReviewOutcome.RESOLVED
            operation.isHeldForReview -> ReviewOutcome.NEEDS_REVIEW
            else -> ReviewOutcome.QUEUED
        }
    }

    /** Drops the local edit plus the queued edits built on it; the server's data wins. */
    suspend fun discard(operationId: Long): ReviewOutcome {
        SessionWriteCoordinator.mutex.withLock {
            database.withTransaction {
                val pending = database.pendingOperationDao()
                val held = pending.getById(operationId) ?: return@withTransaction
                val heldData = decode(held)
                val owner = held.userId
                if (owner != null) {
                    pending.getUnfinishedForUser(owner)
                        .filter { it.id > held.id && it.status == "WAITING" && dependsOn(heldData, held, it) }
                        .forEach { pending.deleteById(it.id) }
                }
                pending.deleteById(held.id)
            }
        }
        // Nothing may be left unresolved for the snapshot store to run; replay what remains.
        replay()
        SessionEvents.requestRefresh()
        InventoryEvents.requestRefresh()
        return ReviewOutcome.RESOLVED
    }

    private sealed interface Rebase {
        data object Done : Rebase
        data class Stop(val outcome: ReviewOutcome) : Rebase
    }

    private suspend fun rebaseOntoServer(operationId: Long): Rebase =
        SessionWriteCoordinator.mutex.withLock {
            val pending = database.pendingOperationDao()
            val operation = pending.getById(operationId)
                ?: return@withLock Rebase.Stop(ReviewOutcome.RESOLVED)
            val decoded = decode(operation)
            if (decoded is OperationData.UpdateInventoryItem) {
                return@withLock rebaseInventoryRow(operation, decoded)
            }
            val data = decoded as? OperationData.UpdateSessionItem
                ?: return@withLock Rebase.Stop(ReviewOutcome.NEEDS_REVIEW)
            val local = database.sessionItemDao().getById(data.itemId)
                ?: return@withLock Rebase.Stop(ReviewOutcome.ITEM_GONE)

            val response = try {
                api.getSessionItems(local.sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withLock Rebase.Stop(ReviewOutcome.NO_CONNECTION)
            }
            val serverItems = response.body()
            if (!response.isSuccessful || serverItems == null) {
                return@withLock Rebase.Stop(ReviewOutcome.NO_CONNECTION)
            }
            val server = serverItems.firstOrNull { it.id == data.itemId }
                ?: return@withLock Rebase.Stop(ReviewOutcome.ITEM_GONE)

            // A 409 proves the server never recorded this idempotency key (only successes
            // reach the ledger), so changing the version cannot apply the edit twice.
            database.withTransaction {
                pending.update(
                    operation.released().copy(
                        operationData = json.encodeToString<OperationData>(data.copy(version = server.version)),
                    )
                )
                database.sessionItemDao().insert(local.copy(version = server.version))
            }
            Rebase.Done
        }

    /** The queued edit reads the row at send time, so only the row's version moves. */
    private suspend fun rebaseInventoryRow(
        operation: PendingOperationEntity,
        data: OperationData.UpdateInventoryItem,
    ): Rebase {
        val rows = database.inventoryItemDao()
        val local = rows.getByUuid(data.itemUuid) ?: return Rebase.Stop(ReviewOutcome.ITEM_GONE)
        val response = try {
            api.getInventory(data.teamId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Rebase.Stop(ReviewOutcome.NO_CONNECTION)
        }
        val serverItems = response.body()?.items
        if (!response.isSuccessful || serverItems == null) return Rebase.Stop(ReviewOutcome.NO_CONNECTION)
        val server = serverItems.firstOrNull { it.item_uuid == data.itemUuid }
            ?: return Rebase.Stop(ReviewOutcome.ITEM_GONE)

        database.withTransaction {
            rows.update(local.copy(version = server.version))
            database.pendingOperationDao().update(operation.released())
        }
        return Rebase.Done
    }

    private fun PendingOperationEntity.released() = copy(
        status = "PENDING",
        scheduledFor = 0L,
        lastError = null,
    )

    private fun decode(operation: PendingOperationEntity): OperationData? =
        runCatching { json.decodeFromString<OperationData>(operation.operationData) }.getOrNull()

    private suspend fun dependsOn(
        heldData: OperationData?,
        held: PendingOperationEntity,
        next: PendingOperationEntity,
    ): Boolean {
        val nextData = decode(next)
        return when (heldData) {
            is OperationData.CreateSession -> when (nextData) {
                is OperationData.AddSessionItem -> nextData.sessionId == heldData.localSessionId
                is OperationData.ClearSession -> nextData.sessionId == heldData.localSessionId
                is OperationData.CompleteSession -> nextData.sessionId == heldData.localSessionId
                is OperationData.UpdateSessionItem -> inSession(nextData.itemId, heldData.localSessionId)
                is OperationData.DeleteSessionItem -> inSession(nextData.itemId, heldData.localSessionId)
                else -> false
            }
            // Stale without the dropped edit. A later delete or add still stands alone.
            is OperationData.UpdateSessionItem ->
                nextData is OperationData.UpdateSessionItem && nextData.itemId == heldData.itemId
            is OperationData.AddSessionItem -> when (nextData) {
                is OperationData.UpdateSessionItem -> nextData.itemId == held.resourceId
                is OperationData.DeleteSessionItem -> nextData.itemId == held.resourceId
                else -> false
            }
            // Edits read the row, so they die with the add that created it.
            is OperationData.AddInventoryItem -> when (nextData) {
                is OperationData.UpdateInventoryItem -> nextData.itemUuid == heldData.itemUuid
                is OperationData.DeleteInventoryItem -> nextData.itemUuid == heldData.itemUuid
                else -> false
            }
            is OperationData.UpdateInventoryItem ->
                nextData is OperationData.UpdateInventoryItem && nextData.itemUuid == heldData.itemUuid
            else -> false
        }
    }

    private suspend fun inSession(itemId: Long, sessionId: Long): Boolean =
        database.sessionItemDao().getById(itemId)?.sessionId == sessionId

    private companion object {
        const val MAX_REBASE_ATTEMPTS = 5
    }
}
