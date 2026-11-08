package com.darius.listmanager.data.usecase

import androidx.room.Room
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.network.ListManagerApi
import com.darius.listmanager.util.ProductRanker
import com.darius.listmanager.util.SpokenQuantity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.lang.reflect.Proxy

/**
 * The resolver cases from the instrumented Milestone3IntegrationTest, on the JVM, so they run
 * on every build instead of only when a phone is attached. Same catalog, same thresholds, real
 * Room FTS (native SQLite), offline: the API fails the test if it is ever called.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ResolveSpokenProductRankingTest {
    private lateinit var db: AppDatabase
    private lateinit var resolver: ResolveSpokenProductUseCase
    private var distA = 0L
    private var distB = 0L

    @Before
    fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val offlineApi = Proxy.newProxyInstance(
            ListManagerApi::class.java.classLoader, arrayOf(ListManagerApi::class.java)
        ) { _, method, _ -> error("Resolver must not call the network: ${method.name}") } as ListManagerApi
        val products = ProductRepository(
            db.productDao(), PendingOperationRepository(db.pendingOperationDao()), context, offlineApi, db
        ) { false }
        resolver = ResolveSpokenProductUseCase(products)

        distA = db.distributorDao().insert(DistributorEntity(distributorName = "Test Distributor A"))
        distB = db.distributorDao().insert(DistributorEntity(distributorName = "Test Distributor B"))
        db.productDao().insertAll(
            listOf(
                ProductEntity(name = "Lapte", distributorId = distA, aliases = "lapte,lapte de vaca,lapte proaspat"),
                ProductEntity(name = "Pâine albă", distributorId = distA, aliases = "paine,paine alba,franzela"),
                ProductEntity(name = "Cartofi", distributorId = distA, aliases = "cartofi,cartof"),
                ProductEntity(name = "Mere", distributorId = distB, aliases = "mere,mar"),
                ProductEntity(name = "Șurub 6x100", distributorId = distB, aliases = "surub 6x100,vis 6x100"),
                ProductEntity(name = "Cauciuc 27.5 x 2.25", distributorId = distB, aliases = "cauciuc 27.5x2.25"),
            )
        )
    }

    @After
    fun tearDown() = db.close()

    private fun resolve(text: String) = runBlocking { resolver.execute(text) }

    private fun autoAdd(text: String): ResolveResult.AutoAdd {
        val result = resolve(text)
        assertTrue("'$text' should auto-add but was $result", result is ResolveResult.AutoAdd)
        val add = result as ResolveResult.AutoAdd
        assertTrue("AutoAdd score ${add.score} must be >= 0.82", add.score >= 0.82)
        return add
    }

    // ---- Romanian dictation and diacritics -------------------------------------------------

    @Test
    fun exactNameAutoAdds() = assertEquals("Lapte", autoAdd("lapte").product.name)

    @Test
    fun diacriticsStrippedOrMixedResolveToTheSameProduct() {
        listOf("pâine albă", "paine alba", "PâInE AlBă").forEach { spoken ->
            assertEquals(spoken, "Pâine albă", autoAdd(spoken).product.name)
        }
    }

    @Test
    fun uppercaseIsNormalised() = assertEquals("Lapte", autoAdd("LAPTE").product.name)

    @Test
    fun aliasAutoAdds() = assertEquals("Pâine albă", autoAdd("franzela").product.name)

    @Test
    fun extraSpacesStillFindTheAlias() {
        val result = resolve("  lapte   de   vaca  ")
        assertTrue("$result", result is ResolveResult.AutoAdd || result is ResolveResult.Suggestions)
    }

    // ---- Confidence bands ------------------------------------------------------------------

    private fun topRanked(spoken: String) = runBlocking {
        ProductRanker.rank(spoken, db.productDao().getAll()).first().product.name
    }

    @Test
    fun nearMissesAndPluralsStillRankTheIntendedProductFirst() {
        assertEquals("Cartofi", topRanked("cartafi"))
        assertEquals("Mere", topRanked("meri"))
        assertEquals("Pâine albă", topRanked("pâini"))
    }

    @Test
    fun misspellingGivesSuggestionsInTheMiddleBandSortedDescending() {
        val result = resolve("cartafi")
        assertTrue("$result", result is ResolveResult.Suggestions)
        val ranked = (result as ResolveResult.Suggestions).products
        assertEquals("Cartofi", ranked.first().product.name)
        assertTrue("top ${ranked.first().score}", ranked.first().score >= 0.60 && ranked.first().score < 0.82)
        assertTrue("at most five suggestions", ranked.size <= 5)
        assertEquals("sorted by score, descending", ranked.sortedByDescending { it.score }, ranked)
    }

    @Test
    fun similarSoundingWordIsNeverUnknown() {
        when (val result = resolve("meri")) {
            is ResolveResult.AutoAdd -> assertEquals("Mere", result.product.name)
            is ResolveResult.Suggestions -> assertTrue(result.products.any { it.product.name == "Mere" })
            is ResolveResult.Unknown -> fail("'meri' should reach Mere")
        }
    }

    @Test
    fun suggestionsAreSortedDescendingAndCappedAtFive() = runBlocking {
        db.productDao().insertAll(
            (1..8).map { ProductEntity(name = "Lapte praf $it", distributorId = distA, aliases = "lapte praf") }
        )
        when (val result = resolver.execute("lapte praf")) {
            is ResolveResult.Suggestions -> {
                assertTrue(result.products.size <= 5)
                assertEquals(result.products.sortedByDescending { it.score }, result.products)
            }
            is ResolveResult.AutoAdd -> assertTrue(result.product.name.startsWith("Lapte praf"))
            is ResolveResult.Unknown -> fail("'lapte praf' should not be Unknown")
        }
    }

    @Test
    fun blankNonsenseAndOutOfDomainAreUnknown() {
        listOf("", "   ", "xyzabc123nonsense", "computer graphics card").forEach { spoken ->
            val result = resolve(spoken)
            assertTrue("'$spoken' should be Unknown but was $result", result is ResolveResult.Unknown)
            assertEquals(spoken, (result as ResolveResult.Unknown).spokenText)
        }
    }

    @Test
    fun genericNameOutranksItsVariants() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity(name = "Mere roșii", distributorId = distA, aliases = "mere rosii"),
                ProductEntity(name = "Mere verzi", distributorId = distA, aliases = "mere verzi"),
                ProductEntity(name = "Mere galbene", distributorId = distA, aliases = "mere galbene"),
            )
        )
        when (val result = resolver.execute("mere")) {
            is ResolveResult.AutoAdd -> assertEquals("Mere", result.product.name)
            is ResolveResult.Suggestions -> assertEquals("Mere", result.products.first().product.name)
            is ResolveResult.Unknown -> fail("'mere' should not be Unknown")
        }
    }

    // ---- Dimensions and numeric variants ---------------------------------------------------

    @Test
    fun screwDimensionAutoAddsInEverySpokenForm() {
        listOf("șurub 6x100", "surub 6x100", "șurub 6 100", "șurub 6 pe 100").forEach { spoken ->
            assertEquals(spoken, "Șurub 6x100", autoAdd(spoken).product.name)
        }
    }

    @Test
    fun decimalTyreDimensionAutoAdds() =
        assertEquals("Cauciuc 27.5 x 2.25", autoAdd("cauciuc 27.5 pe 2.25").product.name)

    @Test
    fun tyreDimensionWithoutDecimalPointsStillFindsTheTyre() {
        when (val result = resolve("cauciuc 275 225")) {
            is ResolveResult.AutoAdd -> assertEquals("Cauciuc 27.5 x 2.25", result.product.name)
            is ResolveResult.Suggestions -> assertEquals("Cauciuc 27.5 x 2.25", result.products.first().product.name)
            is ResolveResult.Unknown -> fail("'cauciuc 275 225' should find the tyre")
        }
    }

    @Test
    fun exactDimensionVariantRanksFirstAmongSiblings() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity(name = "Șurub 6x80", distributorId = distB, aliases = "surub 6x80"),
                ProductEntity(name = "Șurub 8x100", distributorId = distB, aliases = "surub 8x100"),
            )
        )
        mapOf(
            "șurub 6 pe 100" to "Șurub 6x100",
            "șurub 6 pe 80" to "Șurub 6x80",
            "șurub 8 pe 100" to "Șurub 8x100",
        ).forEach { (spoken, expected) ->
            val top = when (val result = resolver.execute(spoken)) {
                is ResolveResult.AutoAdd -> result.product.name
                is ResolveResult.Suggestions -> result.products.first().product.name
                is ResolveResult.Unknown -> null
            }
            assertEquals(spoken, expected, top)
        }
    }

    @Test
    fun dimensionsDoNotCrossMatchOtherProducts() {
        assertEquals("Șurub 6x100", autoAdd("șurub 6x100").product.name)
        assertEquals("Cauciuc 27.5 x 2.25", autoAdd("cauciuc 27.5 pe 2.25").product.name)
        when (val result = resolve("6 pe 100")) {
            is ResolveResult.AutoAdd -> assertEquals("Șurub 6x100", result.product.name)
            is ResolveResult.Suggestions -> assertTrue(result.products.none { it.product.name.startsWith("Cauciuc") && it.score >= 0.82 })
            is ResolveResult.Unknown -> Unit
        }
    }

    // ---- Spoken quantities and multi-product utterances ------------------------------------

    @Test
    fun leadingWordQuantitySurvivesIntoAutoAdd() = runBlocking {
        val resolved = resolver.resolveMultiple("două lapte")
        assertEquals(1, resolved.size)
        val (segment, result) = resolved.single()
        assertEquals("Lapte", (result as ResolveResult.AutoAdd).product.name)
        assertEquals(2, SpokenQuantity.from(segment))
    }

    @Test
    fun twoQuantifiedProductsInOneBreathSplitWithTheirQuantities() = runBlocking {
        val resolved = resolver.resolveMultiple("3 pâini și cinci mere")
        assertEquals("segments: $resolved", listOf(3, 5), resolved.map { SpokenQuantity.from(it.first) })
        assertEquals("Mere", (resolved.last().second as ResolveResult.AutoAdd).product.name)
    }

    @Test
    fun pluralPainiResolvesToBread() = runBlocking {
        val (segment, result) = resolver.resolveMultiple("3 pâini și cinci mere").first()
        assertEquals(3, SpokenQuantity.from(segment))
        assertTrue("$result", result !is ResolveResult.Unknown)
    }

    // ---- Network isolation -----------------------------------------------------------------

    @Test
    fun repeatedQueriesAllReturnWithoutTouchingTheNetwork() {
        listOf("lapte", "paine", "cartofi", "mere", "xyzabc").forEach { resolve(it) }
    }
}
