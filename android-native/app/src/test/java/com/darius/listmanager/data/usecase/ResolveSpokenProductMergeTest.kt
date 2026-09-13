package com.darius.listmanager.data.usecase

import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.util.SpokenQuantity
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveSpokenProductMergeTest {
    private val milk = ProductEntity(
        id = 1,
        name = "Lapte Zuzu 1L",
        distributorId = 1,
        aliases = "lapte, zuzu",
    )

    @Test
    fun quantityAndBrandFragmentsForSameProductBecomeOneAddition() {
        val results = listOf(
            "două lapte" to ResolveResult.AutoAdd(milk, 1.2),
            "Zuzu" to ResolveResult.AutoAdd(milk, 1.3),
        )

        val merged = ResolveSpokenProductUseCase.mergeAdjacentAutoAdds(results)

        assertEquals(1, merged.size)
        assertEquals("două lapte Zuzu", merged.single().first)
        assertEquals(1L, (merged.single().second as ResolveResult.AutoAdd).product.id)
        assertEquals(2, SpokenQuantity.from(merged.single().first))
    }

    @Test
    fun separatelyQuantifiedOccurrencesRemainSeparate() {
        val results = listOf(
            "două lapte" to ResolveResult.AutoAdd(milk, 1.2),
            "trei lapte" to ResolveResult.AutoAdd(milk, 1.3),
        )

        val merged = ResolveSpokenProductUseCase.mergeAdjacentAutoAdds(results)

        assertEquals(2, merged.size)
    }
}
