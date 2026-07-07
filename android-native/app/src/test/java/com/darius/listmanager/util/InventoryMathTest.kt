package com.darius.listmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InventoryMathTest {

    @Test
    fun lineValue_basic() {
        assertEquals(2250L, InventoryMath.lineValueBani(5.0, 450L))
    }

    @Test
    fun lineValue_decimalQuantityRounds() {
        assertEquals(1125L, InventoryMath.lineValueBani(2.5, 450L))
        assertEquals(1667L, InventoryMath.lineValueBani(3.7037, 450L)) // 1666.665 -> 1667
    }

    @Test
    fun lineValue_missingFieldIsNull() {
        assertNull(InventoryMath.lineValueBani(null, 450L))
        assertNull(InventoryMath.lineValueBani(5.0, null))
    }

    @Test
    fun total_skipsIncompleteRows() {
        val total = InventoryMath.totalBani(
            listOf(
                Triple("a", 2.0, 100L),   // 200
                Triple("b", null, 500L),  // skipped
                Triple("c", 3.0, null),   // skipped
                Triple("d", 1.5, 200L)    // 300
            ).map { (_, q, p) -> q to p }
        )
        assertEquals(500L, total)
    }

    @Test
    fun formatLei() {
        assertEquals("4,50 lei", InventoryMath.formatLei(450L))
        assertEquals("0,05 lei", InventoryMath.formatLei(5L))
        assertEquals("0,00 lei", InventoryMath.formatLei(0L))
        assertEquals("12345,67 lei", InventoryMath.formatLei(1234567L))
    }

    @Test
    fun formatQuantity() {
        assertEquals("5", InventoryMath.formatQuantity(5.0))
        assertEquals("2,5", InventoryMath.formatQuantity(2.5))
        assertEquals("", InventoryMath.formatQuantity(null))
    }
}
