package com.darius.listmanager.util

import com.darius.listmanager.data.local.entity.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Segmentation scores every word window against a catalog prepared once, and takes only
 * the best score, skipping products that cannot beat it. Both shortcuts must give exactly
 * what ranking the raw product list gives.
 */
class ProductRankerPreparedTest {
    private val products = listOf(
        ProductEntity(1, "Lapte Zuzu 1L", 1, "lapte, zuzu"),
        ProductEntity(2, "Pâine albă 500g", 1, "paine, franzela"),
        ProductEntity(3, "Șuruburi 4x40mm 100buc", 1, "suruburi, surub"),
        ProductEntity(4, "Cauciuc 27.5*2.25", 1, null),
        ProductEntity(5, "Ouă M 10buc", 1, ""),
        ProductEntity(6, "Detergent vase 1.35 L", 1, "detergent vase, vase"),
        ProductEntity(7, "Cameră bicicletă 26*1.75", 1, "camera, camere"),
        ProductEntity(8, "Folie stretch 500m", 1, "folie"),
    )

    private val phrases = listOf(
        "lapte", "două lapte Zuzu", "paine alba", "surub 4 pe 40", "cauciuc 275 225",
        "cauciuc 27.5 pe 2.25", "oua", "detergent vase", "camera 26 * 175", "x", "*",
        "175", "folie strech", "lapte paine", "", "   ", "o", "1 22 333",
    )

    @Test
    fun rankingAPreparedCatalogMatchesRankingTheProducts() {
        val catalog = ProductRanker.prepare(products)
        phrases.forEach { phrase ->
            val expected = ProductRanker.rank(phrase, products)
            val actual = ProductRanker.rank(phrase, catalog)
            assertEquals(phrase, expected.map { it.product.id to it.score }, actual.map { it.product.id to it.score })
        }
    }

    @Test
    fun bestScoreIsTheTopRankedScore() {
        val catalog = ProductRanker.prepare(products)
        phrases.forEach { phrase ->
            val top = ProductRanker.rank(phrase, products).firstOrNull()?.score ?: 0.0
            assertEquals(phrase, top, ProductRanker.bestScore(phrase, catalog), 0.0)
        }
    }

    @Test
    fun bestMatchIsTheTopRankedProduct() {
        val catalog = ProductRanker.prepare(products)
        phrases.forEach { phrase ->
            val top = ProductRanker.rank(phrase, products).firstOrNull()
            val best = ProductRanker.bestMatch(phrase, catalog)
            assertEquals(phrase, top?.product?.id, best?.product?.product?.id)
            assertEquals(phrase, top?.score ?: 0.0, best?.score ?: 0.0, 0.0)
        }
    }

    @Test
    fun aProductMentionsItsWordsOtherEndingsAbbreviationsAndSizes() {
        val catalog = ProductRanker.prepare(
            products + ProductEntity(9, "Deterg. rufe 2L", 1, null),
        )
        val camera = catalog.products.single { it.product.id == 7L }
        val detergent = catalog.products.single { it.product.id == 9L }

        listOf("cameră", "camerei", "Bicicleta", "26x175", "26x1.75").forEach {
            assertEquals(it, true, camera.mentions(it))
        }
        listOf("șurub", "detergent", "28x175", "pe").forEach {
            assertEquals(it, false, camera.mentions(it))
        }
        assertEquals(true, detergent.mentions("detergent"))
        assertEquals(false, detergent.mentions("vase"))
        // Letters said in the wrong order are still the same word.
        assertEquals(true, detergent.mentions("rfue"))
        assertEquals(0.75, SimilarityEngine.swapTolerantSimilarity("ucie", "cuie"), 1e-9)
        assertEquals(0.5, SimilarityEngine.levenshteinSimilarity("ucie", "cuie"), 1e-9)
    }

    @Test
    fun rankingSeveralHypothesesAgainstAPreparedCatalogMatchesTheProducts() {
        val catalog = ProductRanker.prepare(products)
        val hypotheses = listOf("lapte zuzu", "lapte zuza", "", "camera 26 * 175")
        val expected = ProductRanker.rankAcross(hypotheses, products)
        val actual = ProductRanker.rankAcross(hypotheses, catalog)
        assertEquals(expected.map { it.product.id to it.score }, actual.map { it.product.id to it.score })
    }

    @Test
    fun theCacheReusesAnUnchangedCatalogAndRebuildsAChangedOne() {
        PreparedCatalogCache.clear()
        val first = PreparedCatalogCache.get(products)
        assertSame(first, PreparedCatalogCache.get(products.toList()))

        val renamed = products.map { if (it.id == 1L) it.copy(name = "Lapte Napolact 1L") else it }
        val second = PreparedCatalogCache.get(renamed)
        assertNotSame(first, second)
        assertEquals(
            ProductRanker.rank("lapte napolact", renamed).map { it.product.id to it.score },
            ProductRanker.rank("lapte napolact", second).map { it.product.id to it.score },
        )
        PreparedCatalogCache.clear()
    }

    @Test
    fun levenshteinDistanceIsUnchanged() {
        assertEquals(3, SimilarityEngine.levenshteinDistance("kitten", "sitting"))
        assertEquals(0, SimilarityEngine.levenshteinDistance("", ""))
        assertEquals(4, SimilarityEngine.levenshteinDistance("", "abcd"))
        assertEquals(4, SimilarityEngine.levenshteinDistance("abcd", ""))
        assertEquals(1, SimilarityEngine.levenshteinDistance("camera", "camere"))
        assertEquals(2, SimilarityEngine.levenshteinDistance("flaw", "lawn"))
    }
}
