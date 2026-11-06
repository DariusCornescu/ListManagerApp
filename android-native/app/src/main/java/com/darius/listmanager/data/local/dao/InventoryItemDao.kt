package com.darius.listmanager.data.local.dao

import androidx.room.*
import com.darius.listmanager.data.local.entity.InventoryItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryItemDao {

    // A team's inventory is shared between its members; a personal one belongs to its account.
    @Query(
        """SELECT * FROM inventory_items
           WHERE (:teamId IS NULL AND teamId IS NULL AND accountUserId = :accountUserId)
              OR (:teamId IS NOT NULL AND teamId = :teamId)
           ORDER BY id ASC"""
    )
    fun getAllFlow(accountUserId: Long, teamId: Long?): Flow<List<InventoryItemEntity>>

    @Query(
        """SELECT * FROM inventory_items
           WHERE (:teamId IS NULL AND teamId IS NULL AND accountUserId = :accountUserId)
              OR (:teamId IS NOT NULL AND teamId = :teamId)
           ORDER BY id ASC"""
    )
    suspend fun getAll(accountUserId: Long, teamId: Long?): List<InventoryItemEntity>

    @Query("SELECT * FROM inventory_items WHERE itemUuid = :itemUuid LIMIT 1")
    suspend fun getByUuid(itemUuid: String): InventoryItemEntity?

    @Query("SELECT * FROM inventory_items WHERE accountUserId = -1")
    suspend fun getUnclaimed(): List<InventoryItemEntity>

    @Insert
    suspend fun insert(item: InventoryItemEntity): Long

    @Update
    suspend fun update(item: InventoryItemEntity)

    @Query("DELETE FROM inventory_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """DELETE FROM inventory_items
           WHERE (:teamId IS NULL AND teamId IS NULL AND accountUserId = :accountUserId)
              OR (:teamId IS NOT NULL AND teamId = :teamId)"""
    )
    suspend fun deleteAll(accountUserId: Long, teamId: Long?)
}
