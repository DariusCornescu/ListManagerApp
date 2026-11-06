package com.darius.listmanager.util

import com.darius.listmanager.data.local.entity.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductRankerQuantityTest {
    @Test
    fun leadingQuantityDoesNotHideTheCorrectCatalogProduct() {
        val products = listOf(
            ProductEntity(
                id = 1,
                name = "Lapte Zuzu 1L",
                distributorId = 1,
                aliases = "lapte, zuzu",
            ),
            ProductEntity(
                id = 4,
                name = "Ouă M 10buc",
                distributorId = 1,
                aliases = "oua, ou",
            ),
        )

        val ranked = ProductRanker.rank("două lapte Zuzu", products)

        assertEquals(1L, ranked.first().product.id)
        assertTrue(
            "A correctly transcribed product must reach the suggestion threshold",
            ranked.first().score >= 0.60,
        )
    }
}
