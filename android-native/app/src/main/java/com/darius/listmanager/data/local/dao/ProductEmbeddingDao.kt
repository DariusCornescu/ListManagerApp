package com.darius.listmanager.data.local.dao

import androidx.room.*
import com.darius.listmanager.data.local.entity.ProductEmbeddingEntity

@Dao
interface ProductEmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ProductEmbeddingEntity)

    @Query("SELECT * FROM product_embeddings WHERE modelVersion = :modelVersion")
    suspend fun getAllForVersion(modelVersion: String): List<ProductEmbeddingEntity>

    @Query(
        """
        SELECT p.id FROM products p
        LEFT JOIN product_embeddings e
          ON p.id = e.productId AND e.modelVersion = :modelVersion
        WHERE e.productId IS NULL
        """
    )
    suspend fun getMissingProductIds(modelVersion: String): List<Long>

    @Query("DELETE FROM product_embeddings WHERE productId = :productId")
    suspend fun deleteByProductId(productId: Long)

    @Query("DELETE FROM product_embeddings")
    suspend fun deleteAll()
}
