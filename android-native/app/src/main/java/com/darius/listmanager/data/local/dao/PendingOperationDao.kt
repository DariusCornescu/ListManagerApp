package com.darius.listmanager.data.local.dao

import androidx.room.*
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingOperationDao {
    @Query("SELECT * FROM pending_operations WHERE status != 'COMPLETED' ORDER BY id ASC")
    suspend fun getUnfinished(): List<PendingOperationEntity>

    @Query("SELECT * FROM pending_operations WHERE userId = :userId AND status != 'COMPLETED' ORDER BY id ASC")
    suspend fun getUnfinishedForUser(userId: Long): List<PendingOperationEntity>

        
    @Query("SELECT * FROM pending_operations WHERE userId = :userId AND status != 'COMPLETED' ORDER BY id ASC")
    fun getUnfinishedFlowForUser(userId: Long): Flow<List<PendingOperationEntity>>

    /** Ends the wait of everything backing off; a hold for review stays a hold. */
    @Query(
        """UPDATE pending_operations SET scheduledFor = 0
           WHERE userId = :userId AND status != 'COMPLETED' AND scheduledFor != :heldForReview"""
    )
    suspend fun clearBackoffForUser(userId: Long, heldForReview: Long = Long.MAX_VALUE)

    @Query("SELECT * FROM pending_operations WHERE status IN ('PENDING', 'WAITING') ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<PendingOperationEntity>

    @Query("SELECT * FROM pending_operations WHERE userId = :userId AND status IN ('PENDING', 'WAITING') ORDER BY createdAt ASC")
    suspend fun getAllPendingForUser(userId: Long): List<PendingOperationEntity>
    
    @Query("SELECT * FROM pending_operations WHERE status IN ('PENDING', 'WAITING') ORDER BY createdAt ASC")
    fun getAllPendingFlow(): Flow<List<PendingOperationEntity>>

    @Query("SELECT * FROM pending_operations WHERE userId = :userId AND status IN ('PENDING', 'WAITING') ORDER BY createdAt ASC")
    fun getAllPendingFlowForUser(userId: Long): Flow<List<PendingOperationEntity>>
    
    @Query("SELECT * FROM pending_operations WHERE status = 'FAILED' ORDER BY updatedAt DESC")
    suspend fun getAllFailed(): List<PendingOperationEntity>

    @Query("SELECT * FROM pending_operations WHERE userId = :userId AND status = 'FAILED' ORDER BY updatedAt DESC")
    suspend fun getAllFailedForUser(userId: Long): List<PendingOperationEntity>
    
    @Query("SELECT * FROM pending_operations WHERE id = :id")
    suspend fun getById(id: Long): PendingOperationEntity?
    
    @Query("""SELECT * FROM pending_operations WHERE status IN ('PENDING', 'WAITING', 'FAILED', 'IN_PROGRESS') AND scheduledFor <= :currentTime ORDER BY id ASC """)
    suspend fun getReadyForSync(currentTime: Long = System.currentTimeMillis()): List<PendingOperationEntity>

    @Query("""SELECT * FROM pending_operations WHERE userId = :userId AND status IN ('PENDING', 'WAITING', 'FAILED', 'IN_PROGRESS') AND scheduledFor <= :currentTime ORDER BY id ASC""")
    suspend fun getReadyForSyncForUser(userId: Long, currentTime: Long = System.currentTimeMillis()): List<PendingOperationEntity>
    
    @Query("SELECT COUNT(*) FROM pending_operations WHERE status IN ('PENDING', 'WAITING')")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_operations WHERE userId = :userId AND status IN ('PENDING', 'WAITING')")
    fun getPendingCountFlowForUser(userId: Long): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'FAILED'")
    fun getFailedCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_operations WHERE userId = :userId AND status = 'FAILED'")
    fun getFailedCountFlowForUser(userId: Long): Flow<Int>
        
    @Insert
    suspend fun insert(operation: PendingOperationEntity): Long
    
    @Insert
    suspend fun insertAll(operations: List<PendingOperationEntity>)
        
    @Update
    suspend fun update(operation: PendingOperationEntity)
    
    @Query(""" UPDATE pending_operations SET status = :status, lastError = :error, retryCount = retryCount + 1, 
    updatedAt = :updatedAt, scheduledFor = :scheduledFor WHERE id = :id """)
    suspend fun markAsFailed( id: Long, status: String = "FAILED", error: String?, updatedAt: Long = System.currentTimeMillis(), scheduledFor: Long = System.currentTimeMillis() + 60000 )
    
    @Query(""" UPDATE pending_operations SET status = 'IN_PROGRESS', updatedAt = :updatedAt WHERE id = :id""")
    suspend fun markAsInProgress( id: Long, updatedAt: Long = System.currentTimeMillis() )
    
    @Query(""" UPDATE pending_operations SET status = 'COMPLETED', updatedAt = :updatedAt WHERE id = :id """)
    suspend fun markAsCompleted( id: Long, updatedAt: Long = System.currentTimeMillis() )
    
    @Delete
    suspend fun delete(operation: PendingOperationEntity)
    
    @Query("DELETE FROM pending_operations WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    @Query("DELETE FROM pending_operations WHERE status = 'COMPLETED'")
    suspend fun deleteAllCompleted()
    
    @Query("DELETE FROM pending_operations WHERE status = 'COMPLETED' AND updatedAt < :olderThan")
    suspend fun deleteOldCompleted(olderThan: Long)

    @Query("DELETE FROM pending_operations WHERE userId = :userId AND status = 'COMPLETED' AND updatedAt < :olderThan")
    suspend fun deleteOldCompletedForUser(userId: Long, olderThan: Long)
    
    @Query("DELETE FROM pending_operations")
    suspend fun deleteAll()
}
