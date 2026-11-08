package com.darius.listmanager.data.usecase

import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.util.CatalogMatch
import com.darius.listmanager.util.MatchStats
import com.darius.listmanager.util.PhraseSegmenter
import com.darius.listmanager.util.ProductRanker
import com.darius.listmanager.util.QueryVariants
import com.darius.listmanager.util.RankedProduct
import com.darius.listmanager.util.ScoringWeights
import com.darius.listmanager.util.SensitiveLog
import com.darius.listmanager.util.SpokenQuantity
import com.darius.listmanager.data.embedding.EmbeddingModel
import com.darius.listmanager.data.repository.ProductEmbeddingRepository
import com.darius.listmanager.util.EmbeddingSearch
import com.darius.listmanager.util.VectorMath
import com.darius.listmanager.util.PreparedCatalog
import com.darius.listmanager.util.PreparedCatalogCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * - >= 0.82: Auto-add (high confidence), when no other product is within 0.05
 * - >= 0.60: Show suggestions (medium confidence)
 * - < 0.60: Store as unknown (low confidence)
 */
class ResolveSpokenProductUseCase(
    private val productRepository: ProductRepository,
    private val embeddingModel: EmbeddingModel? = null,
    private val embeddingRepository: ProductEmbeddingRepository? = null,
    // Scoring the whole catalog takes seconds on a phone, so callers on the main
    // thread must not run it themselves.
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    companion object {
        private const val AUTO_ADD_THRESHOLD = 0.82
        // Variants that differ only in a supplier code ("... - SET A", "... - SET B") can tie.
        // Adding one of them without asking would be a guess.
        private const val AUTO_ADD_MARGIN = 0.05
        private const val SUGGESTIONS_THRESHOLD = 0.60
        private const val MAX_SUGGESTIONS = 5
        private const val EMB_TOP_K = 10
        // Timings only, never the spoken text, so it is safe in release logs.
        private const val TIMING_TAG = "DictationTiming"

        private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000
        // Minimum fuzzy score for a word-window to be cut out as its own product
        // during multi-product segmentation. Tunable.
        private const val SEGMENT_THRESHOLD = 0.62
        private const val SEGMENT_MAX_WINDOW = 7

        internal fun mergeAdjacentAutoAdds(
            results: List<Pair<String, ResolveResult>>,
        ): List<Pair<String, ResolveResult>> {
            val merged = mutableListOf<Pair<String, ResolveResult>>()
            for ((segment, result) in results) {
                val previous = merged.lastOrNull()
                val previousMatch = previous?.second as? ResolveResult.AutoAdd
                val currentMatch = result as? ResolveResult.AutoAdd
                val sameProduct = previousMatch != null && currentMatch != null &&
                    previousMatch.product.id == currentMatch.product.id
                if (sameProduct && !SpokenQuantity.hasExplicitPrefix(segment)) {
                    val bestMatch = if (currentMatch.score > previousMatch.score) {
                        currentMatch
                    } else {
                        previousMatch
                    }
                    merged[merged.lastIndex] = "${previous.first} $segment" to bestMatch
                } else {
                    merged += segment to result
                }
            }
            return merged
        }

        /**
         * Split an utterance into the products said in it, scoring word windows against
         * [catalog]. Each window is scored only against the products sharing a word with it
         * ([candidatesOnly]), which cuts utterances like scoring the whole catalog does, at a
         * fraction of the cost.
         */
        internal fun segment(
            spokenText: String,
            catalog: PreparedCatalog,
            candidatesOnly: Boolean = true,
            stats: MatchStats? = null,
        ): List<String> {
            if (catalog.products.isEmpty()) return listOf(spokenText)
            // The segmenter asks about some windows more than once.
            val matches = HashMap<String, CatalogMatch?>()
            fun matchOf(window: String): CatalogMatch? =
                if (window in matches) matches[window]
                else ProductRanker.bestMatch(window, catalog, ScoringWeights(), candidatesOnly, stats)
                    .also { matches[window] = it }
            return PhraseSegmenter.segment(
                text = spokenText,
                threshold = SEGMENT_THRESHOLD,
                maxWindow = SEGMENT_MAX_WINDOW,
                strays = { window, word, rest ->
                    val product = matchOf(window)?.product
                    product != null && !product.mentions(word) &&
                        matchOf(rest)?.product?.product?.id == product.product.id
                },
            ) { window ->
                matchOf(window)?.score ?: 0.0
            }.ifEmpty { listOf(spokenText) }
        }
    }

    /**
     * Resolve a spoken phrase that may contain SEVERAL products said in one
     * breath ("lalele pâine albă ouă"). Segments the phrase against the local
     * catalog, then resolves each segment individually. Returns (segmentText,
     * result) pairs. A single-product phrase yields a one-element list, so this
     * is a safe superset of [execute].
     */
    suspend fun resolveMultiple(
        spokenText: String,
        alternatives: List<String> = emptyList()
    ): List<Pair<String, ResolveResult>> = withContext(computeDispatcher) {
        val started = System.nanoTime()
        segmentAndResolve(spokenText, alternatives).also {
            android.util.Log.d(TIMING_TAG, "Resolved ${it.size} part(s) in ${elapsedMs(started)} ms")
        }
    }

    /**
     * Prepare the local catalog ahead of the first phrase, so the first dictation does not
     * pay for it. Safe to call again: an unchanged catalog is not prepared twice.
     */
    suspend fun warmUp() = withContext(computeDispatcher) {
        val started = System.nanoTime()
        val size = preparedLocalCatalog().products.size
        android.util.Log.d(TIMING_TAG, "Prepared $size products in ${elapsedMs(started)} ms")
    }

    /** The whole local catalog, prepared once per catalog version. Never touches the network. */
    private suspend fun preparedLocalCatalog(): PreparedCatalog {
        val localProducts = try {
            productRepository.getAllLocal()
        } catch (e: Exception) {
            android.util.Log.e("ResolveUseCase", "getAllLocal failed: ${e.message}", e)
            emptyList()
        }
        return PreparedCatalogCache.get(localProducts)
    }

    private suspend fun segmentAndResolve(
        spokenText: String,
        alternatives: List<String>
    ): List<Pair<String, ResolveResult>> {
        if (spokenText.isBlank()) {
            return listOf(spokenText to ResolveResult.Unknown(spokenText))
        }

        val started = System.nanoTime()
        // Prepared and indexed once per catalog; each word window below is scored against it.
        val catalog = preparedLocalCatalog()
        val stats = MatchStats()
        val segments = segment(spokenText, catalog, stats = stats)

        android.util.Log.d(
            TIMING_TAG,
            "Segmented into ${segments.size} part(s) in ${elapsedMs(started)} ms " +
                "(${stats.windows} windows, ${stats.wholeCatalog} against the whole catalog)",
        )
        SensitiveLog.d("ResolveUseCase", "Segmented utterance into ${segments.size} part(s)") {
            "Segmented '$spokenText' -> $segments"
        }
        // N-best alternatives are alternate transcripts of the WHOLE utterance, so
        // they only align with a single-product utterance. When the phrase splits
        // into several products, resolve each segment on its own.
        val resolved = if (segments.size == 1) {
            listOf(segments.first() to execute(segments.first(), alternatives))
        } else {
            segments.map { segment -> segment to execute(segment) }
        }
        return mergeAdjacentAutoAdds(resolved)
    }

    suspend fun execute(
        spokenText: String,
        alternatives: List<String> = emptyList()
    ): ResolveResult = withContext(computeDispatcher) {
        resolve(spokenText, alternatives)
    }

    private suspend fun resolve(spokenText: String, alternatives: List<String>): ResolveResult {
        if (spokenText.isBlank()) {
            return ResolveResult.Unknown(spokenText)
        }

        // Recognizer N-best: the primary transcript plus any alternate hypotheses.
        // Blank/duplicate guesses are dropped; a single hypothesis reproduces the
        // old single-transcript behavior exactly.
        val hypotheses = (listOf(spokenText) + alternatives)
            .map { SpokenQuantity.productText(it) }
            .filter { it.isNotEmpty() }
            .distinct()

        android.util.Log.d("ResolveUseCase", "=== Starting Resolution ===")
        SensitiveLog.d("ResolveUseCase", "Prepared ${hypotheses.size} hypothesis/hypotheses") {
            "Hypotheses: $hypotheses"
        }

        // Step 1+2: gather FTS candidates for EVERY hypothesis (union), so the
        // right product can still be found when the top guess is garbled.
        val ftsResults = hypotheses.flatMap { hypothesis ->
            val ftsQuery = QueryVariants.toFtsQuery(QueryVariants.generate(hypothesis))
            try {
                productRepository.searchFtsRaw(ftsQuery)
            } catch (e: Exception) {
                SensitiveLog.e("ResolveUseCase", "FTS query failed", e) {
                    "FTS query failed for '$hypothesis': ${e.message}"
                }
                emptyList()
            }
        }.distinctBy { it.id }

        android.util.Log.d("ResolveUseCase", "FTS returned ${ftsResults.size} products across ${hypotheses.size} hypotheses")

        // Step 3: If FTS returns nothing, rank the whole local catalog. It used to be
        // downloaded again from the server here, while the person waited for the result.
        val fullCatalog = if (ftsResults.isEmpty()) {
            android.util.Log.d("ResolveUseCase", "FTS empty, ranking the whole local catalog")
            preparedLocalCatalog()
        } else {
            null
        }

        android.util.Log.d("ResolveUseCase", "Candidate products: ${fullCatalog?.products?.size ?: ftsResults.size}")

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

        // Step 4: Rank products across all hypotheses (fuzzy + embedding), keeping
        // each product's best score so a bad top guess can't bury a good match.
        // The whole catalog already holds every embedding candidate.
        val rankedProducts = if (fullCatalog != null) {
            if (fullCatalog.products.isEmpty()) {
                android.util.Log.d("ResolveUseCase", "No candidates -> Unknown")
                return ResolveResult.Unknown(spokenText)
            }
            ProductRanker.rankAcross(hypotheses, fullCatalog, embeddingScores)
        } else {
            val allCandidates = (ftsResults + embeddingCandidates).distinctBy { it.id }
            ProductRanker.rankAcross(hypotheses, allCandidates, embeddingScores)
        }
        android.util.Log.d("ResolveUseCase", "Ranked ${rankedProducts.size} products")

        rankedProducts.take(5).forEach {
            SensitiveLog.d("ResolveUseCase", "Candidate score: ${it.score}") {
                "  ${it.product.name}: ${it.score}"
            }
        }

        if (rankedProducts.isEmpty()) {
            android.util.Log.d("ResolveUseCase", "No ranked products -> Unknown")
            return ResolveResult.Unknown(spokenText)
        }

        // Step 5: Make decision based on top score
        val topMatch = rankedProducts.first()
        SensitiveLog.d("ResolveUseCase", "Top match score: ${topMatch.score}") {
            "Top match: ${topMatch.product.name} = ${topMatch.score}"
        }

        val runnerUp = rankedProducts.getOrNull(1)?.score
        val clearlyAhead = runnerUp == null || topMatch.score - runnerUp >= AUTO_ADD_MARGIN

        return when {
            topMatch.score >= AUTO_ADD_THRESHOLD && clearlyAhead -> {
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
