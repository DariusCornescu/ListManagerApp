package com.darius.listmanager.util

import com.darius.listmanager.data.local.entity.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N-best matching: the recognizer returns several transcript hypotheses per
 * utterance. [ProductRanker.rankAcross] ranks the catalog against all of them
 * and keeps each product's best score, so a garbled top guess can't hide a
 * correct lower-ranked one.
 */
class ProductRankerNBestTest {

    private val paine = ProductEntity(id = 1L, name = "pâine albă", distributorId = 1L, aliases = "")
    private val lapte = ProductEntity(id = 2L, name = "lapte", distributorId = 1L, aliases = "")
    private val products = listOf(paine, lapte)

    @Test
    fun singleHypothesis_matchesPlainRank() {
        val across = ProductRanker.rankAcross(listOf("paine alba"), products)
        val plain = ProductRanker.rank("paine alba", products)
        assertEquals(
            plain.map { it.product.id to it.score },
            across.map { it.product.id to it.score }
        )
    }

    @Test
    fun betterAlternativeWins_whenTopHypothesisIsGarbled() {
        val garbledOnly = ProductRanker.rank("pole alba", products).first()
        val goodOnly = ProductRanker.rank("paine alba", products).first()
        val across = ProductRanker.rankAcross(listOf("pole alba", "paine alba"), products).first()

        // The correct product surfaces at the better hypothesis's score —
        // strictly better than trusting only the (garbled) top guess.
        assertEquals(1L, across.product.id)
        assertEquals(goodOnly.score, across.score, 1e-9)
        assertTrue(across.score > garbledOnly.score)
    }

    @Test
    fun keepsMaxScorePerProduct_acrossHypotheses() {
        // Each product scores highest under a different hypothesis; rankAcross
        // must keep each product's best, not any single hypothesis's ranking.
        val across = ProductRanker.rankAcross(listOf("paine alba", "lapte"), products)
        val byId = across.associateBy { it.product.id }
        assertEquals(
            ProductRanker.rank("paine alba", products).first { it.product.id == 1L }.score,
            byId.getValue(1L).score, 1e-9
        )
        assertEquals(
            ProductRanker.rank("lapte", products).first { it.product.id == 2L }.score,
            byId.getValue(2L).score, 1e-9
        )
    }

    @Test
    fun blankHypotheses_yieldEmpty() {
        assertTrue(ProductRanker.rankAcross(emptyList(), products).isEmpty())
        assertTrue(ProductRanker.rankAcross(listOf("", "   "), products).isEmpty())
    }

    @Test
    fun resultsSortedByScoreDescending() {
        val across = ProductRanker.rankAcross(listOf("paine alba", "lapte"), products)
        val scores = across.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }
}
