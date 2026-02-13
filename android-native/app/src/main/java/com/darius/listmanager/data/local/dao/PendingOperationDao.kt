package com.darius.listmanager.data.local.dao

import androidx.room.*
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingOperationDao {
        
    @Query("SELECT * FROM pending_operations WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<PendingOperationEntity>
    
    @Query("SELECT * FROM pending_operations WHERE status = 'PENDING' ORDER BY createdAt ASC")
    fun getAllPendingFlow(): Flow<List<PendingOperationEntity>>
    
    @Query("SELECT * FROM pending_operations WHERE status = 'FAILED' ORDER BY updatedAt DESC")
    suspend fun getAllFailed(): List<PendingOperationEntity>
    
    @Query("SELECT * FROM pending_operations WHERE id = :id")
    suspend fun getById(id: Long): PendingOperationEntity?
    
    @Query("""SELECT * FROM pending_operations WHERE status = 'PENDING' AND scheduledFor <= :currentTime ORDER BY createdAt ASC """)
    suspend fun getReadyForSync(currentTime: Long = System.currentTimeMillis()): List<PendingOperationEntity>
    
    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'PENDING'")
    fun getPendingCountFlow(): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'FAILED'")
    fun getFailedCountFlow(): Flow<Int>
        
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
    
    @Query("DELETE FROM pending_operations")
    suspend fun deleteAll()
}