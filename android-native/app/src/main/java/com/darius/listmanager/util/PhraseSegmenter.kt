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
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        val segments = mutableListOf<String>()
        val leftover = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
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
                segments.add(tokens.subList(i, i + bestLen).joinToString(" "))
                i += bestLen
            } else {
                leftover.add(tokens[i])
                i++
            }
        }
        if (leftover.isNotEmpty()) segments.add(leftover.joinToString(" "))
        return segments
    }
}
