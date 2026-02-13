package com.darius.listmanager.data.repository

import com.darius.listmanager.data.local.dao.UnknownDao
import com.darius.listmanager.data.local.entity.UnknownProductEntity
import kotlinx.coroutines.flow.Flow

class UnknownRepository(private val dao: UnknownDao) {

    fun getAllFlow(): Flow<List<UnknownProductEntity>> = dao.getAllFlow()

    suspend fun getAll(): List<UnknownProductEntity> = dao.getAll()

    suspend fun insert(spokenText: String): Long {
        return dao.insert(UnknownProductEntity(spokenText = spokenText))
    }

    suspend fun delete(unknown: UnknownProductEntity) = dao.delete(unknown)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()
}