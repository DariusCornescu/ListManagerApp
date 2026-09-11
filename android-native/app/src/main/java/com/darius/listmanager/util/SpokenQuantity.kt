package com.darius.listmanager.util

/** Extracts a conservative quantity from the start of a spoken product phrase. */
object SpokenQuantity {
    private val wordValues = mapOf(
        "un" to 1,
        "una" to 1,
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
        "zece" to 10,
    )

    fun from(spokenText: String): Int {
        val normalized = TextNorm.normalize(spokenText)
        if (normalized.isEmpty()) return 1

        wordValues[normalized.substringBefore(' ')]?.let { return it }

        val numeric = Regex("^(\\d{1,10})(?:\\s+|x\\s+)")
            .find(normalized)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: return 1
        return numeric.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: 1
    }

    fun isQuantityToken(token: String): Boolean {
        val normalized = TextNorm.normalize(token)
        return normalized in wordValues || normalized.toLongOrNull()?.let { it > 0 } == true
    }
}
