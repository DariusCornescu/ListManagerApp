package com.darius.listmanager.data.repository

import com.darius.listmanager.data.local.dao.SessionDao
import com.darius.listmanager.data.local.dao.SessionItemDao
import com.darius.listmanager.data.local.dao.SessionItemWithProduct
import com.darius.listmanager.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

class SessionRepository( private val sessionDao: SessionDao, private val sessionItemDao: SessionItemDao) {
    fun getActiveSessionFlow(): Flow<SessionEntity?> = sessionDao.getActiveSessionFlow()

    suspend fun getActiveSession(): SessionEntity? = sessionDao.getActiveSession()

    suspend fun getOrCreateActiveSession(): SessionEntity {
        return sessionDao.getOrCreateActiveSession()
    }

    suspend fun getSessionById(id: Long): SessionEntity? = sessionDao.getById(id)

    suspend fun completeSession(sessionId: Long) = sessionDao.completeSession(sessionId)

    // Session Items
    fun getSessionItemsFlow(sessionId: Long): Flow<List<SessionItemWithProduct>> {
        return sessionItemDao.getItemsWithProductFlow(sessionId)
    }

    suspend fun getSessionItems(sessionId: Long): List<SessionItemWithProduct> {
        return sessionItemDao.getItemsWithProduct(sessionId)
    }

    suspend fun addOrIncrementItem(sessionId: Long, productId: Long, quantity: Int = 1) {
        sessionItemDao.addOrIncrement(sessionId, productId, quantity)
    }

    suspend fun setItemQuantity(sessionId: Long, productId: Long, quantity: Int) {
        sessionItemDao.setQuantity(sessionId, productId, quantity)
    }

    suspend fun deleteItem(itemId: Long) {
        sessionItemDao.deleteById(itemId)
    }

    suspend fun clearSession(sessionId: Long) {
        sessionItemDao.deleteAllInSession(sessionId)
    }
}