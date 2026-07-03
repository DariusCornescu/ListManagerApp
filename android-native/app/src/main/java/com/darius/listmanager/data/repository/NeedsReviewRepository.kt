package com.darius.listmanager.data.repository

import com.darius.listmanager.data.local.dao.NeedsReviewDao
import com.darius.listmanager.data.local.entity.NeedsReviewEntity
import kotlinx.coroutines.flow.Flow

class NeedsReviewRepository(private val dao: NeedsReviewDao) {

    fun getAllFlow(): Flow<List<NeedsReviewEntity>> = dao.getAllFlow()

    suspend fun getAll(): List<NeedsReviewEntity> = dao.getAll()

    suspend fun insert(spokenText: String, sessionId: Long, createdAt: Long): Long {
        return dao.insert(
            NeedsReviewEntity(
                spokenText = spokenText,
                sessionId = sessionId,
                createdAt = createdAt
            )
        )
    }

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()
}
