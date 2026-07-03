package com.darius.listmanager.util

import com.darius.listmanager.data.local.entity.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductRankerEmbeddingTest {

    private val orange = ProductEntity(id = 1L, name = "suc portocale", distributorId = 1L, aliases = "orange juice")
    private val water = ProductEntity(id = 2L, name = "apa plata", distributorId = 1L, aliases = "")
    private val products = listOf(orange, water)

    @Test
    fun emptyEmbeddingScores_reproducesBaselineOrder() {
        val baseline = ProductRanker.rank("apa plata", products)
        val withEmptyMap = ProductRanker.rank("apa plata", products, emptyMap())
        assertEquals(
            baseline.map { it.product.id to it.score },
            withEmptyMap.map { it.product.id to it.score }
        )
    }

    @Test
    fun strongEmbeddingScore_raisesSemanticMatch() {
        val scores = mapOf(1L to 0.9, 2L to 0.05)
        val ranked = ProductRanker.rank("orange juice drink", products, scores)
        assertEquals("orange product should rank first", 1L, ranked.first().product.id)
        assertTrue("embedding should lift its score", ranked.first().score >= 0.9 - 1e-6)
    }
}
