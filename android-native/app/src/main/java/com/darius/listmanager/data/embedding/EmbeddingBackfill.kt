package com.darius.listmanager.data.embedding

import android.util.Log
import com.darius.listmanager.data.local.dao.ProductDao
import com.darius.listmanager.data.repository.ProductEmbeddingRepository

/**
 * Computes and caches embeddings for products that don't yet have one for the
 * current model version. Idempotent and safe to call on every app start.
 * No-op (silent) if the model is unavailable.
 */
class EmbeddingBackfill(
    private val productDao: ProductDao,
    private val embeddingRepository: ProductEmbeddingRepository,
    private val embeddingModel: EmbeddingModel
) {
    suspend fun run() {
        if (!embeddingModel.ensureReady()) {
            Log.d(TAG, "Model not ready; skipping backfill")
            return
        }
        val missingIds = embeddingRepository.getMissingProductIds(EmbeddingModel.MODEL_VERSION).toSet()
        if (missingIds.isEmpty()) return

        val products = productDao.getAll().filter { it.id in missingIds }
        Log.d(TAG, "Backfilling ${products.size} product embeddings")
        for (product in products) {
            val text = buildString {
                append(product.name)
                val aliases = product.aliases
                if (!aliases.isNullOrBlank()) {
                    append(' ')
                    append(aliases.replace(',', ' '))
                }
            }
            val vector = embeddingModel.embed(text) ?: continue
            embeddingRepository.upsert(product.id, vector, EmbeddingModel.MODEL_VERSION)
        }
        Log.d(TAG, "Backfill complete")
    }

    companion object { private const val TAG = "EmbeddingBackfill" }
}
