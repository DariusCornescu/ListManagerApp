package com.darius.listmanager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenQuantityTest {
    @Test fun `reads Romanian quantity words at the start`() {
        assertEquals(2, SpokenQuantity.from("două lapte Zuzu"))
        assertEquals(2, SpokenQuantity.from("doi lapte Zuzu"))
        assertEquals(5, SpokenQuantity.from("cinci pâini"))
    }

    @Test fun `reads a leading numeric quantity`() {
        assertEquals(12, SpokenQuantity.from("12 sticle de apă"))
        assertEquals(3, SpokenQuantity.from("3x lapte"))
    }

    @Test fun `does not treat product measurements as quantity`() {
        assertEquals(1, SpokenQuantity.from("Lapte Zuzu 1L"))
        assertEquals(1, SpokenQuantity.from("Șuruburi 4x40mm"))
    }

    @Test fun `invalid or missing leading quantity defaults to one`() {
        assertEquals(1, SpokenQuantity.from("lapte Zuzu"))
        assertEquals(1, SpokenQuantity.from("0 lapte"))
        assertEquals(1, SpokenQuantity.from(""))
    }

    @Test fun `removes only a valid leading quantity from product text`() {
        assertEquals("lapte zuzu", SpokenQuantity.productText("două lapte Zuzu"))
        assertEquals("lapte", SpokenQuantity.productText("3x lapte"))
        assertEquals("sticle de apa", SpokenQuantity.productText("12 sticle de apă"))
        assertEquals("lapte zuzu 1l", SpokenQuantity.productText("Lapte Zuzu 1L"))
        assertEquals("suruburi 4x40mm", SpokenQuantity.productText("Șuruburi 4x40mm"))
        assertEquals("0 lapte", SpokenQuantity.productText("0 lapte"))
        assertEquals(true, SpokenQuantity.hasExplicitPrefix("două lapte Zuzu"))
        assertEquals(false, SpokenQuantity.hasExplicitPrefix("Lapte Zuzu 1L"))
    }

    @Test
    fun numbersAboveTen_inWords() {
        assertEquals(11, SpokenQuantity.from("unsprezece pungi"))
        assertEquals(12, SpokenQuantity.from("douăsprezece ouă"))
        assertEquals(15, SpokenQuantity.from("cinspe cutii"))
        assertEquals(20, SpokenQuantity.from("douăzeci de ouă"))
        assertEquals(25, SpokenQuantity.from("douăzeci și cinci de cutii"))
        assertEquals(100, SpokenQuantity.from("o sută de pungi"))
        assertEquals(200, SpokenQuantity.from("două sute de pungi"))
        assertEquals(125, SpokenQuantity.from("o sută douăzeci și cinci de cutii"))
    }

    @Test
    fun countWords_areRemovedFromTheProduct() {
        assertEquals("oua", SpokenQuantity.productText("douăzeci de ouă"))
        assertEquals("cutii", SpokenQuantity.productText("douăzeci și cinci de cutii"))
        assertEquals("pungi", SpokenQuantity.productText("două sute de pungi"))
        assertEquals("oua", SpokenQuantity.productText("10 de ouă"))
    }

    @Test
    fun aConnector_withoutACountAfterIt_isNotPartOfTheNumber() {
        assertEquals(20, SpokenQuantity.from("douăzeci și lapte"))
        assertEquals("si lapte", SpokenQuantity.productText("douăzeci și lapte"))
    }

    @Test
    fun aNumberWithNoProduct_keepsItsText_soItIsNotAnEmptyQuery() {
        assertEquals("o suta", SpokenQuantity.productText("o sută"))
    }

    @Test
    fun aLoneDigit_isNotACount() {
        assertEquals(1, SpokenQuantity.from("3"))
    }
}
