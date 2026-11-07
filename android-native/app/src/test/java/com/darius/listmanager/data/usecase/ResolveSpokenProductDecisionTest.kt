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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.lang.reflect.Proxy

/** What a dictated phrase turns into once it has been matched against the local catalog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ResolveSpokenProductDecisionTest {
    private lateinit var database: AppDatabase
    private lateinit var useCase: ResolveSpokenProductUseCase

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        runBlocking {
            database.distributorDao().insert(DistributorEntity(1, "Furnizor"))
            listOf(
                ProductEntity(1, "SURUB GIPS CARTON 3.5 X 35 - SET A", 1, "surub gips carton a"),
                ProductEntity(2, "SURUB GIPS CARTON 3.5 X 35 - SET B", 1, "surub gips carton b"),
                ProductEntity(3, "Șuruburi 4x40mm 100buc", 1, "suruburi, surub"),
                ProductEntity(4, "CAMERE NOVA 26*1.75 AV", 1, "camere nova av"),
                ProductEntity(5, "Lapte Zuzu 1L", 1, "lapte, zuzu"),
                ProductEntity(6, "SURUB XYZ LEMN 3.5*35", 1, "surub xyz lemn"),
            ).forEach { database.productDao().insert(it) }
        }
        val api = Proxy.newProxyInstance(
            ListManagerApi::class.java.classLoader,
            arrayOf(ListManagerApi::class.java),
        ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as ListManagerApi
        useCase = ResolveSpokenProductUseCase(
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

    @After
    fun close() = database.close()

    @Test
    fun twoVariantsThatTieAreAskedAboutInsteadOfAdded() = runBlocking {
        val result = useCase.execute("surub gips carton 3,5 pe 35")

        assertTrue("$result", result is ResolveResult.Suggestions)
        assertEquals(setOf(1L, 2L), (result as ResolveResult.Suggestions).products.take(2).map { it.product.id }.toSet())
    }

    @Test
    fun aClearMatchIsStillAddedDirectly() = runBlocking {
        val result = useCase.execute("lapte")

        assertEquals(5L, (result as ResolveResult.AutoAdd).product.id)
    }

    @Test
    fun sizesInAPhraseAreNotReadAsCounts() = runBlocking {
        val results = useCase.resolveMultiple("cameră nova 26 * 175 șurub 3,5 * 35")

        assertEquals(listOf("cameră nova 26x175", "șurub 3.5x35"), results.map { it.first })
        val tube = results.first().second
        val tubeId = when (tube) {
            is ResolveResult.AutoAdd -> tube.product.id
            is ResolveResult.Suggestions -> tube.products.first().product.id
            is ResolveResult.Unknown -> null
        }
        assertEquals(4L, tubeId)
        val screw = results.last().second
        assertTrue("$screw", screw !is ResolveResult.AutoAdd || screw.product.id == 6L)
    }
}
