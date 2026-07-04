package com.darius.listmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VectorMathTest {

    @Test
    fun cosine_identicalVectors_isOne() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertEquals(1.0f, VectorMath.cosineSimilarity(v, v), 1e-5f)
    }

    @Test
    fun cosine_orthogonalVectors_isZero() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(a, b), 1e-5f)
    }

    @Test
    fun cosine_oppositeVectors_isNegativeOne() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(-1f, 0f)
        assertEquals(-1.0f, VectorMath.cosineSimilarity(a, b), 1e-5f)
    }

    @Test
    fun cosine_mismatchedOrEmpty_isZero() {
        assertEquals(0.0f, VectorMath.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f)), 1e-6f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(floatArrayOf(), floatArrayOf()), 1e-6f)
    }

    @Test
    fun bytes_roundTrip_preservesFloats() {
        val v = floatArrayOf(1.5f, -2.25f, 0f, 3.125f)
        val round = VectorMath.bytesToFloats(VectorMath.floatsToBytes(v))
        assertArrayEquals(v, round, 0f)
    }

    @Test
    fun l2Normalize_producesUnitLength() {
        val v = floatArrayOf(3f, 4f)
        val n = VectorMath.l2Normalize(v)
        assertEquals(1.0f, VectorMath.cosineSimilarity(n, n), 1e-5f)
        assertEquals(0.6f, n[0], 1e-5f)
        assertEquals(0.8f, n[1], 1e-5f)
    }
}
