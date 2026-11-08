package com.darius.listmanager.util

import com.darius.listmanager.data.local.entity.ProductEntity

data class RankedProduct(
    val product: ProductEntity,
    val score: Double,
    val breakdown: Map<String, Double> = emptyMap()
)

/**
 * A text normalized once, with everything the similarity measures derive from it.
 * Ranking used to recompute these for every product on every spoken phrase.
 */
class PreparedText internal constructor(internal val text: String) {
    // Numbers are compared without their decimal dot: the recognizer often loses it
    // ("26 * 175" for 26*1.75).
    internal val tokens: Set<String> = TextNorm.tokenize(text).map { it.replace(".", "") }.toSet()
    internal val phoneticKey: String = SimilarityEngine.phoneticKey(text)
    internal val bigrams: Set<String> = SimilarityEngine.bigrams(text)
    internal val hasDigits: Boolean = TextNorm.containsDigits(text)
    internal val numbers: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        TextNorm.extractNumbers(text).map { it.replace(".", "") }
    }
    internal val sizes: Set<String> = if (hasDigits) TextNorm.sizes(text) else emptySet()
}

class PreparedProduct internal constructor(val product: ProductEntity) {
    internal val name = PreparedText(TextNorm.normalize(product.name))
    internal val aliases: List<PreparedText> = product.aliases?.split(",")
        ?.map { PreparedText(TextNorm.normalize(it)) }
        ?: emptyList()

    internal val words: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        (name.tokens + aliases.flatMap { it.tokens })
            .map(::canonicalWord)
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * Whether a spoken word is one of this product's words: the same word, another ending of
     * it ("cameră"/"camere"), an abbreviation ("deterg."/"detergent"), or a near miss of the
     * recognizer. Numbers and sizes must be the same, compared without their decimal dots
     * as ranking does: "28x175" is another size than "26x1.75".
     */
    fun mentions(word: String): Boolean {
        val spoken = sizeSeparators(TextNorm.normalize(word).replace(".", ""))
        if (spoken.isEmpty()) return true
        if (!spoken.all { it.isLetter() }) return spoken in words
        val stem = TextNorm.wordStem(spoken)
        return words.any { own ->
            own == spoken || TextNorm.wordStem(own) == stem ||
                (minOf(own.length, spoken.length) >= 4 && (own.startsWith(spoken) || spoken.startsWith(own))) ||
                (spoken.length >= 4 && SimilarityEngine.swapTolerantSimilarity(spoken, own) >= 0.6)
        }
    }
}

// Dictation writes every size as "AxB"; a name may write it "A/B".
private val SIZE_SLASH = Regex("(?<=\\d)/(?=\\d)")

private fun sizeSeparators(word: String): String = word.replace(SIZE_SLASH, "x")

/** A token of a normalized text as a word: sizes written "AxB", no punctuation around it. */
private fun canonicalWord(token: String): String =
    sizeSeparators(token).trim(',', ';', ':', '(', ')', '/', '-', '\'', '"')

/** The best-scoring product for a phrase. */
class CatalogMatch(val product: PreparedProduct, val score: Double)

/** How many word windows were scored, and how many of them against the whole catalog. */
internal class MatchStats {
    var windows = 0
    var wholeCatalog = 0
}

/**
 * The catalog prepared for ranking. Build it once and rank many phrases against it, as
 * segmentation does for every word window of one utterance.
 */
class PreparedCatalog(products: List<ProductEntity>) {
    val products: List<PreparedProduct> = products.map(::PreparedProduct)

    /**
     * Products by the keys of their words, the same keys the catalog search (FTS) is asked
     * with in [QueryVariants.generate]: the word, its stem, and its first three letters.
     */
    private val byWordKey: Map<String, IntArray> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val index = HashMap<String, MutableList<Int>>()
        this.products.forEachIndexed { at, product ->
            product.words.flatMap(::wordKeys).toSet().forEach { key ->
                index.getOrPut(key) { mutableListOf() }.add(at)
            }
        }
        index.mapValues { (_, positions) -> positions.toIntArray() }
    }

    /** Build the word index now rather than on the first phrase. */
    fun indexWords() {
        byWordKey
    }

    /** Positions of the products sharing a word key with [spoken], in catalog order. */
    internal fun candidates(spoken: PreparedText): IntArray {
        val found = java.util.BitSet(products.size)
        for (token in spoken.tokens) {
            for (key in wordKeys(canonicalWord(token))) {
                byWordKey[key]?.forEach(found::set)
            }
        }
        return found.stream().toArray()
    }

    private companion object {
        fun wordKeys(word: String): List<String> {
            if (word.isEmpty()) return emptyList()
            val keys = mutableListOf(word)
            val stem = TextNorm.wordStem(word)
            if (stem != word) keys += "$stem*"
            if (word.length >= 3 && word.all { it.isLetter() }) keys += "${word.take(3)}~"
            return keys
        }
    }
}

