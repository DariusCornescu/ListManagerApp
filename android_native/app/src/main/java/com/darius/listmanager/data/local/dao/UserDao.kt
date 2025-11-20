package com.darius.listmanager.data.local.dao

import android.icu.text.MessagePattern
import androidx.room.*
import com.darius.listmanager.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Query("SELECT * FROM users WHERE id = 1")
    suspend fun getCurrentUser(): UserEntity?

    @Query("SELECT * FROM users WHERE id = 1")
    fun getCurrentUserFlow(): Flow<UserEntity?>

    @Query("SELECT isLoggedIn FROM users WHERE id = 1")
    fun isLoggedInFlow(): Flow<Boolean?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveUser(user: UserEntity)

    @Query("UPDATE users SET jwtToken = :token, tokenExpiresAt = :expiresAt WHERE id = 1")
    suspend fun updateToken(token: String, expiresAt: Long)

    @Query("UPDATE users SET lastSyncedAt = :timestamp WHERE id = 1")
    suspend fun updateLastSync(timestamp: Long)

    @Query("UPDATE users SET jwtToken = NULL, tokenExpiresAt = NULL, isLoggedIn = 0 WHERE id = 1")
    suspend fun logout()

    @Query("DELETE FROM users")
    suspend fun deleteAll()






}