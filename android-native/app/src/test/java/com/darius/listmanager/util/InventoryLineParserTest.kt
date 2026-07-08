package com.darius.listmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InventoryLineParserTest {

    @Test
    fun fullLine_nameQtyLeiBaniPrice() {
        val p = InventoryLineParser.parse("lapte zuzu 5 4 lei 50")!!
        assertEquals("lapte zuzu", p.nameText)
        assertEquals(5.0, p.quantity!!, 0.0)
        assertEquals(450L, p.priceBani)
    }

    @Test
    fun decimalPriceWithComma() {
        val p = InventoryLineParser.parse("pâine 3 2,50")!!
        assertEquals("pâine", p.nameText)
        assertEquals(3.0, p.quantity!!, 0.0)
        assertEquals(250L, p.priceBani)
    }

    @Test
    fun decimalPriceWithDot() {
        val p = InventoryLineParser.parse("pâine 3 2.5")!!
        assertEquals(250L, p.priceBani)
    }

    @Test
    fun virgulaWordPrice() {
        val p = InventoryLineParser.parse("ouă 10 1 virgulă 20")!!
        assertEquals("ouă", p.nameText)
        assertEquals(10.0, p.quantity!!, 0.0)
        assertEquals(120L, p.priceBani)
    }

    @Test
    fun leiOnlyPrice() {
        val p = InventoryLineParser.parse("apă 6 5 lei")!!
        assertEquals(6.0, p.quantity!!, 0.0)
        assertEquals(500L, p.priceBani)
    }

    @Test
    fun leiAndBaniPrice() {
        val p = InventoryLineParser.parse("cafea 2 12 lei 75 bani")!!
        assertEquals(2.0, p.quantity!!, 0.0)
        assertEquals(1275L, p.priceBani)
    }

    @Test
    fun baniOnlyPrice() {
        val p = InventoryLineParser.parse("chibrituri 3 50 bani")!!
        assertEquals(3.0, p.quantity!!, 0.0)
        assertEquals(50L, p.priceBani)
    }

    @Test
    fun onlyQuantity_priceMissing() {
        val p = InventoryLineParser.parse("lapte 5")!!
        assertEquals("lapte", p.nameText)
        assertEquals(5.0, p.quantity!!, 0.0)
        assertNull(p.priceBani)
    }

    @Test
    fun singleMoneyMarkedAmount_isPriceNotQuantity() {
        val p = InventoryLineParser.parse("lapte 5 lei")!!
        assertEquals("lapte", p.nameText)
        assertNull(p.quantity)
        assertEquals(500L, p.priceBani)
    }

    @Test
    fun onlyName_bothMissing() {
        val p = InventoryLineParser.parse("lapte zuzu")!!
        assertEquals("lapte zuzu", p.nameText)
        assertNull(p.quantity)
        assertNull(p.priceBani)
    }

    @Test
    fun numberInsideProductNameStaysInName() {
        val p = InventoryLineParser.parse("cola 2l 5 4 lei")!!
        assertEquals("cola 2l", p.nameText)
        assertEquals(5.0, p.quantity!!, 0.0)
        assertEquals(400L, p.priceBani)
    }

    @Test
    fun fillerWordsInsideNumericRegion() {
        val p = InventoryLineParser.parse("lapte 5 bucăți 4 lei 50")!!
        assertEquals("lapte", p.nameText)
        assertEquals(5.0, p.quantity!!, 0.0)
        assertEquals(450L, p.priceBani)
    }

    @Test
    fun decimalQuantity() {
        val p = InventoryLineParser.parse("cașcaval 2,5 30 lei")!!
        assertEquals(2.5, p.quantity!!, 0.0)
        assertEquals(3000L, p.priceBani)
    }

    @Test
    fun bareThirdNumberIgnored() {
        // convention: say "4 lei 50" or "4 virgulă 50"; a bare "4 50" is NOT merged
        val p = InventoryLineParser.parse("lapte 5 4 50")!!
        assertEquals(5.0, p.quantity!!, 0.0)
        assertEquals(400L, p.priceBani)
    }

    @Test
    fun emptyName_returnedBlank() {
        val p = InventoryLineParser.parse("5 4 lei")!!
        assertEquals("", p.nameText)
        assertEquals(5.0, p.quantity!!, 0.0)
        assertEquals(400L, p.priceBani)
    }

    @Test
    fun nameWithFillerWordsNotAtEnd_keptIntact() {
        val p = InventoryLineParser.parse("lapte de vacă")!!
        assertEquals("lapte de vacă", p.nameText)
        assertNull(p.quantity)
        assertNull(p.priceBani)
    }

    @Test
    fun uppercaseInputNormalized() {
        val p = InventoryLineParser.parse("LAPTE ZUZU 5 4 LEI")!!
        assertEquals("lapte zuzu", p.nameText)
        assertEquals(400L, p.priceBani)
    }

    @Test
    fun blank_returnsNull() {
        assertNull(InventoryLineParser.parse("   "))
    }

    // ===== robustness / pinning tests (behavior documented, not "ideal") =====

    @Test
    fun adversarialInputs_neverThrow() {
        val inputs = listOf(
            "4,", ",5", "4.5.6", "4,5.6",
            "lapte 99999999999999999999999 99999999999999999999999 lei",
            "lapte virgulă", "virgulă", "lei", "bani", "lapte 4 virgulă",
            "lapte 4, lei 50", "5 5 5 5 5 5 5 5 5 5"
        )
        for (input in inputs) {
            InventoryLineParser.parse(input) // must not throw
        }
    }

    @Test
    fun singularCurrencySynonyms() {
        val leu = InventoryLineParser.parse("pâine 1 1 leu")!!
        assertEquals(100L, leu.priceBani)
        val ban = InventoryLineParser.parse("sare 2 1 ban")!!
        assertEquals(1L, ban.priceBani)
        val ron = InventoryLineParser.parse("apă 3 4 ron")!!
        assertEquals(400L, ron.priceBani)
    }

    @Test
    fun numberWord_pinnedMisattribution() {
        // "cinci" is not a digit token: it breaks the trailing region, the
        // stranded "lei" is swallowed by the region scan, and "50" becomes the
        // quantity. Pinned so a refactor can't silently change this mode.
        val p = InventoryLineParser.parse("lapte cinci lei 50")!!
        assertEquals("lapte cinci", p.nameText)
        assertEquals(50.0, p.quantity!!, 0.0)
        assertNull(p.priceBani)
    }
}
