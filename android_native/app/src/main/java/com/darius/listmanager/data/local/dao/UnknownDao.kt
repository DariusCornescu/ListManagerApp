package com.darius.listmanager.data.local.dao

import androidx.room.*
import com.darius.listmanager.data.local.entity.UnknownProductEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UnknownDao {

    @Query("SELECT * FROM unknown_products ORDER BY id DESC")
    fun getAllFlow(): Flow<List<UnknownProductEntity>>

    @Query("SELECT * FROM unknown_products")
    suspend fun getAll(): List<UnknownProductEntity>

    @Insert
    suspend fun insert(unknown: UnknownProductEntity): Long

    @Delete
    suspend fun delete(unknown: UnknownProductEntity)

    @Query("DELETE FROM unknown_products WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM unknown_products")
    suspend fun deleteAll()
}