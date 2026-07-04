package com.darius.listmanager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseSegmenterTest {

    /** Fake catalog: any window equal (case-insensitive) to a known product scores 1.0. */
    private fun scorer(vararg products: String): (String) -> Double = { window ->
        val n = window.lowercase().trim()
        if (products.any { it.lowercase() == n }) 1.0 else 0.0
    }

    @Test
    fun splitsMultipleProductsSaidInOneBreath() {
        val out = PhraseSegmenter.segment(
            "lalele pâine albă ouă",
            threshold = 0.6,
            scoreOf = scorer("lalele", "pâine albă", "ouă")
        )
        assertEquals(listOf("lalele", "pâine albă", "ouă"), out)
    }

    @Test
    fun prefersLongerProductNameOverPrefix() {
        val out = PhraseSegmenter.segment(
            "pâine albă",
            threshold = 0.6,
            scoreOf = scorer("pâine", "pâine albă")
        )
        assertEquals(listOf("pâine albă"), out)
    }

    @Test
    fun singleProduct_returnsOneSegment() {
        val out = PhraseSegmenter.segment("lapte", threshold = 0.6, scoreOf = scorer("lapte"))
        assertEquals(listOf("lapte"), out)
    }

    @Test
    fun unmatchedWordsBetweenProductsGroupedAsLeftover() {
        val out = PhraseSegmenter.segment(
            "lapte xyz abc",
            threshold = 0.6,
            scoreOf = scorer("lapte")
        )
        assertEquals(listOf("lapte", "xyz abc"), out)
    }

    @Test
    fun nothingMatches_wholePhraseIsOneSegment() {
        val out = PhraseSegmenter.segment("xyz abc", threshold = 0.6, scoreOf = scorer("lapte"))
        assertEquals(listOf("xyz abc"), out)
    }

    @Test
    fun blankInput_returnsEmpty() {
        assertEquals(emptyList<String>(), PhraseSegmenter.segment("   ", threshold = 0.6, scoreOf = scorer()))
    }
}
