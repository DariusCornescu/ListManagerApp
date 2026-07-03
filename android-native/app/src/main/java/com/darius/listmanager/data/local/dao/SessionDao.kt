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

    @Upsert
    suspend fun upsert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("UPDATE sessions SET isActive = 0 WHERE teamId IS :teamId")
    suspend fun deactivateAll(teamId: Long?)

    @Query("SELECT MIN(id) FROM sessions")
    suspend fun getMinSessionId(): Long?

    @Transaction
    suspend fun getOrCreateActiveSession(teamId: Long?): SessionEntity {
        val active = getActiveSession(teamId)
        if (active != null) return active
        // Local fallback sessions get negative ids; server-mirrored sessions
        // (activateServerSession) own the positive id space.
        val newId = minOf(getMinSessionId() ?: 0L, 0L) - 1L
        insert(SessionEntity(id = newId, name = "Current Session", isActive = true, teamId = teamId))
        return getById(newId)!!
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
