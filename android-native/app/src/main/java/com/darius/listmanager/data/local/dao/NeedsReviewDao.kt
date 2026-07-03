package com.darius.listmanager.data.local.dao

import androidx.room.*
import com.darius.listmanager.data.local.entity.NeedsReviewEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NeedsReviewDao {

    @Query("SELECT * FROM needs_review ORDER BY id DESC")
    fun getAllFlow(): Flow<List<NeedsReviewEntity>>

    @Query("SELECT * FROM needs_review")
    suspend fun getAll(): List<NeedsReviewEntity>

    @Insert
    suspend fun insert(item: NeedsReviewEntity): Long

    @Query("DELETE FROM needs_review WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM needs_review")
    suspend fun deleteAll()
}
