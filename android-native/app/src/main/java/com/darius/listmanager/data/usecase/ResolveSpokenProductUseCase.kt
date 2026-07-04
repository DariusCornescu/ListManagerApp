package com.darius.listmanager.data.usecase

import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.util.PhraseSegmenter
import com.darius.listmanager.util.ProductRanker
import com.darius.listmanager.util.QueryVariants
import com.darius.listmanager.util.RankedProduct
import com.darius.listmanager.data.embedding.EmbeddingModel
import com.darius.listmanager.data.repository.ProductEmbeddingRepository
import com.darius.listmanager.util.EmbeddingSearch
import com.darius.listmanager.util.VectorMath

sealed class ResolveResult {
    /**
     * High confidence match - auto-add to session
     */
    data class AutoAdd(val product: ProductEntity, val score: Double) : ResolveResult()

    /**
     * Medium confidence - show suggestions to user
     */
    data class Suggestions(val products: List<RankedProduct>) : ResolveResult()

    /**
     * Low confidence - store as unknown
     */
    data class Unknown(val spokenText: String) : ResolveResult()
}

/**
 * Use case for resolving spoken product text to actual products
 *
 * Decision thresholds:
 * - >= 0.82: Auto-add (high confidence)
 * - >= 0.60: Show suggestions (medium confidence)
 * - < 0.60: Store as unknown (low confidence)
 */
