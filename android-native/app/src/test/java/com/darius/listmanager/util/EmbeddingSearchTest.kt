package com.darius.listmanager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingSearchTest {

    private val query = floatArrayOf(1f, 0f)
    private val candidates = listOf(
        7L to floatArrayOf(1f, 0f),
        8L to floatArrayOf(0.7f, 0.7f),
        9L to floatArrayOf(0f, 1f)
    )

    @Test
    fun topK_ordersByCosineDescending() {
        val result = EmbeddingSearch.topK(query, candidates, k = 3)
        assertEquals(listOf(7L, 8L, 9L), result.map { it.productId })
    }

    @Test
    fun topK_limitsToK() {
        val result = EmbeddingSearch.topK(query, candidates, k = 2)
        assertEquals(2, result.size)
        assertEquals(listOf(7L, 8L), result.map { it.productId })
    }

    @Test
    fun topK_emptyCandidates_returnsEmpty() {
        assertEquals(emptyList<Long>(), EmbeddingSearch.topK(query, emptyList(), k = 5).map { it.productId })
    }
}
