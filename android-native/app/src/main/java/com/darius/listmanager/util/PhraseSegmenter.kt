package com.darius.listmanager.util

/**
 * Splits a spoken phrase that may contain several products said in one breath
 * ("lalele pâine albă ouă") into individual product segments.
 *
 * The word-window scoring is injected via [scoreOf] (backed by the catalog
 * matcher at runtime), which keeps this object pure and unit-testable without a
 * database.
 */
object PhraseSegmenter {
    private val boundaryTokens = setOf("|", "si", "plus", "apoi", "iar", "dupa", "care")
    private val fillerTokens = setOf(
        "adaug", "adauga", "adaugam", "pune", "punem", "vreau", "as", "vrea",
        "dori", "doresc", "sa", "poti", "pui", "te", "rog", "mai", "inca",
        "avem", "am", "nevoie", "imi", "trebuie", "da-mi", "noteaza", "trece",
        "ia", "de", "bucata", "bucati", "cutie", "cutii", "bax", "baxuri",
        "sticla", "sticle", "kilogram", "kilograme", "kg", "litru", "litri",
    )

    private fun tokenize(text: String): List<String> = text
        .replace(Regex("[,;:\\n]+"), " | ")
        .trim()
        .split(Regex("\\s+"))
        .map { it.trim('.', '!', '?') }
        .filter { it.isNotBlank() }

    /**
     * Greedily segment [text]. At each position, evaluate windows of 1..[maxWindow]
     * words and pick the one with the highest [scoreOf] that clears [threshold];
     * on ties the longer window wins, so full product names ("pâine albă") beat
     * their prefixes ("pâine"). Consecutive words that match nothing are grouped
     * into a single leftover segment (resolved later, usually as Unknown).
     */
    fun segment(
        text: String,
        threshold: Double,
        maxWindow: Int = 4,
        scoreOf: (String) -> Double
    ): List<String> {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()

        val segments = mutableListOf<String>()
        val leftover = mutableListOf<String>()
        var quantityPrefix: String? = null
        var i = 0
        while (i < tokens.size) {
            if (quantityPrefix == null && SpokenQuantity.isQuantityToken(tokens[i])) {
                if (leftover.isNotEmpty()) {
                    segments.add(leftover.joinToString(" "))
                    leftover.clear()
                }
                quantityPrefix = tokens[i]
                i++
                continue
            }

            var bestLen = 0
            var bestScore = Double.NEGATIVE_INFINITY
            val maxLen = minOf(maxWindow, tokens.size - i)
            for (len in 1..maxLen) {
                val window = tokens.subList(i, i + len).joinToString(" ")
                val s = scoreOf(window)
                if (s > bestScore || (s == bestScore && len > bestLen)) {
                    bestScore = s
                    bestLen = len
                }
            }
            if (bestLen > 0 && bestScore >= threshold) {
                if (leftover.isNotEmpty()) {
                    segments.add(leftover.joinToString(" "))
                    leftover.clear()
                }
                val matched = tokens.subList(i, i + bestLen).joinToString(" ")
                segments.add(listOfNotNull(quantityPrefix, matched).joinToString(" "))
                quantityPrefix = null
                i += bestLen
            } else if (TextNorm.normalize(tokens[i]) in boundaryTokens) {
                if (leftover.isNotEmpty()) {
                    segments.add(leftover.joinToString(" "))
                    leftover.clear()
                }
                i++
            } else if (TextNorm.normalize(tokens[i]) in fillerTokens) {
                i++
            } else {
                quantityPrefix?.let {
                    leftover.add(it)
                    quantityPrefix = null
                }
                leftover.add(tokens[i])
                i++
            }
        }
        quantityPrefix?.let { leftover.add(it) }
        if (leftover.isNotEmpty()) segments.add(leftover.joinToString(" "))
        return segments
    }
}
