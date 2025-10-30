package com.darius.listmanager.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.darius.listmanager.data.local.entity.ProductEntity

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY name ASC")
    fun getAllFlow(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products ORDER BY name ASC")
    suspend fun getAll(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getById(id: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE distributorId = :distributorId ORDER BY name ASC")
    suspend fun getByDistributor(distributorId: Long): List<ProductEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(product: ProductEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<ProductEntity>)

    @Update
    suspend fun update(product: ProductEntity)

    @Delete
    suspend fun delete(product: ProductEntity)

    // FTS search - returns product IDs that match the query
    @Query("""
        SELECT p.* FROM products p
        JOIN products_fts fts ON p.rowid = fts.rowid
        WHERE products_fts MATCH :query
    """)
    suspend fun searchFts(query: String): List<ProductEntity>

    // Raw FTS search for custom ranking later
    @Query("""
        SELECT p.* FROM products p
        JOIN products_fts fts ON p.rowid = fts.rowid
        WHERE products_fts MATCH :query
    """)
    suspend fun searchFtsRaw(query: String): List<ProductEntity>
}