package com.darius.listmanager.data.usecase

import androidx.room.Room
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.network.ListManagerApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.lang.reflect.Proxy

/**
 * A short name said with one wrong letter scores about 0.5, under the plain suggestion level,
 * while the right product still leads every other by far. That is asked about, not filed as
 * unknown. A weak score without such a lead, or with nothing to lead, still stays unknown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ResolveSpokenProductClearLeaderTest {
    private val database: AppDatabase = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(),
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After
    fun close() = database.close()

    /** The catalog Milestone3IntegrationTest uses on the emulator. */
    private val groceries = listOf(
        ProductEntity(1, "Lapte", 1, "lapte,lapte de vaca,lapte proaspat"),
        ProductEntity(2, "Pâine albă", 1, "paine,paine alba,franzela"),
        ProductEntity(3, "Cartofi", 1, "cartofi,cartof"),
        ProductEntity(4, "Mere", 2, "mere,mar"),
        ProductEntity(5, "Șurub 6x100", 2, "surub 6x100,vis 6x100"),
        ProductEntity(6, "Cauciuc 27.5 x 2.25", 2, "cauciuc 27.5x2.25"),
    )

    private fun useCase(products: List<ProductEntity>): ResolveSpokenProductUseCase = runBlocking {
        database.distributorDao().insert(DistributorEntity(1, "A"))
        database.distributorDao().insert(DistributorEntity(2, "B"))
        products.forEach { database.productDao().insert(it) }
        val api = Proxy.newProxyInstance(
            ListManagerApi::class.java.classLoader,
            arrayOf(ListManagerApi::class.java),
        ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as ListManagerApi
        ResolveSpokenProductUseCase(
            ProductRepository(
                dao = database.productDao(),
                pendingOperationRepository = PendingOperationRepository(database.pendingOperationDao()),
                context = RuntimeEnvironment.getApplication(),
                api = api,
                database = database,
                isOnline = { false },
            )
        )
    }

    private fun assertSuggestsFirst(expected: String, result: ResolveResult) {
        assertTrue("expected Suggestions, got $result", result is ResolveResult.Suggestions)
        val top = (result as ResolveResult.Suggestions).products.first()
        assertEquals(expected, top.product.name)
        assertTrue("a clear leader under the plain level: ${top.score}", top.score >= 0.50 && top.score < 0.60)
    }

    @Test
    fun aOneLetterSlipInAShortName_isAskedAbout() = runBlocking {
        val resolve = useCase(groceries)

        assertSuggestsFirst("Cartofi", resolve.execute("cartafi"))
        assertSuggestsFirst("Mere", resolve.execute("meri"))
    }

    @Test
    fun aWeakScoreWithoutALead_staysUnknown() = runBlocking {
        // "meri" is one letter from both, so neither stands clear of the other.
        val resolve = useCase(
            listOf(
                ProductEntity(1, "Mere", 1, "mere"),
                ProductEntity(2, "Mera", 1, "mera"),
            )
        )

        assertTrue(resolve.execute("meri") is ResolveResult.Unknown)
    }

    @Test
    fun aLoneWeakCandidate_hasNothingToLead_andStaysUnknown() = runBlocking {
        val resolve = useCase(listOf(ProductEntity(1, "Cartofi", 1, "cartofi,cartof")))

        assertTrue(resolve.execute("cartafi") is ResolveResult.Unknown)
    }

    @Test
    fun nonsense_staysUnknown() = runBlocking {
        val resolve = useCase(groceries)

        assertTrue(resolve.execute("xyzabc123nonsense") is ResolveResult.Unknown)
    }
}
