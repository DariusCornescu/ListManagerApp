package com.darius.listmanager

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.data.usecase.ResolveResult
import com.darius.listmanager.data.usecase.ResolveSpokenProductUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Integration test for Milestone 3 - Basic Lookup Pipeline
 *
 * Tests the complete flow from spoken text to product resolution
 */
@RunWith(AndroidJUnit4::class)
class Milestone3IntegrationTest {

    private lateinit var database: AppDatabase
    private lateinit var productRepository: ProductRepository
    private lateinit var resolveUseCase: ResolveSpokenProductUseCase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Create in-memory database for testing
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        productRepository = ProductRepository(database.productDao())
        resolveUseCase = ResolveSpokenProductUseCase(productRepository)

        // Seed test data
        seedTestData()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun seedTestData() = runBlocking {
        val distributorDao = database.distributorDao()
        val productDao = database.productDao()

        // Add distributors
        val dist1Id = distributorDao.insert(DistributorEntity(distributorName = "Test Distributor A"))
        val dist2Id = distributorDao.insert(DistributorEntity(distributorName = "Test Distributor B"))

        // Add test products
        val products = listOf(
            ProductEntity(name = "Lapte", distributorId = dist1Id, aliases = "lapte,lapte de vaca,lapte proaspat"),
            ProductEntity(name = "Pâine albă", distributorId = dist1Id, aliases = "paine,paine alba,franzela"),
            ProductEntity(name = "Cartofi", distributorId = dist1Id, aliases = "cartofi,cartof"),
            ProductEntity(name = "Mere", distributorId = dist2Id, aliases = "mere,mar"),
            ProductEntity(name = "Șurub 6x100", distributorId = dist2Id, aliases = "surub 6x100,vis 6x100"),
            ProductEntity(name = "Cauciuc 27.5 x 2.25", distributorId = dist2Id, aliases = "cauciuc 27.5x2.25")
        )

        productDao.insertAll(products)
    }

    // ===== TEST CASE 1: EXACT MATCH (AUTO-ADD) =====

    @Test
    fun testExactMatch_shouldAutoAdd() = runBlocking {
        val result = resolveUseCase.execute("lapte")

        assertTrue("Should return AutoAdd for exact match", result is ResolveResult.AutoAdd)
        val autoAdd = result as ResolveResult.AutoAdd
        assertEquals("Product name should match", "Lapte", autoAdd.product.name)
        assertTrue("Score should be >= 0.82", autoAdd.score >= 0.82)
    }

    @Test
    fun testExactMatchWithDiacritics_shouldAutoAdd() = runBlocking {
        val result = resolveUseCase.execute("pâine albă")

        assertTrue("Should handle diacritics correctly", result is ResolveResult.AutoAdd)
        val autoAdd = result as ResolveResult.AutoAdd
        assertEquals("Pâine albă", autoAdd.product.name)
    }

    @Test
    fun testAliasMatch_shouldAutoAdd() = runBlocking {
        val result = resolveUseCase.execute("franzela")

        assertTrue("Should match via alias", result is ResolveResult.AutoAdd)
        val autoAdd = result as ResolveResult.AutoAdd
        assertEquals("Should find product via alias", "Pâine albă", autoAdd.product.name)
    }

    // ===== TEST CASE 2: PARTIAL MATCH (SUGGESTIONS) =====

    @Test
    fun testPartialMatch_shouldReturnSuggestions() = runBlocking {
        val result = resolveUseCase.execute("cartafi") // misspelling

        assertTrue("Should return Suggestions for partial match", result is ResolveResult.Suggestions)
        val suggestions = result as ResolveResult.Suggestions
        assertTrue("Should have at least one suggestion", suggestions.products.isNotEmpty())

        val topSuggestion = suggestions.products.first()
        assertEquals("Top suggestion should be Cartofi", "Cartofi", topSuggestion.product.name)
        assertTrue("Score should be between 0.60 and 0.82",
            topSuggestion.score >= 0.60 && topSuggestion.score < 0.82)
    }

    @Test
    fun testSimilarSoundingWord_shouldReturnSuggestions() = runBlocking {
        val result = resolveUseCase.execute("meri") // sounds like "mere"

        when (result) {
            is ResolveResult.AutoAdd -> {
                // Could auto-add if similarity is high enough
                assertEquals("Mere", result.product.name)
            }
            is ResolveResult.Suggestions -> {
                // Or show suggestions if confidence is medium
                assertTrue(result.products.any { it.product.name == "Mere" })
            }
            else -> fail("Should not be Unknown")
        }
    }

    // ===== TEST CASE 3: UNKNOWN PRODUCTS =====

    @Test
    fun testCompletelyUnknown_shouldReturnUnknown() = runBlocking {
        val result = resolveUseCase.execute("xyzabc123nonsense")

        assertTrue("Should return Unknown for nonsense input", result is ResolveResult.Unknown)
        val unknown = result as ResolveResult.Unknown
        assertEquals("xyzabc123nonsense", unknown.spokenText)
    }

    @Test
    fun testBlankInput_shouldReturnUnknown() = runBlocking {
        val result = resolveUseCase.execute("")

        assertTrue("Should return Unknown for blank input", result is ResolveResult.Unknown)
    }

