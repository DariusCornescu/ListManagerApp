package com.darius.listmanager.data.local.dao

import androidx.room.*
import com.darius.listmanager.data.local.entity.InventoryItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryItemDao {

    @Query("SELECT * FROM inventory_items ORDER BY id ASC")
    fun getAllFlow(): Flow<List<InventoryItemEntity>>

    @Insert
    suspend fun insert(item: InventoryItemEntity): Long

    @Update
    suspend fun update(item: InventoryItemEntity)

    @Query("DELETE FROM inventory_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM inventory_items")
    suspend fun deleteAll()
}
