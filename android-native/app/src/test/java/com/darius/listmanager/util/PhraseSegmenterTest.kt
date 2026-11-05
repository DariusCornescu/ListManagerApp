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
    fun leadingQuantityStaysWithTheFollowingProductName() {
        val out = PhraseSegmenter.segment(
            "două lapte Zuzu",
            threshold = 0.6,
            scoreOf = scorer("lapte", "lapte Zuzu", "Zuzu")
        )
        assertEquals(listOf("două lapte Zuzu"), out)
    }

    @Test
    fun eachLeadingQuantityStaysWithItsProduct() {
        val out = PhraseSegmenter.segment(
            "două lapte trei ouă",
            threshold = 0.6,
            scoreOf = scorer("lapte", "ouă")
        )
        assertEquals(listOf("două lapte", "trei ouă"), out)
    }

    @Test
    fun naturalConnectorsAndCommandsDoNotBecomeUnknownProducts() {
        val out = PhraseSegmenter.segment(
            "te rog pune două lapte Zuzu și trei ouă, apoi adaugă o pâine albă plus patru ape",
            threshold = 0.6,
            maxWindow = 7,
            scoreOf = scorer("lapte Zuzu", "ouă", "pâine albă", "ape")
        )
        assertEquals(
            listOf("două lapte Zuzu", "trei ouă", "o pâine albă", "patru ape"),
            out,
        )
    }

    @Test
    fun conversationalRequestAndCountUnitsAreRemovedAroundProducts() {
        val out = PhraseSegmenter.segment(
            "aș vrea să adaug două baxuri de apă și trei bucăți de săpun",
            threshold = 0.6,
            scoreOf = scorer("apă", "săpun")
        )
        assertEquals(listOf("două apă", "trei săpun"), out)
    }

    @Test
    fun connectorInsideCatalogProductNameIsPreserved() {
        val out = PhraseSegmenter.segment(
            "sare și piper apoi ouă",
            threshold = 0.6,
            scoreOf = scorer("sare și piper", "ouă")
        )
        assertEquals(listOf("sare și piper", "ouă"), out)
    }

    @Test
    fun punctuationSeparatesProductsAndIsNotKeptInTheirNames() {
        val out = PhraseSegmenter.segment(
            "lapte; ouă, pâine!",
            threshold = 0.6,
            scoreOf = scorer("lapte", "ouă", "pâine")
        )
        assertEquals(listOf("lapte", "ouă", "pâine"), out)
    }

    @Test
    fun catalogNamesCanUseSevenWordWindows() {
        val longName = "detergent lichid pentru rufe colorate parfum floral"
        val out = PhraseSegmenter.segment(
            "$longName ouă",
            threshold = 0.6,
            maxWindow = 7,
            scoreOf = scorer(longName, "ouă")
        )
        assertEquals(listOf(longName, "ouă"), out)
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
