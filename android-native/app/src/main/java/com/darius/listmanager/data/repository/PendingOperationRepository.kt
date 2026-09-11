// app/src/main/java/com/darius/listmanager/data/repository/PendingOperationRepository.kt
package com.darius.listmanager.data.repository

import com.darius.listmanager.data.local.dao.PendingOperationDao
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PendingOperationRepository(private val dao: PendingOperationDao) {

    fun getAllPendingFlow(): Flow<List<PendingOperationEntity>> = dao.getAllPendingFlow()

    suspend fun getAllPending(): List<PendingOperationEntity> = dao.getAllPending()

    suspend fun getAllFailed(): List<PendingOperationEntity> = dao.getAllFailed()

    suspend fun getReadyForSync(): List<PendingOperationEntity> = dao.getReadyForSync()

    fun getPendingCountFlow(): Flow<Int> = dao.getPendingCountFlow()

    fun getFailedCountFlow(): Flow<Int> = dao.getFailedCountFlow()

    suspend fun queueOperation(
        operationType: OperationType,
        resourceType: ResourceType,
        resourceId: Long? = null,
        operationData: OperationData,
        conflictStrategy: ConflictStrategy = ConflictStrategy.LATEST_WINS,
        userId: Long? = null 
        ): Long {
        val json = Json { encodeDefaults = true }
        val dataJson = json.encodeToString(operationData.withOperationIdentity())

        val operation = PendingOperationEntity(
            operationType = operationType.name,
            resourceType = resourceType.name,
            resourceId = resourceId,
            operationData = dataJson,
            status = OperationStatus.PENDING.name,
            conflictStrategy = conflictStrategy.name,
            userId = userId
        )

        return dao.insert(operation)
    }

    /** Caller holds SessionWriteCoordinator.mutex; upgrade old JSON before sending. */
    suspend fun prepareForReplay(operation: PendingOperationEntity): PendingOperationEntity {
        val current = dao.getById(operation.id) ?: error("Operation no longer exists")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val data = json.decodeFromString<OperationData>(current.operationData)
        val identified = data.withOperationIdentity()
        if (identified == data) return current
        return current.copy(operationData = json.encodeToString(identified)).also { dao.update(it) }
    }

    suspend fun markAsInProgress(id: Long) = dao.markAsInProgress(id)

    suspend fun markAsCompleted(id: Long) = dao.markAsCompleted(id)

    suspend fun markAsFailed(id: Long, error: String) {

        val operation = dao.getById(id) ?: return
        val retryDelay = when (operation.retryCount) {
            0 -> 60_000L        // 1 minute
            1 -> 300_000L       // 5 minutes
            2 -> 900_000L       // 15 minutes
            3 -> 3_600_000L     // 1 hour
            else -> 3_600_000L  // 1 hour (max)
        }
        val scheduledFor = System.currentTimeMillis() + retryDelay
        
        dao.markAsFailed( id = id, error = error, scheduledFor = scheduledFor )
    }

    suspend fun deleteById(id: Long) = dao.deleteById(id)
    suspend fun deleteAllCompleted() = dao.deleteAllCompleted()
    suspend fun deleteOldCompleted(olderThanDays: Int = 7) {
        val olderThan = System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000L)
        dao.deleteOldCompleted(olderThan)
    }
    suspend fun deleteAll() = dao.deleteAll()
}