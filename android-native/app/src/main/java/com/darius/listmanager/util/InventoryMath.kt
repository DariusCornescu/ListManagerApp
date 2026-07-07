package com.darius.listmanager.util

import kotlin.math.roundToLong

/**
 * Pure money math for inventory lists. Prices are stored in bani (integer
 * minor units) — never floating-point lei — so totals cannot drift.
 */
object InventoryMath {

    /** quantity x unit price, rounded to whole bani; null if either is missing. */
    fun lineValueBani(quantity: Double?, priceBani: Long?): Long? =
        if (quantity == null || priceBani == null) null
        else (quantity * priceBani).roundToLong()

    /** Sum of complete rows' line values (incomplete rows contribute 0). */
    fun totalBani(rows: List<Pair<Double?, Long?>>): Long =
        rows.sumOf { (q, p) -> lineValueBani(q, p) ?: 0L }

    /** 450 -> "4,50 lei" (Romanian comma decimals). */
    fun formatLei(bani: Long): String {
        val sign = if (bani < 0) "-" else ""
        val abs = kotlin.math.abs(bani)
        return "$sign${abs / 100},${(abs % 100).toString().padStart(2, '0')} lei"
    }

    /** 5.0 -> "5", 2.5 -> "2,5", null -> "". */
    fun formatQuantity(quantity: Double?): String {
        if (quantity == null) return ""
        return if (quantity == quantity.toLong().toDouble()) quantity.toLong().toString()
        else quantity.toString().replace('.', ',')
    }
}