    @Test
    fun testVeryLowSimilarity_shouldReturnUnknown() = runBlocking {
        val result = resolveUseCase.execute("computer graphics card")

        // Should be Unknown as it's completely different from food products
        assertTrue("Should return Unknown for completely different domain",
            result is ResolveResult.Unknown)
    }

    // ===== TEST CASE 4: DIMENSION PRODUCTS =====

    @Test
    fun testDimensionExactMatch_shouldAutoAdd() = runBlocking {
        val result = resolveUseCase.execute("șurub 6x100")

        assertTrue("Should match dimension product", result is ResolveResult.AutoAdd)
        val autoAdd = result as ResolveResult.AutoAdd
        assertEquals("Șurub 6x100", autoAdd.product.name)
    }

    @Test
    fun testDimensionWithSpaces_shouldAutoAdd() = runBlocking {
        val result = resolveUseCase.execute("șurub 6 100")

        assertTrue("Should handle space-separated dimensions", result is ResolveResult.AutoAdd)
        val autoAdd = result as ResolveResult.AutoAdd
        assertEquals("Șurub 6x100", autoAdd.product.name)
    }

    @Test
    fun testDimensionWithRomanianPe_shouldAutoAdd() = runBlocking {
        val result = resolveUseCase.execute("șurub 6 pe 100")

        assertTrue("Should handle Romanian 'pe' separator", result is ResolveResult.AutoAdd)
        val autoAdd = result as ResolveResult.AutoAdd
        assertEquals("Șurub 6x100", autoAdd.product.name)
    }

    @Test
    fun testDecimalDimensions_shouldMatch() = runBlocking {
        val result = resolveUseCase.execute("cauciuc 27.5 pe 2.25")

        assertTrue("Should match decimal dimensions", result is ResolveResult.AutoAdd)
        val autoAdd = result as ResolveResult.AutoAdd
        assertTrue("Should match tire product", autoAdd.product.name.contains("27.5"))
    }

    @Test
    fun testDimensionWithoutDecimal_shouldMatch() = runBlocking {
        val result = resolveUseCase.execute("cauciuc 275 225")

        // Should match via smart decimal insertion
        when (result) {
            is ResolveResult.AutoAdd -> {
                assertTrue(result.product.name.contains("27.5") || result.product.name.contains("Cauciuc"))
            }
            is ResolveResult.Suggestions -> {
                assertTrue(result.products.any { it.product.name.contains("Cauciuc") })
            }
            else -> fail("Should find tire product")
        }
    }

    // ===== TEST CASE 5: MULTIPLE SIMILAR PRODUCTS =====

    @Test
    fun testMultipleSimilarProducts_shouldRankCorrectly() = runBlocking {
        // Add more similar products
        val dist1Id = database.distributorDao().getAll().first().id
        database.productDao().insertAll(listOf(
            ProductEntity(name = "Mere roșii", distributorId = dist1Id, aliases = "mere rosii"),
            ProductEntity(name = "Mere verzi", distributorId = dist1Id, aliases = "mere verzi"),
            ProductEntity(name = "Mere galbene", distributorId = dist1Id, aliases = "mere galbene")
        ))

        val result = resolveUseCase.execute("mere")

        when (result) {
            is ResolveResult.AutoAdd -> {
                // Should match generic "Mere"
                assertEquals("Mere", result.product.name)
            }
            is ResolveResult.Suggestions -> {
                // All similar products should be in suggestions
                assertTrue("Should have multiple suggestions", result.products.size >= 2)
                // "Mere" should be top ranked
                assertEquals("Generic Mere should rank highest", "Mere", result.products.first().product.name)
            }
            else -> fail("Should not be Unknown")
        }
    }

    // ===== TEST CASE 6: EDGE CASES =====

    @Test
    fun testUpperCase_shouldNormalize() = runBlocking {
        val result = resolveUseCase.execute("LAPTE")

        assertTrue("Should handle uppercase", result is ResolveResult.AutoAdd)
        val autoAdd = result as ResolveResult.AutoAdd
        assertEquals("Lapte", autoAdd.product.name)
    }

    @Test
    fun testExtraSpaces_shouldNormalize() = runBlocking {
        val result = resolveUseCase.execute("  lapte   de   vaca  ")

        // Should match via alias normalization
        assertTrue("Should handle extra spaces",
            result is ResolveResult.AutoAdd || result is ResolveResult.Suggestions)
    }

    @Test
    fun testMixedCaseWithDiacritics_shouldNormalize() = runBlocking {
        val result = resolveUseCase.execute("PâInE AlBă")

        assertTrue("Should handle mixed case with diacritics",
            result is ResolveResult.AutoAdd)
    }

    // ===== PERFORMANCE TEST =====

    @Test
    fun testPerformance_shouldCompleteQuickly() = runBlocking {
        val startTime = System.currentTimeMillis()

        resolveUseCase.execute("lapte")

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        assertTrue("Resolution should complete in under 500ms", duration < 500)
    }

    @Test
    fun testMultipleQueries_shouldHandleSequentially() = runBlocking {
        val queries = listOf("lapte", "paine", "cartofi", "mere", "xyzabc")

        queries.forEach { query ->
            val result = resolveUseCase.execute(query)
            assertNotNull("Should return result for: $query", result)
        }
    }
}