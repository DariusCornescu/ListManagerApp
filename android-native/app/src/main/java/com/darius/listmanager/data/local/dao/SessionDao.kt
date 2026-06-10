package com.darius.listmanager.data.local.dao

import androidx.room.*
import com.darius.listmanager.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE isActive = 1 AND teamId IS :teamId LIMIT 1")
    suspend fun getActiveSession(teamId: Long?): SessionEntity?

    @Query("SELECT * FROM sessions WHERE isActive = 1 AND teamId IS :teamId LIMIT 1")
    fun getActiveSessionFlow(teamId: Long?): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("UPDATE sessions SET isActive = 0 WHERE teamId IS :teamId")
    suspend fun deactivateAll(teamId: Long?)

    @Transaction
    suspend fun getOrCreateActiveSession(teamId: Long?): SessionEntity {
        val active = getActiveSession(teamId)
        return if (active != null) {
            active
        } else {
            val newId = insert(SessionEntity(name = "Current Session", isActive = true, teamId = teamId))
            getById(newId)!!
        }
    }

    /**
     * Mirror a server session into the local cache as the single active
     * session of its workspace. Uses the SERVER id as the local id (same
     * convention as products/distributors).
     */
    @Transaction
    suspend fun activateServerSession(serverId: Long, name: String, teamId: Long?) {
        deactivateAll(teamId)
        upsert(SessionEntity(id = serverId, name = name, isActive = true, teamId = teamId))
    }

    @Transaction
    suspend fun completeSession(sessionId: Long) {
        val session = getById(sessionId)
        if (session != null) {
            update(session.copy(isActive = false))
        }
    }
}
