package com.darius.listmanager.util

import com.darius.listmanager.data.local.entity.ProductEntity
import kotlin.collections.map

data class RankedProduct(
    val product: ProductEntity,
    val score: Double,
    val breakdown: Map<String, Double> = emptyMap()
)

object ProductRanker {

    /**
     * Rank products by their similarity to the spoken text
     * Uses multiple similarity metrics with weights
     */
    fun rank(spokenText: String, products: List<ProductEntity>, weights: ScoringWeights = ScoringWeights()): List<RankedProduct> {
        val normalizedSpoken = TextNorm.normalize(spokenText)

        return products.map { product ->
            val score = calculateScore(normalizedSpoken, product, weights)
            RankedProduct(
                product = product,
                score = score.total,
                breakdown = score.breakdown
            )
        }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
    }

    /**
     * Calculate comprehensive similarity score for a product
     */
    private fun calculateScore(
        normalizedSpoken: String,
        product: ProductEntity,
        weights: ScoringWeights
    ): Score {
        val productName = TextNorm.normalize(product.name)
        val aliases = product.aliases?.split(",")
            ?.map { TextNorm.normalize(it) }
            ?: emptyList()

        // Calculate scores against product name
        val nameLevenshtein = SimilarityEngine.levenshteinSimilarity(normalizedSpoken, productName)
        val nameJaccard = SimilarityEngine.jaccardSimilarity(normalizedSpoken, productName)
        val nameToken = SimilarityEngine.tokenSimilarity(normalizedSpoken, productName)
        val namePhonetic = SimilarityEngine.phoneticSimilarity(normalizedSpoken, productName)
        val namePrefix = SimilarityEngine.prefixSimilarity(normalizedSpoken, productName)

        // Check if product name contains/is contained in spoken text
        val containsBonus = when {
            productName == normalizedSpoken -> 1.0
            normalizedSpoken.contains(productName) || productName.contains(normalizedSpoken) -> 0.5
            else -> 0.0
        }

        // Calculate max score from aliases
        val aliasScores = aliases.map { alias ->
            val aliasLevenshtein = SimilarityEngine.levenshteinSimilarity(normalizedSpoken, alias)
            val aliasJaccard = SimilarityEngine.jaccardSimilarity(normalizedSpoken, alias)
            val aliasToken = SimilarityEngine.tokenSimilarity(normalizedSpoken, alias)
            val aliasPhonetic = SimilarityEngine.phoneticSimilarity(normalizedSpoken, alias)

            // Exact match bonus for aliases
            val aliasContains = when {
                alias == normalizedSpoken -> 1.0
                normalizedSpoken.contains(alias) || alias.contains(normalizedSpoken) -> 0.5
                else -> 0.0
            }

            (aliasLevenshtein * weights.levenshtein +
                    aliasJaccard * weights.jaccard +
                    aliasToken * weights.token +
                    aliasPhonetic * weights.phonetic +
                    aliasContains * weights.containsBonus) / 5.0
        }

        val maxAliasScore = aliasScores.maxOrNull() ?: 0.0

        // Number similarity (if product has numbers)
        val numberSim = if (TextNorm.containsDigits(normalizedSpoken) ||
            TextNorm.containsDigits(productName)) {
            SimilarityEngine.numberSeqSimilarity(normalizedSpoken, productName)
        } else {
            0.0
        }

        // Weighted combination
        val nameScore = (
                nameLevenshtein * weights.levenshtein +
                        nameJaccard * weights.jaccard +
                        nameToken * weights.token +
                        namePhonetic * weights.phonetic +
                        namePrefix * weights.prefix +
                        containsBonus * weights.containsBonus +
                        numberSim * weights.numberSeq
                ) / 7.0

        // Take the best of name score or alias score
        val finalScore = maxOf(nameScore, maxAliasScore)

        return Score(
            total = finalScore,
            breakdown = mapOf(
                "levenshtein" to nameLevenshtein,
                "jaccard" to nameJaccard,
                "token" to nameToken,
                "phonetic" to namePhonetic,
                "prefix" to namePrefix,
                "contains" to containsBonus,
                "number" to numberSim,
                "aliasMax" to maxAliasScore,
                "final" to finalScore
            )
        )
    }

    private data class Score(
        val total: Double,
        val breakdown: Map<String, Double>
    )
}

data class ScoringWeights(
    val levenshtein: Double = 1.5,  // Edit distance - high weight
    val jaccard: Double = 1.2,      // Character n-grams
    val token: Double = 1.3,        // Token matching
    val phonetic: Double = 0.8,     // Phonetic similarity
    val prefix: Double = 0.7,       // Prefix matching
    val containsBonus: Double = 2.0,// Contains/exact match bonus - highest
    val numberSeq: Double = 1.0     // Number sequence matching
)