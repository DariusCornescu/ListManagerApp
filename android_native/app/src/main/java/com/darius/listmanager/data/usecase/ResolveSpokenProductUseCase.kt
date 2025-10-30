package com.darius.listmanager.data.usecase

import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.util.ProductRanker
import com.darius.listmanager.util.QueryVariants
import com.darius.listmanager.util.RankedProduct

/**
 * Result of resolving a spoken product name
 */
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
    private val productRepository: ProductRepository
) {
    companion object {
        private const val AUTO_ADD_THRESHOLD = 0.82
        private const val SUGGESTIONS_THRESHOLD = 0.60
        private const val MAX_SUGGESTIONS = 5
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

        if (candidateProducts.isEmpty()) {
            android.util.Log.d("ResolveUseCase", "No candidates -> Unknown")
            return ResolveResult.Unknown(spokenText)
        }

        // Step 4: Rank products by similarity
        val rankedProducts = ProductRanker.rank(spokenText, candidateProducts)
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