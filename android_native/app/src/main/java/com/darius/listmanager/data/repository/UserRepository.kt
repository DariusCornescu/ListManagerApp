package com.darius.listmanager.data.repository

import com.darius.listmanager.data.local.dao.UserDao
import com.darius.listmanager.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

class UserRepository(private val dao: UserDao) {

    fun getCurrentUserFlow(): Flow<UserEntity?> = dao.getCurrentUserFlow()

    suspend fun getCurrentUser(): UserEntity? = dao.getCurrentUser()

    fun isLoggedInFlow(): Flow<Boolean?> = dao.isLoggedInFlow()

    suspend fun saveUser(user: UserEntity) = dao.saveUser(user)

    suspend fun updateToken(token: String, expiresAt: Long) = dao.updateToken(token, expiresAt)

    suspend fun updateLastSync(timestamp: Long) = dao.updateLastSync(timestamp)

    suspend fun logout() = dao.logout()

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun isTokenValid(): Boolean {
        val user = getCurrentUser() ?: return false
        if (user.jwtToken.isNullOrBlank()) return false

        val expiresAt = user.tokenExpiresAt ?: return false
        return System.currentTimeMillis() < expiresAt
    }
}