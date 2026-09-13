package com.darius.listmanager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryVariantsQuantityTest {
    @Test
    fun ftsVariantsDescribeTheProductInsteadOfItsRequestedCount() {
        val variants = QueryVariants.generate("două lapte Zuzu")

        assertTrue("lapte zuzu" in variants)
        assertTrue("lapte" in variants)
        assertTrue("zuzu" in variants)
        assertFalse(variants.any { it == "doua" || it.startsWith("doua ") })
    }
}
