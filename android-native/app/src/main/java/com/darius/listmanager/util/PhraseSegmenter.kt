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

    // "3,5" is a decimal, not two items.
    private val DECIMAL_COMMA = Regex("(?<=\\d),(?=\\d)")
    // "20 * 175", "4 x 40", "6 pe 100", "40 x 30 x 20": a size, written as one token so its
    // numbers are not read as counts. The recognizer may also write "pe" as "/" ("3,5/35").
    private val DIMENSION = Regex(
        "\\d+(?:\\.\\d+)?(?:\\s*(?:[x×*/]|\\bpe\\b)\\s*\\d+(?:\\.\\d+)?)+",
        RegexOption.IGNORE_CASE,
    )
    private val DIMENSION_SEPARATOR = Regex("\\s*(?:[x×*/]|\\bpe\\b)\\s*", RegexOption.IGNORE_CASE)
    private val DIMENSION_TOKEN = Regex("^\\d+(?:\\.\\d+)?(?:x\\d+(?:\\.\\d+)?)+$")
    // "cameră pe 26 pe 175": the first "pe" introduces the size and is not part of any name.
    private val PE_BEFORE_SIZE = Regex("\\bpe\\s+(?=\\d+(?:\\.\\d+)?x\\d)", RegexOption.IGNORE_CASE)
    private val LIST_SEPARATORS = Regex("[,;:\\n]+")
    private val WHITESPACE = Regex("\\s+")

    private fun joinDimensions(text: String): String =
        DIMENSION.replace(text) { it.value.split(DIMENSION_SEPARATOR).joinToString("x") }

    private fun tokenize(text: String): List<String> = joinDimensions(text.replace(DECIMAL_COMMA, "."))
        .replace(PE_BEFORE_SIZE, "")
        .replace(LIST_SEPARATORS, " | ")
        .trim()
        .split(WHITESPACE)
        .map { it.trim('.', '!', '?') }
        .filter { it.isNotBlank() }

    internal fun isDimension(token: String): Boolean = DIMENSION_TOKEN.matches(token)

    private fun isPlainWord(token: String): Boolean = token.length >= 3 && token.all { it.isLetter() }

    /**
     * Greedily segment [text]. At each position, evaluate windows of 1..[maxWindow]
     * words and pick the one with the highest [scoreOf] that clears [threshold];
     * on ties the longer window wins, so full product names ("pâine albă") beat
     * their prefixes ("pâine"). Consecutive words that match nothing are grouped
     * into a single leftover segment (resolved later, usually as Unknown).
     *
     * [strays] tells whether a word at the edge of a window rides along without being part of
     * its match: the window's product does not contain the word, and the [rest] of the window
     * is matched by that same product. Such a word is left out when that helps: at the start
     * when unmatched words come right before it and the rest scores higher on its own
     * ("perie sârmă săpun vase" must not take "sârmă" into "săpun vase"), at the end when the
     * word starts a product of its own ("cui" in "roată 26x175 cui 3x35").
     */
    fun segment(
        text: String,
        threshold: Double,
        maxWindow: Int = 4,
        strays: (window: String, word: String, rest: String) -> Boolean = { _, _, _ -> false },
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
                // A count may be several words: "douazeci si cinci", "doua sute".
                val used = SpokenQuantity.leadingTokenCount(tokens.subList(i, tokens.size)).coerceAtLeast(1)
                quantityPrefix = tokens.subList(i, i + used).joinToString(" ")
                i += used
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
            fun window(from: Int, len: Int) = tokens.subList(from, from + len).joinToString(" ")
            // A word that only rides along in front of a better match belongs with the
            // unmatched words before it ("perie | sârmă săpun vase" -> "perie sârmă"). Codes and
            // two-letter bits are left alone: they name one variant even when a sibling wins.
            val strayFirstWord = leftover.isNotEmpty() && bestLen > 1 && isPlainWord(tokens[i]) &&
                scoreOf(window(i + 1, bestLen - 1)) > bestScore &&
                strays(window(i, bestLen), tokens[i], window(i + 1, bestLen - 1))
            if (bestLen > 0 && bestScore >= threshold && !strayFirstWord) {
                // A last word that is not part of the product and starts one of its own goes to it.
                while (bestLen > 1) {
                    val last = i + bestLen - 1
                    if (isDimension(tokens[last]) || scoreOf(window(i, bestLen - 1)) < threshold) break
                    if (!strays(window(i, bestLen), tokens[last], window(i, bestLen - 1))) break
                    // ...really starts it, not only rides along in front of it.
                    val startsAProduct = (1..minOf(maxWindow, tokens.size - last)).any { len ->
                        scoreOf(window(last, len)) >= threshold &&
                            (len == 1 || !strays(window(last, len), tokens[last], window(last + 1, len - 1)))
                    }
                    if (!startsAProduct) break
                    bestLen--
                }
                if (leftover.isNotEmpty()) {
                    segments.add(leftover.joinToString(" "))
                    leftover.clear()
                }
                // A size right after a product describes that product ("cameră 26x1.75"),
                // unless a misheard size would push the match under the threshold.
                if (i + bestLen < tokens.size && isDimension(tokens[i + bestLen])) {
                    val withSize = tokens.subList(i, i + bestLen + 1).joinToString(" ")
                    if (scoreOf(withSize) >= threshold) bestLen++
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
