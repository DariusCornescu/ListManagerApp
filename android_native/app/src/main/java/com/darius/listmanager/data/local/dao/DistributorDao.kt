package com.darius.listmanager.data.local.dao

import androidx.room.*
import com.darius.listmanager.data.local.entity.DistributorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DistributorDao {
    @Query("SELECT * FROM distributors ORDER BY distributorName ASC")
    fun getAllFlow(): Flow<List<DistributorEntity>>

    @Query("SELECT * FROM distributors ORDER BY distributorName ASC")
    suspend fun getAll(): List<DistributorEntity>

    @Query("SELECT * FROM distributors WHERE id = :id")
    suspend fun getById(id: Long): DistributorEntity?

    @Query("SELECT * FROM distributors WHERE distributorName = :name LIMIT 1")
    suspend fun getByName(name: String): DistributorEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(distributor: DistributorEntity): Long

    @Update
    suspend fun update(distributor: DistributorEntity)

    @Delete
    suspend fun delete(distributor: DistributorEntity)

    @Transaction
    suspend fun upsertByName(name: String, contactInfo: String? = null): Long {
        val existing = getByName(name)
        return if (existing != null) {
            existing.id
        } else {
            insert(DistributorEntity(distributorName = name))
        }
    }
}