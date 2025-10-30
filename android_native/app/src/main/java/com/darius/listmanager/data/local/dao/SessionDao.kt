package com.darius.listmanager.data.local.dao

import androidx.room.*
import com.darius.listmanager.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE isActive = 1 LIMIT 1")
    fun getActiveSessionFlow(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("UPDATE sessions SET isActive = 0")
    suspend fun deactivateAll()

    @Transaction
    suspend fun getOrCreateActiveSession(): SessionEntity {
        val active = getActiveSession()
        return if (active != null) {
            active
        } else {
            val newId = insert(SessionEntity(name = "Current Session", isActive = true))
            getById(newId)!!
        }
    }

    @Transaction
    suspend fun completeSession(sessionId: Long) {
        val session = getById(sessionId)
        if (session != null) {
            update(session.copy(isActive = false))
        }
    }
}