object ProductRanker {

    fun prepare(products: List<ProductEntity>): PreparedCatalog = PreparedCatalog(products)

    /**
     * Rank products by their similarity to the spoken text
     * Uses multiple similarity metrics with weights
     */
    fun rank(
        spokenText: String,
        products: List<ProductEntity>,
        embeddingScores: Map<Long, Double> = emptyMap(),
        weights: ScoringWeights = ScoringWeights()
    ): List<RankedProduct> = rank(spokenText, prepare(products), embeddingScores, weights)

    fun rank(
        spokenText: String,
        catalog: PreparedCatalog,
        embeddingScores: Map<Long, Double> = emptyMap(),
        weights: ScoringWeights = ScoringWeights()
    ): List<RankedProduct> {
        val spoken = PreparedText(SpokenQuantity.productText(spokenText))

        return catalog.products.map { prepared ->
            val embeddingSim = embeddingScores[prepared.product.id] ?: 0.0
            val score = calculateScore(spoken, prepared, embeddingSim, weights)
            RankedProduct(
                product = prepared.product,
                score = score.total,
                breakdown = score.breakdown
            )
        }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
    }

    /**
     * The score of the best match, or 0.0 when nothing scores: the same value as
     * `rank(spokenText, catalog).firstOrNull()?.score ?: 0.0`, without sorting the catalog.
     */
    fun bestScore(
        spokenText: String,
        catalog: PreparedCatalog,
        weights: ScoringWeights = ScoringWeights()
    ): Double = bestMatch(spokenText, catalog, weights)?.score ?: 0.0

    /**
     * The product behind [bestScore], or null when nothing scores above 0.
     *
     * With [candidatesOnly], only the products sharing a word, a stem or the first three
     * letters of a word with the phrase are scored, as the catalog search would find them;
     * a phrase that shares none with any product is scored against the whole catalog.
     */
    fun bestMatch(
        spokenText: String,
        catalog: PreparedCatalog,
        weights: ScoringWeights = ScoringWeights(),
        candidatesOnly: Boolean = false,
    ): CatalogMatch? = bestMatch(spokenText, catalog, weights, candidatesOnly, stats = null)

    internal fun bestMatch(
        spokenText: String,
        catalog: PreparedCatalog,
        weights: ScoringWeights,
        candidatesOnly: Boolean,
        stats: MatchStats?,
    ): CatalogMatch? {
        val spoken = PreparedText(SpokenQuantity.productText(spokenText))
        val candidates = if (candidatesOnly) catalog.candidates(spoken) else null
        val wholeCatalog = candidates == null || candidates.isEmpty()
        stats?.let {
            it.windows++
            if (wholeCatalog) it.wholeCatalog++
        }
        val count = if (wholeCatalog) catalog.products.size else candidates!!.size
        var best = 0.0
        var bestProduct: PreparedProduct? = null
        for (n in 0 until count) {
            val prepared = catalog.products[if (wholeCatalog) n else candidates!![n]]
            // Products that cannot beat the best so far skip the edit distances.
            val score = components(spoken, prepared, 0.0, weights, floor = best)?.total ?: continue
            if (score > best) {
                best = score
                bestProduct = prepared
            }
        }
        return bestProduct?.let { CatalogMatch(it, best) }
    }

    /**
     * Rank the catalog against SEVERAL transcript hypotheses (recognizer N-best)
     * and keep each product's best score across them, so a garbled top guess
     * can't hide a correct lower-ranked one. With a single hypothesis this is
     * exactly [rank]; blank hypotheses are ignored.
     */
    fun rankAcross(
        hypotheses: List<String>,
        products: List<ProductEntity>,
        embeddingScores: Map<Long, Double> = emptyMap(),
        weights: ScoringWeights = ScoringWeights()
    ): List<RankedProduct> = rankAcross(hypotheses, prepare(products), embeddingScores, weights)

