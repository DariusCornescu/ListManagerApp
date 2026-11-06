package com.darius.listmanager.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.darius.listmanager.data.local.entity.ProductEntity

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY name ASC")
    fun getAllFlow(): Flow<List<ProductEntity>>
    
    @Query("SELECT COUNT(*) FROM products")
    fun getCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM products")
    suspend fun count(): Int

    @Query("UPDATE products SET distributorId = :toId WHERE distributorId = :fromId")
    suspend fun moveToDistributor(fromId: Long, toId: Long)

    @Query("SELECT * FROM products ORDER BY name ASC")
    suspend fun getAll(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getById(id: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE distributorId = :distributorId ORDER BY name ASC")
    suspend fun getByDistributor(distributorId: Long): List<ProductEntity>

    // Update existing parents in place; REPLACE cascades into session items.
    @Upsert
    suspend fun insert(product: ProductEntity): Long

    // Update existing parents in place; REPLACE cascades into session items.
    @Upsert
    suspend fun insertAll(products: List<ProductEntity>)

    @Update
    suspend fun update(product: ProductEntity)

    @Delete
    suspend fun delete(product: ProductEntity)

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM products")
    suspend fun deleteAll()

    @Query("""
        SELECT p.* FROM products p
        JOIN products_fts fts ON p.rowid = fts.rowid
        WHERE products_fts MATCH :query
    """)
    suspend fun searchFts(query: String): List<ProductEntity>

    @Query("""
        SELECT p.* FROM products p
        JOIN products_fts fts ON p.rowid = fts.rowid
        WHERE products_fts MATCH :query
    """)
    suspend fun searchFtsRaw(query: String): List<ProductEntity>
}