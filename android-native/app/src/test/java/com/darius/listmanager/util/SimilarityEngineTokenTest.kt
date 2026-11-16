package com.darius.listmanager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SimilarityEngineTokenTest {
    private fun token(a: String, b: String) = SimilarityEngine.tokenSimilarity(a, b)

    @Test
    fun exactTokenOverlapIsUnchangedJaccard() {
        assertEquals(1.0, token("lapte zuzu", "lapte zuzu"), 1e-9)
        assertEquals(0.5, token("lapte", "lapte zuzu"), 1e-9)
        assertEquals(1.0 / 3, token("lapte dorna", "lapte zuzu"), 1e-9)
        assertEquals(0.0, token("computer", "lapte"), 1e-9)
    }

    @Test
    fun oneSlipOrPluralEndingEarnsItsEditSimilarity() {
        // a lone pair matched with credit s scores s / (1 + 1 - s), the same Jaccard as below
        fun lone(s: Double) = s / (2 - s)
        assertEquals(lone(6.0 / 7), token("cartafi", "cartofi"), 1e-9)
        assertEquals(lone(0.75), token("meri", "mere"), 1e-9)
        assertEquals(lone(0.8), token("paini", "paine"), 1e-9)
    }

    @Test
    fun aTokenInsideTheOtherEarnsNoFuzzyCredit() {
        // the ranker's contains bonus already rewards this; counting it twice auto-added
        // Coca Cola for a garbled "ciocan" in the synthetic benchmark
        assertEquals(0.0, token("icoca", "coca"), 1e-9)
        assertEquals(0.0, token("lapt", "lapte"), 1e-9)
    }

    @Test
    fun fuzzyCreditJoinsExactMatchesInTheSameJaccard() {
        // "paini" ~ "paine" (0.8) plus exact "alba": 1.8 matched over 2 + 2 - 1.8 = 2.2
        assertEquals(1.8 / 2.2, token("paini alba", "paine alba"), 1e-9)
    }

    @Test
    fun distantOrShortTokensEarnNothing() {
        assertEquals("below 0.75 edit similarity", 0.0, token("mar", "mere"), 1e-9)
        assertEquals("three letters must match exactly", 0.0, token("vin", "vit"), 1e-9)
        assertEquals(0.0, token("detergent", "dorna"), 1e-9)
    }

    @Test
    fun eachTokenIsCreditedOnlyOnce() {
        // both spoken tokens are near "mere", but the product has one "mere"
        assertEquals(0.75 / (2 + 1 - 0.75), token("meri mera", "mere"), 1e-9)
    }
}