    /** [rankAcross] against a catalog prepared ahead, such as the whole local catalog. */
    fun rankAcross(
        hypotheses: List<String>,
        catalog: PreparedCatalog,
        embeddingScores: Map<Long, Double> = emptyMap(),
        weights: ScoringWeights = ScoringWeights()
    ): List<RankedProduct> {
        val bestByProduct = LinkedHashMap<Long, RankedProduct>()
        for (hypothesis in hypotheses) {
            if (hypothesis.isBlank()) continue
            for (ranked in rank(hypothesis, catalog, embeddingScores, weights)) {
                val current = bestByProduct[ranked.product.id]
                if (current == null || ranked.score > current.score) {
                    bestByProduct[ranked.product.id] = ranked
                }
            }
        }
        return bestByProduct.values.sortedByDescending { it.score }
    }

    private class Components(
        val nameLevenshtein: Double,
        val nameJaccard: Double,
        val nameToken: Double,
        val namePhonetic: Double,
        val namePrefix: Double,
        val containsBonus: Double,
        val numberSim: Double,
        val maxAliasScore: Double,
        val embeddingContribution: Double,
        val sizeAdjustment: Double,
        val total: Double,
    )

    private fun containsScore(spoken: String, text: String): Double = when {
        text == spoken -> 1.0
        spoken.contains(text) || text.contains(spoken) -> 0.5
        else -> 0.0
    }

    private fun nameScore(
        levenshtein: Double, jaccard: Double, token: Double, phonetic: Double, prefix: Double,
        contains: Double, number: Double, weights: ScoringWeights,
    ): Double = (
            levenshtein * weights.levenshtein +
                    jaccard * weights.jaccard +
                    token * weights.token +
                    phonetic * weights.phonetic +
                    prefix * weights.prefix +
                    contains * weights.containsBonus +
                    number * weights.numberSeq
            ) / 7.0

    private fun aliasScore(
        levenshtein: Double, jaccard: Double, token: Double, phonetic: Double, contains: Double,
        weights: ScoringWeights,
    ): Double = (levenshtein * weights.levenshtein +
            jaccard * weights.jaccard +
            token * weights.token +
            phonetic * weights.phonetic +
            contains * weights.containsBonus) / 5.0

