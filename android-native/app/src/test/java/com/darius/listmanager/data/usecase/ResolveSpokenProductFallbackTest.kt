package com.darius.listmanager.data.usecase

import androidx.room.Room
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.network.ListManagerApi
import com.darius.listmanager.util.PreparedCatalogCache
import com.darius.listmanager.util.ProductRanker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * A phrase the catalog search does not find used to download the whole catalog from the
 * server before it could be matched, while the person waited for the result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ResolveSpokenProductFallbackTest {
    private lateinit var database: AppDatabase
    private lateinit var useCase: ResolveSpokenProductUseCase
    private val apiCalls = AtomicInteger()
    private val products = listOf(
        ProductEntity(1, "Lapte Zuzu 1L", 1, "lapte, zuzu"),
        ProductEntity(2, "Pâine albă 500g", 1, "paine"),
        ProductEntity(3, "Detergent vase 1.35 L", 1, "detergent vase"),
    )

    @Before
    fun setup() {
        PreparedCatalogCache.clear()
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        runBlocking {
            database.distributorDao().insert(DistributorEntity(1, "Furnizor"))
            products.forEach { database.productDao().insert(it) }
        }
        val api = Proxy.newProxyInstance(
            ListManagerApi::class.java.classLoader,
            arrayOf(ListManagerApi::class.java),
        ) { _, method, _ ->
            apiCalls.incrementAndGet()
            error("Unexpected API call: ${method.name}")
        } as ListManagerApi
        val repository = ProductRepository(
            dao = database.productDao(),
            pendingOperationRepository = PendingOperationRepository(database.pendingOperationDao()),
            context = RuntimeEnvironment.getApplication(),
            api = api,
            database = database,
            // Online, so the old fallback would have gone to the server.
            isOnline = { true },
        )
        useCase = ResolveSpokenProductUseCase(repository)
    }

    @After
    fun close() {
        database.close()
        PreparedCatalogCache.clear()
    }

    @Test
    fun aPhraseTheSearchMissesIsRankedLocallyWithoutTheNetwork() = runBlocking {
        // No catalog word in it, so the full-text search finds nothing.
        val phrase = "qqq zzz"
        val result = useCase.execute(phrase)

        assertEquals("no request may leave the phone while matching", 0, apiCalls.get())
        val expectedTop = ProductRanker.rank(phrase, products).firstOrNull()?.score ?: 0.0
        when (result) {
            is ResolveResult.Unknown -> assertTrue("top score $expectedTop", expectedTop < 0.60)
            is ResolveResult.Suggestions -> assertEquals(expectedTop, result.products.first().score, 0.0)
            is ResolveResult.AutoAdd -> assertEquals(expectedTop, result.score, 0.0)
        }
    }

    @Test
    fun dictationAfterWarmUpStillMatchesAndStaysOffline() = runBlocking {
        useCase.warmUp()
        val results = useCase.resolveMultiple("lapte zuzu")

        assertEquals(0, apiCalls.get())
        val result = results.single().second
        assertTrue("$result", result is ResolveResult.AutoAdd && result.product.id == 1L)
    }
}
