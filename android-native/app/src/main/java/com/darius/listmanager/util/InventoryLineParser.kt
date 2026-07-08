package com.darius.listmanager.util

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Result of parsing one spoken inventory line ("lapte zuzu 5 4 lei 50").
 * Missing/unparseable fields are null; the UI shows them blank for manual fill.
 */
data class ParsedInventoryLine(
    val nameText: String,
    val quantity: Double?,
    val priceBani: Long?
)

/**
 * Pure parser for spoken inventory lines in the fixed order
 * product -> quantity -> price (Romanian speech-recognizer output).
 *
 * Only the TRAILING numeric region of the line is interpreted as qty/price;
 * tokens like "2l" or "1.5%" are not pure numbers, so numbers inside product
 * names never leak into the amounts. First trailing amount = quantity, second
 * = price; a single amount carrying a money marker (lei/bani) is the price.
 * Number WORDS ("cinci") are out of scope: they break the trailing region and
 * can misattribute a later digit (e.g. "lapte cinci lei 50" -> qty 50) — the
 * row stays editable, so the operator corrects it; this is pinned by tests.
 *
 * With a [nameScorer] (catalog match score in 0..1), the split becomes
 * catalog-aware: the name may swallow leading tokens of the numeric region
 * when a longer candidate matches the catalog with high confidence, so names
 * ending in a bare number ("cuie de 5") keep it instead of losing it to the
 * quantity. Without a scorer (or no confident match) behavior is unchanged.
 */
object InventoryLineParser {

    private val FILLER = setOf("de", "la", "buc", "bucata", "bucată", "bucati", "bucăți")
    private val CURRENCY = setOf("lei", "leu", "ron")
    private val SUBUNIT = setOf("bani", "ban")
    private val COMMA_WORD = setOf("virgula", "virgulă")

    private val NUMBER = Regex("^\\d+([.,]\\d+)?$")

    /** Minimum catalog score for a longer name candidate to override the default split. */
    private const val CATALOG_OVERRIDE_THRESHOLD = 0.82

    private data class Amount(val value: Double, val money: Boolean)

    fun parse(text: String, nameScorer: ((String) -> Double)? = null): ParsedInventoryLine? {
        val tokens = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        // 1. Trailing numeric region: scan from the end while tokens are pure
        //    numbers or known money/filler words.
        var start = tokens.size
        for (i in tokens.indices.reversed()) {
            val t = tokens[i]
            if (isNumber(t) || t in FILLER || t in CURRENCY || t in SUBUNIT || t in COMMA_WORD) {
                start = i
            } else break
        }
        if (tokens.subList(start, tokens.size).none { isNumber(it) }) {
            // no numbers at the end -> the whole line is a product name
            return ParsedInventoryLine(tokens.joinToString(" "), quantity = null, priceBani = null)
        }

        // 1b. Catalog-aware override: let the name grow into the numeric region
        //     ("cuie" -> "cuie de 5") when a candidate matches the catalog with
        //     high confidence. Ties prefer the longer name.
        var splitAt = start
        if (nameScorer != null && start < tokens.size) {
            var bestScore = 0.0
            var bestAt = -1
            for (i in start..tokens.size) {
                val candidate = tokens.subList(0, i).joinToString(" ")
                if (candidate.isBlank()) continue
                val score = nameScorer(candidate)
                if (score >= bestScore) {
                    bestScore = score
                    bestAt = i
                }
            }
            if (bestScore >= CATALOG_OVERRIDE_THRESHOLD && bestAt > start) {
                splitAt = bestAt
            }
        }

        val region = tokens.subList(splitAt, tokens.size)
        val nameText = tokens.subList(0, splitAt).joinToString(" ")
        if (region.none { isNumber(it) }) {
            // the catalog consumed every trailing number ("cuie de 5" alone)
            return ParsedInventoryLine(nameText, quantity = null, priceBani = null)
        }

        // 2. Collapse the region into amounts (left to right).
        val amounts = mutableListOf<Amount>()
        var i = 0
        while (i < region.size) {
            val t = region[i]
            if (!isNumber(t)) { i++; continue }
            var value = numberOf(t)
            var money = false
            var consumed = 1
            val next = region.getOrNull(i + 1)
            val next2 = region.getOrNull(i + 2)
            val next3 = region.getOrNull(i + 3)
            when {
                // "4 virgulă 50" -> 4.50
                next != null && next in COMMA_WORD && next2 != null && isNumber(next2) -> {
                    value += fractionOf(next2)
                    consumed = 3
                }
                // "4 lei 50 (bani)?" -> 4.50
                next != null && next in CURRENCY && next2 != null && isNumber(next2) -> {
                    value += numberOf(next2) / 100.0
                    money = true
                    consumed = if (next3 != null && next3 in SUBUNIT) 4 else 3
                }
                // "4 lei" -> 4.00
                next != null && next in CURRENCY -> { money = true; consumed = 2 }
                // "50 bani" -> 0.50
                next != null && next in SUBUNIT -> { value /= 100.0; money = true; consumed = 2 }
            }
            amounts.add(Amount(value, money))
            i += consumed
        }

        // 3. Fixed order: first = quantity, second = price. A single
        //    money-marked amount is the price (qty missing).
        val quantity: Double?
        val priceLei: Double?
        when {
            amounts.size >= 2 -> { quantity = amounts[0].value; priceLei = amounts[1].value }
            amounts.size == 1 && amounts[0].money -> { quantity = null; priceLei = amounts[0].value }
            amounts.size == 1 -> { quantity = amounts[0].value; priceLei = null }
            else -> { quantity = null; priceLei = null }
        }

        return ParsedInventoryLine(
            nameText = nameText,
            quantity = quantity,
            priceBani = priceLei?.let { (it * 100).roundToLong() }
        )
    }

    private fun isNumber(t: String) = NUMBER.matches(t)

    private fun numberOf(t: String) = t.replace(',', '.').toDouble()

    /** "50" -> 0.50, "5" -> 0.5, "05" -> 0.05 (decimal part spoken after "virgulă"). */
    private fun fractionOf(t: String): Double = t.toDouble() / 10.0.pow(t.length)
}
