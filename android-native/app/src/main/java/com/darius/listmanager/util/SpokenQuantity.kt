package com.darius.listmanager.util

/** Extracts a conservative quantity from the start of a spoken product phrase. */
object SpokenQuantity {
    private val units = mapOf(
        "un" to 1,
        "una" to 1,
        "unu" to 1,
        "o" to 1,
        "doi" to 2,
        "doua" to 2,
        "trei" to 3,
        "patru" to 4,
        "cinci" to 5,
        "sase" to 6,
        "sapte" to 7,
        "opt" to 8,
        "noua" to 9,
    )

    // Both the written forms and the everyday spoken ones the recognizer may return.
    private val tensAndTeens = mapOf(
        "zece" to 10,
        "unsprezece" to 11, "unspe" to 11,
        "doisprezece" to 12, "douasprezece" to 12, "doispe" to 12,
        "treisprezece" to 13, "treispe" to 13,
        "paisprezece" to 14, "patrusprezece" to 14, "paispe" to 14,
        "cincisprezece" to 15, "cinsprezece" to 15, "cinspe" to 15,
        "saisprezece" to 16, "saispe" to 16,
        "saptesprezece" to 17, "saptispe" to 17,
        "optsprezece" to 18, "optspe" to 18,
        "nouasprezece" to 19, "nouaspe" to 19,
        "douazeci" to 20,
        "treizeci" to 30,
        "patruzeci" to 40,
        "cincizeci" to 50,
        "saizeci" to 60,
        "saptezeci" to 70,
        "optzeci" to 80,
        "nouazeci" to 90,
    )

    private val roundTens = setOf(20, 30, 40, 50, 60, 70, 80, 90)
    private val hundredWords = setOf("suta", "sute")

    /** "o" and "un" are also articles, and "noua" also means "new". */
    private val ambiguousWords = setOf("un", "una", "o", "noua")

    private fun isNumberWord(token: String) = token in units || token in tensAndTeens

    /**
     * Reads a number spoken in words from the start of normalized [tokens], returning its
     * value and how many tokens it used: "douazeci si cinci" is (25, 3), "doua sute" (200, 2).
     */
    private fun parseWords(tokens: List<String>): Pair<Int, Int>? {
        var at = 0
        var total = 0

        val hundreds = units[tokens.getOrNull(at)]
        if (hundreds != null && tokens.getOrNull(at + 1) in hundredWords) {
            total = hundreds * 100
            at += 2
            if (tokens.getOrNull(at) == "si") at++
        }

        val tens = tensAndTeens[tokens.getOrNull(at)]
        val unit = units[tokens.getOrNull(at)]
        when {
            tens != null -> {
                total += tens
                at++
                val following = units[tokens.getOrNull(at + 1)]
                if (tens in roundTens && tokens.getOrNull(at) == "si" && following != null) {
                    total += following
                    at += 2
                }
            }
            unit != null -> {
                total += unit
                at++
            }
        }
        // "doua sute si" with nothing countable after it: leave "si" for the segmenter.
        if (at > 0 && tokens.getOrNull(at - 1) == "si") at--
        return if (at == 0) null else total to at
    }

    /** The leading count, however it was said, as (value, tokens used). */
    private fun parseLeading(tokens: List<String>): Pair<Int, Int>? {
        parseWords(tokens)?.let { return it }
        val numeric = tokens.firstOrNull()
            ?.let { Regex("^(\\d{1,10})x?$").matchEntire(it) }
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: return null
        return numeric.takeIf { it in 1..Int.MAX_VALUE }?.let { it.toInt() to 1 }
    }

    private fun tokensOf(spokenText: String): List<String> =
        TextNorm.normalize(spokenText).split(' ').filter { it.isNotBlank() }

    fun from(spokenText: String): Int {
        val tokens = tokensOf(spokenText)
        // A lone number has no product to count, except the words already treated as counts.
        if (tokens.size < 2 && tokens.firstOrNull()?.all(Char::isDigit) == true) return 1
        return parseLeading(tokens)?.first ?: 1
    }

    fun isQuantityToken(token: String): Boolean {
        val normalized = TextNorm.normalize(token)
        return isNumberWord(normalized) || normalized.toLongOrNull()?.let { it > 0 } == true
    }

    /** How many of [tokens], from the start, form one spoken count. At least 1 for a quantity token. */
    fun leadingTokenCount(tokens: List<String>): Int =
        parseLeading(tokens.map(TextNorm::normalize))?.second ?: 0

    /** Removes a valid leading count so matching only sees the product words. */
    fun productText(spokenText: String): String {
        val normalized = TextNorm.normalize(spokenText)
        val tokens = normalized.split(' ').filter { it.isNotBlank() }
        val (_, used) = parseLeading(tokens) ?: return normalized

        // "douazeci de oua": "de" joins the count to the product and is not part of either.
        val rest = tokens.drop(used).let { if (it.firstOrNull() == "de") it.drop(1) else it }
        return if (rest.isEmpty()) normalized else rest.joinToString(" ")
    }

    fun hasExplicitPrefix(spokenText: String): Boolean {
        val normalized = TextNorm.normalize(spokenText)
        return normalized.isNotEmpty() && productText(normalized) != normalized
    }

    /**
     * For lines read left to right after a product name, where "o", "un" and "noua" are too
     * ambiguous to be a number ("pâine nouă"). Returns null when [tokens] does not start with
     * an unambiguous number word.
     */
    internal fun unambiguousNumberWords(tokens: List<String>): Pair<Int, Int>? {
        val first = tokens.firstOrNull() ?: return null
        if (first in ambiguousWords && tokens.getOrNull(1) !in hundredWords) return null
        return parseWords(tokens)
    }
}
