package com.darius.listmanager.util

import com.darius.listmanager.data.local.entity.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How sizes and word endings are said, against how the catalog writes them. */
class ProductRankerSpokenFormsTest {
    private val tube = ProductEntity(1, "CAMERE NOVA 26*1.75 AV", 1, "camere nova av")
    private val otherTube = ProductEntity(2, "CAMERE NOVA 28*1.75", 1, "camere nova")
    private val carTube = ProductEntity(3, "CAMERA AUTO R13 155/70-13", 1, "camera auto")
    private val demoScrews = ProductEntity(4, "Șuruburi 4x40mm 100buc", 1, "suruburi, surub")
    private val woodScrew = ProductEntity(5, "SURUB XYZ LEMN 3.5*35", 1, "surub xyz lemn")
    private val products = listOf(tube, otherTube, carTube, demoScrews, woodScrew)

    private fun top(phrase: String) = ProductRanker.rank(phrase, products).first()

    @Test
    fun aDecimalSaidWithoutItsDotStillFindsTheSize() {
        assertEquals(tube.id, top("cameră nova 26x175").product.id)
        assertEquals(tube.id, top("cameră 26x175").product.id)
    }

    @Test
    fun theSaidSizeWinsOverAnAliasWithAnotherSize() {
        assertEquals(woodScrew.id, top("șurub 3.5x35").product.id)
        assertEquals(woodScrew.id, top("șurub 3,5x35").product.id)
    }

    @Test
    fun withoutASizeNothingChanges() {
        val ranked = ProductRanker.rank("surub", products)
        assertEquals(demoScrews.id, ranked.first().product.id)
        assertTrue(ranked.all { it.breakdown["size"] == 0.0 })
    }

    @Test
    fun sizesIgnoreTheDecimalDotAndKeepTwoOfThreeParts() {
        assertEquals(setOf("26x175"), TextNorm.sizes(TextNorm.normalize("CAMERE NOVA 26*1.75 AV")))
        assertEquals(setOf("35x35"), TextNorm.sizes(TextNorm.normalize("SURUB 3,5X35")))
        assertEquals(setOf("105x52x21", "105x52"), TextNorm.sizes(TextNorm.normalize("BURETE 10.5X5.2X2.1CM")))
    }

    @Test
    fun wordStemsJoinEndingsButKeepShortWordsAndNumbers() {
        assertEquals(TextNorm.wordStem("camera"), TextNorm.wordStem("camere"))
        assertEquals(TextNorm.wordStem("camera"), TextNorm.wordStem("camerei"))
        assertEquals(TextNorm.wordStem("surub"), TextNorm.wordStem("suruburi"))
        assertEquals("oua", TextNorm.wordStem("oua"))
        assertEquals("26x1.75", TextNorm.wordStem("26x1.75"))
    }

    @Test
    fun ftsAlsoSearchesOtherEndingsOfAWord() {
        assertTrue("camer*" in QueryVariants.generate("cameră nova"))
    }
}