    /**
     * Calculate comprehensive similarity score for a product.
     *
     * With a [floor], returns null when the product cannot score above it. The ceiling uses
     * the highest edit-distance similarity the string lengths allow, so it is never below the
     * real score, and the edit distances are only computed for products that can still win.
     */
    private fun components(
        spoken: PreparedText,
        product: PreparedProduct,
        embeddingSim: Double,
        weights: ScoringWeights,
        floor: Double? = null,
    ): Components? {
        val normalizedSpoken = spoken.text
        val name = product.name
        val productName = name.text

        // The cheap parts of the name score
        val nameJaccard = SimilarityEngine.jaccardOfBigrams(normalizedSpoken, productName, spoken.bigrams, name.bigrams)
        val nameToken = SimilarityEngine.tokenSetSimilarity(spoken.tokens, name.tokens)
        val namePrefix = SimilarityEngine.prefixSimilarity(normalizedSpoken, productName)
        // Check if product name contains/is contained in spoken text
        val containsBonus = containsScore(normalizedSpoken, productName)
        // Number similarity (if product has numbers)
        val numberSim = if (spoken.hasDigits || name.hasDigits) {
            SimilarityEngine.numberListSimilarity(spoken.numbers, name.numbers)
        } else {
            0.0
        }

        // Embedding contribution (0 when no cached vector / no model → regression-safe)
        val embeddingContribution = (embeddingSim * weights.embedding).coerceIn(0.0, 1.0)

        // A size said aloud is strong evidence: the same size in the name raises the product.
        // A different size does not lower it, because the recognizer often mishears a digit
        // and the nearest size is then still the best guess.
        val sizeAdjustment = sizeAgreement(spoken, name) * weights.size / 7.0

        if (floor != null) {
            var ceiling = maxOf(
                nameScore(
                    SimilarityEngine.levenshteinSimilarityCeiling(normalizedSpoken.length, productName.length),
                    nameJaccard, nameToken,
                    SimilarityEngine.levenshteinSimilarityCeiling(spoken.phoneticKey.length, name.phoneticKey.length),
                    namePrefix, containsBonus, numberSim, weights,
                ),
                embeddingContribution,
            )
            for (prepared in product.aliases) {
                if (ceiling + sizeAdjustment > floor) break
                ceiling = maxOf(
                    ceiling,
                    aliasScore(
                        SimilarityEngine.levenshteinSimilarityCeiling(normalizedSpoken.length, prepared.text.length),
                        SimilarityEngine.jaccardOfBigrams(normalizedSpoken, prepared.text, spoken.bigrams, prepared.bigrams),
                        SimilarityEngine.tokenSetSimilarity(spoken.tokens, prepared.tokens),
                        SimilarityEngine.levenshteinSimilarityCeiling(spoken.phoneticKey.length, prepared.phoneticKey.length),
                        containsScore(normalizedSpoken, prepared.text),
                        weights,
                    ),
                )
            }
            if ((ceiling + sizeAdjustment).coerceAtLeast(0.0) <= floor) return null
        }

        // Calculate scores against product name
        val nameLevenshtein = SimilarityEngine.levenshteinSimilarity(normalizedSpoken, productName)
        val namePhonetic = SimilarityEngine.levenshteinSimilarity(spoken.phoneticKey, name.phoneticKey)

        // Calculate max score from aliases
        var maxAliasScore: Double? = null
        for (prepared in product.aliases) {
            val alias = prepared.text
            val score = aliasScore(
                SimilarityEngine.levenshteinSimilarity(normalizedSpoken, alias),
                SimilarityEngine.jaccardOfBigrams(normalizedSpoken, alias, spoken.bigrams, prepared.bigrams),
                SimilarityEngine.tokenSetSimilarity(spoken.tokens, prepared.tokens),
                SimilarityEngine.levenshteinSimilarity(spoken.phoneticKey, prepared.phoneticKey),
                // Exact match bonus for aliases
                containsScore(normalizedSpoken, alias),
                weights,
            )
            maxAliasScore = maxAliasScore?.let { maxOf(it, score) } ?: score
        }

        // Weighted combination
        val nameTotal = nameScore(
            nameLevenshtein, nameJaccard, nameToken, namePhonetic, namePrefix, containsBonus,
            numberSim, weights,
        )

        // Take the best of fuzzy name score, alias score, or embedding similarity
        val aliasMax = maxAliasScore ?: 0.0
        val finalScore = (maxOf(nameTotal, aliasMax, embeddingContribution) + sizeAdjustment).coerceAtLeast(0.0)

        return Components(
            nameLevenshtein, nameJaccard, nameToken, namePhonetic, namePrefix, containsBonus,
            numberSim, aliasMax, embeddingContribution, sizeAdjustment, finalScore,
        )
    }

    /** 1 when the phrase and the name share a size, else 0. */
    private fun sizeAgreement(spoken: PreparedText, name: PreparedText): Double =
        if (spoken.sizes.any { it in name.sizes }) 1.0 else 0.0

    private fun calculateScore(
        spoken: PreparedText,
        product: PreparedProduct,
        embeddingSim: Double,
        weights: ScoringWeights
    ): Score {
        val c = requireNotNull(components(spoken, product, embeddingSim, weights))
        return Score(
            total = c.total,
            breakdown = mapOf(
                "levenshtein" to c.nameLevenshtein,
                "jaccard" to c.nameJaccard,
                "token" to c.nameToken,
                "phonetic" to c.namePhonetic,
                "prefix" to c.namePrefix,
                "contains" to c.containsBonus,
                "number" to c.numberSim,
                "aliasMax" to c.maxAliasScore,
                "embedding" to c.embeddingContribution,
                "size" to c.sizeAdjustment,
                "final" to c.total
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
    val numberSeq: Double = 1.0,    // Number sequence matching
    val embedding: Double = 1.0,    // Semantic embedding similarity
    val size: Double = 2.0          // The size said is the size in the name
)
