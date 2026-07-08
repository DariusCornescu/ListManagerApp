package com.darius.listmanager.data.repository

import com.darius.listmanager.data.local.dao.InventoryItemDao
import com.darius.listmanager.data.local.entity.InventoryItemEntity
import kotlinx.coroutines.flow.Flow

class InventoryRepository(private val dao: InventoryItemDao) {

    fun getAllFlow(): Flow<List<InventoryItemEntity>> = dao.getAllFlow()

    suspend fun insert(item: InventoryItemEntity): Long = dao.insert(item)

    suspend fun update(item: InventoryItemEntity) = dao.update(item)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()
}