class ResolveSpokenProductUseCase(
    private val productRepository: ProductRepository,
    private val embeddingModel: EmbeddingModel? = null,
    private val embeddingRepository: ProductEmbeddingRepository? = null
) {
    companion object {
        private const val AUTO_ADD_THRESHOLD = 0.82
        private const val SUGGESTIONS_THRESHOLD = 0.60
        private const val MAX_SUGGESTIONS = 5
        private const val EMB_TOP_K = 10
        // Minimum fuzzy score for a word-window to be cut out as its own product
        // during multi-product segmentation. Tunable.
        private const val SEGMENT_THRESHOLD = 0.62
        private const val SEGMENT_MAX_WINDOW = 4
    }

    /**
     * Resolve a spoken phrase that may contain SEVERAL products said in one
     * breath ("lalele pâine albă ouă"). Segments the phrase against the local
     * catalog, then resolves each segment individually. Returns (segmentText,
     * result) pairs. A single-product phrase yields a one-element list, so this
     * is a safe superset of [execute].
     */
    suspend fun resolveMultiple(spokenText: String): List<Pair<String, ResolveResult>> {
        if (spokenText.isBlank()) {
            return listOf(spokenText to ResolveResult.Unknown(spokenText))
        }

        val localProducts = try {
            productRepository.getAllLocal()
        } catch (e: Exception) {
            android.util.Log.e("ResolveUseCase", "getAllLocal failed: ${e.message}", e)
            emptyList()
        }

        val segments = if (localProducts.isEmpty()) {
            listOf(spokenText)
        } else {
            PhraseSegmenter.segment(
                text = spokenText,
                threshold = SEGMENT_THRESHOLD,
                maxWindow = SEGMENT_MAX_WINDOW
            ) { window ->
                ProductRanker.rank(window, localProducts).firstOrNull()?.score ?: 0.0
            }.ifEmpty { listOf(spokenText) }
        }

        android.util.Log.d("ResolveUseCase", "Segmented '$spokenText' -> $segments")
        return segments.map { segment -> segment to execute(segment) }
    }

    suspend fun execute(spokenText: String): ResolveResult {
        if (spokenText.isBlank()) {
            return ResolveResult.Unknown(spokenText)
        }

        android.util.Log.d("ResolveUseCase", "=== Starting Resolution ===")
        android.util.Log.d("ResolveUseCase", "Spoken text: '$spokenText'")

        // Step 1: Generate query variants
        val variants = QueryVariants.generate(spokenText)
        android.util.Log.d("ResolveUseCase", "Generated ${variants.size} variants: $variants")

        // Step 2: Search FTS with variants
        val ftsQuery = QueryVariants.toFtsQuery(variants)
        android.util.Log.d("ResolveUseCase", "FTS Query: $ftsQuery")

        val ftsResults = try {
            productRepository.searchFtsRaw(ftsQuery)
        } catch (e: Exception) {
            android.util.Log.e("ResolveUseCase", "FTS query failed: ${e.message}", e)
            productRepository.getAll()
        }

        android.util.Log.d("ResolveUseCase", "FTS returned ${ftsResults.size} products")

        // Step 3: If FTS returns nothing, try with all products (expensive fallback)
        val candidateProducts = if (ftsResults.isEmpty()) {
            android.util.Log.d("ResolveUseCase", "FTS empty, using ALL products fallback")
            productRepository.getAll()
        } else {
            ftsResults
        }

        android.util.Log.d("ResolveUseCase", "Candidate products: ${candidateProducts.size}")

        // Step 3.5: Semantic retrieval (hybrid). If no model/vectors, this is a no-op.
        var embeddingScores: Map<Long, Double> = emptyMap()
        var embeddingCandidates: List<ProductEntity> = emptyList()
        val queryVec = embeddingModel?.embed(spokenText)
        if (queryVec != null && embeddingRepository != null) {
            val cached = embeddingRepository.getAllForVersion(EmbeddingModel.MODEL_VERSION)
            if (cached.isNotEmpty()) {
                embeddingScores = cached.associate { (id, vec) ->
                    id to VectorMath.cosineSimilarity(queryVec, vec).toDouble()
                }
                val topIds = EmbeddingSearch.topK(queryVec, cached, EMB_TOP_K).map { it.productId }.toSet()
                if (topIds.isNotEmpty()) {
                    val localById = productRepository.getAllLocal().associateBy { it.id }
                    embeddingCandidates = topIds.mapNotNull { localById[it] }
                }
            }
        }

        val allCandidates = (candidateProducts + embeddingCandidates).distinctBy { it.id }

        if (allCandidates.isEmpty()) {
            android.util.Log.d("ResolveUseCase", "No candidates -> Unknown")
            return ResolveResult.Unknown(spokenText)
        }

        // Step 4: Rank products by similarity (fuzzy + embedding)
        val rankedProducts = ProductRanker.rank(spokenText, allCandidates, embeddingScores)
        android.util.Log.d("ResolveUseCase", "Ranked ${rankedProducts.size} products")

        rankedProducts.take(5).forEach {
            android.util.Log.d("ResolveUseCase", "  ${it.product.name}: ${it.score}")
        }

        if (rankedProducts.isEmpty()) {
            android.util.Log.d("ResolveUseCase", "No ranked products -> Unknown")
            return ResolveResult.Unknown(spokenText)
        }

        // Step 5: Make decision based on top score
        val topMatch = rankedProducts.first()
        android.util.Log.d("ResolveUseCase", "Top match: ${topMatch.product.name} = ${topMatch.score}")

        return when {
            topMatch.score >= AUTO_ADD_THRESHOLD -> {
                android.util.Log.d("ResolveUseCase", "Decision: AutoAdd (${topMatch.score} >= $AUTO_ADD_THRESHOLD)")
                ResolveResult.AutoAdd(topMatch.product, topMatch.score)
            }
            topMatch.score >= SUGGESTIONS_THRESHOLD -> {
                android.util.Log.d("ResolveUseCase", "Decision: Suggestions (${topMatch.score} >= $SUGGESTIONS_THRESHOLD)")
                val suggestions = rankedProducts.take(MAX_SUGGESTIONS)
                ResolveResult.Suggestions(suggestions)
            }
            else -> {
                android.util.Log.d("ResolveUseCase", "Decision: Unknown (${topMatch.score} < $SUGGESTIONS_THRESHOLD)")
                ResolveResult.Unknown(spokenText)
            }
        }
    }
}