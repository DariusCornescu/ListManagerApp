package com.darius.listmanager.data.local.dao

import androidx.room.*
import com.darius.listmanager.data.local.entity.SessionItemEntity
import kotlinx.coroutines.flow.Flow

data class SessionItemWithProduct(
    val id: Long,
    val sessionId: Long,
    val productId: Long,
    val productName: String,
    val distributorId: Long,
    val distributorName: String,
    val quantity: Int,
)

@Dao
interface SessionItemDao {
    @Query("""
    SELECT 
        si.id, si.sessionId, si.productId, si.quantity,
        p.name as productName,
        d.id as distributorId, d.distributorName as distributorName
    FROM session_items si
    JOIN products p ON si.productId = p.id
    JOIN distributors d ON p.distributorId = d.id
    WHERE si.sessionId = :sessionId
    ORDER BY si.id DESC
""")
    fun getItemsWithProductFlow(sessionId: Long): Flow<List<SessionItemWithProduct>>

    @Query("""
    SELECT 
        si.id, si.sessionId, si.productId, si.quantity,
        p.name as productName,
        d.id as distributorId, d.distributorName as distributorName
    FROM session_items si
    JOIN products p ON si.productId = p.id
    JOIN distributors d ON p.distributorId = d.id
    WHERE si.sessionId = :sessionId
    ORDER BY d.distributorName, p.name
""")
    suspend fun getItemsWithProduct(sessionId: Long): List<SessionItemWithProduct>

    @Query("SELECT * FROM session_items WHERE sessionId = :sessionId AND productId = :productId LIMIT 1")
    suspend fun getItem(sessionId: Long, productId: Long): SessionItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SessionItemEntity): Long

    @Update
    suspend fun update(item: SessionItemEntity)

    @Delete
    suspend fun delete(item: SessionItemEntity)

    @Query("DELETE FROM session_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM session_items WHERE sessionId = :sessionId")
    suspend fun deleteAllInSession(sessionId: Long)

    @Transaction
    suspend fun addOrIncrement(sessionId: Long, productId: Long, quantityToAdd: Int = 1) {
        val existing = getItem(sessionId, productId)
        if (existing != null) {
            update(existing.copy(quantity = existing.quantity + quantityToAdd))
        } else {
            insert(SessionItemEntity(sessionId = sessionId, productId = productId, quantity = quantityToAdd))
        }
    }

    @Transaction
    suspend fun setQuantity(sessionId: Long, productId: Long, quantity: Int) {
        val existing = getItem(sessionId, productId)
        if (existing != null) {
            if (quantity <= 0) {
                delete(existing)
            } else {
                update(existing.copy(quantity = quantity))
            }
        } else if (quantity > 0) {
            insert(SessionItemEntity(sessionId = sessionId, productId = productId, quantity = quantity))
        }
    }
}