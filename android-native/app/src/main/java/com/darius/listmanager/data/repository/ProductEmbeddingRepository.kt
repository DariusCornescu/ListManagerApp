package com.darius.listmanager.data.repository

import com.darius.listmanager.data.local.dao.ProductEmbeddingDao
import com.darius.listmanager.data.local.entity.ProductEmbeddingEntity
import com.darius.listmanager.util.VectorMath

class ProductEmbeddingRepository(private val dao: ProductEmbeddingDao) {

    suspend fun upsert(productId: Long, vector: FloatArray, modelVersion: String) {
        dao.upsert(
            ProductEmbeddingEntity(
                productId = productId,
                vector = VectorMath.floatsToBytes(vector),
                modelVersion = modelVersion
            )
        )
    }

    /** All cached vectors for [modelVersion] as (productId, vector) pairs. */
    suspend fun getAllForVersion(modelVersion: String): List<Pair<Long, FloatArray>> =
        dao.getAllForVersion(modelVersion).map { it.productId to VectorMath.bytesToFloats(it.vector) }

    suspend fun getMissingProductIds(modelVersion: String): List<Long> =
        dao.getMissingProductIds(modelVersion)

    suspend fun deleteByProductId(productId: Long) = dao.deleteByProductId(productId)
}
