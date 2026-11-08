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

    /** Like [scorer], and a product followed by a size still scores [withSize]. */
    private fun sizedScorer(withSize: Double, vararg products: String): (String) -> Double = { window ->
        val exact = scorer(*products)(window)
        val words = window.trim().split(" ")
        if (exact > 0.0 || words.size < 2 || !PhraseSegmenter.isDimension(words.last())) exact
        else if (scorer(*products)(words.dropLast(1).joinToString(" ")) > 0.0) withSize else 0.0
    }

    @Test
    fun aSizeStaysWithItsProductAndIsNotACount() {
        val segments = PhraseSegmenter.segment(
            "cameră nova 20 * 175 șurub 3,5 * 35",
            threshold = 0.6,
            maxWindow = 7,
            scoreOf = sizedScorer(0.7, "cameră nova", "șurub"),
        )

        assertEquals(listOf("cameră nova 20x175", "șurub 3.5x35"), segments)
        assertEquals(listOf(1, 1), segments.map(SpokenQuantity::from))
    }

    @Test
    fun aSizeSaidWithPeXOrSlashIsOneToken() {
        val segments = PhraseSegmenter.segment(
            "două șurub 6 pe 100 cutii 40 x 30 x 20 cuie 3,5/35",
            threshold = 0.6,
            maxWindow = 7,
            scoreOf = sizedScorer(0.7, "șurub", "cutii", "cuie"),
        )

        assertEquals(listOf("două șurub 6x100", "cutii 40x30x20", "cuie 3.5x35"), segments)
        assertEquals(listOf(2, 1, 1), segments.map(SpokenQuantity::from))
    }

    @Test
    fun thePeThatIntroducesASizeIsDropped() {
        val segments = PhraseSegmenter.segment(
            "roată zenit pe 26 pe 175 ceai",
            threshold = 0.6,
            maxWindow = 7,
            scoreOf = sizedScorer(0.7, "roată zenit", "ceai"),
        )

        assertEquals(listOf("roată zenit 26x175", "ceai"), segments)
    }

    @Test
    fun aPeThatIsNotBeforeASizeIsKept() {
        val segments = PhraseSegmenter.segment(
            "husă pe 2 locuri",
            threshold = 0.6,
            scoreOf = scorer("husă pe 2 locuri"),
        )

        assertEquals(listOf("husă pe 2 locuri"), segments)
    }

    @Test
    fun aMisheardSizeDoesNotPullItsProductUnderTheThreshold() {
        val segments = PhraseSegmenter.segment(
            "cameră nova 26/1751",
            threshold = 0.6,
            maxWindow = 7,
            scoreOf = sizedScorer(0.4, "cameră nova"),
        )

        assertEquals(listOf("cameră nova", "26x1751"), segments)
    }

    @Test
    fun aCompoundCount_staysWithItsProduct() {
        val catalog = setOf("oua", "cutii", "lapte")
        val segments = PhraseSegmenter.segment(
            text = "douazeci si cinci de cutii si doua sute de oua",
            threshold = 0.62,
            maxWindow = 7,
        ) { window -> if (TextNorm.normalize(window) in catalog) 1.0 else 0.0 }

        assertEquals(listOf(25, 200), segments.map(SpokenQuantity::from))
        assertEquals(listOf("cutii", "oua"), segments.map(SpokenQuantity::productText))
    }
}
