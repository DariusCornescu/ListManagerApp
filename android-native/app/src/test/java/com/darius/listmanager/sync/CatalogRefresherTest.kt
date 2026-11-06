package com.darius.listmanager.sync

import androidx.room.InvalidationTracker
import androidx.room.Room
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.InventoryItemEntity
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.local.entity.SessionEntity
import com.darius.listmanager.data.local.entity.SessionItemEntity
import com.darius.listmanager.data.model.OperationData
import com.darius.listmanager.data.model.OperationStatus
import com.darius.listmanager.data.model.OperationType
import com.darius.listmanager.data.model.ResourceType
import com.darius.listmanager.network.DistributorDTO
import com.darius.listmanager.network.ListManagerApi
import com.darius.listmanager.network.ProductDTO
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers.Companion.headersOf
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CatalogRefresherTest {
    private lateinit var db: AppDatabase
    private lateinit var etags: InMemoryCatalogEtagStore
    private lateinit var server: FakeServer

    /** Answers like the real endpoints, including 304 for a version the phone already holds. */
    private class FakeServer {
        var products = listOf<ProductDTO>()
        var distributors = listOf<DistributorDTO>()
        var offline = false
        var productStatus = 200
        val calls = mutableListOf<String>()
        val sentEtags = mutableListOf<String?>()

        val api: ListManagerApi = run {
            val unused = Proxy.newProxyInstance(
                ListManagerApi::class.java.classLoader,
                arrayOf(ListManagerApi::class.java),
            ) { _, method, _ -> error("Unexpected call: ${method.name}") } as ListManagerApi
            object : ListManagerApi by unused {
                override suspend fun getProductsIfChanged(etag: String?) =
                    respond("products", etag, products, productStatus)

                override suspend fun getDistributorsIfChanged(etag: String?) =
                    respond("distributors", etag, distributors, 200)
            }
        }

        private fun <T> respond(name: String, etag: String?, rows: List<T>, status: Int): Response<List<T>> {
            calls += name
            sentEtags += etag
            if (offline) throw IOException("offline")
            if (status != 200) return Response.error(status, "fail".toResponseBody())
            val current = "\"$name-${rows.hashCode()}\""
            if (etag == current) {
                val raw = okhttp3.Response.Builder()
                    .request(Request.Builder().url("https://example.test/").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(304)
                    .message("Not Modified")
                    .build()
                return Response.error("".toResponseBody(), raw)
            }
            return Response.success(rows, headersOf("ETag", current))
        }
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        etags = InMemoryCatalogEtagStore()
        server = FakeServer().apply {
            distributors = listOf(distributor(1, "Metro"))
            products = listOf(product(7, "Lapte Zuzu 1L"), product(8, "Paine alba"))
        }
    }

    @After fun close() { db.close() }

    private fun distributor(id: Long, name: String) = DistributorDTO(id, name, null, "2026-11-06")
    private fun product(id: Long, name: String, distributorId: Long = 1, aliases: String? = null) =
        ProductDTO(id, name, distributorId, aliases, "2026-11-06")

    private fun refresher() = CatalogRefresher(db, server.api, etags)

    private suspend fun seedOpenSession() {
        db.sessionDao().insert(SessionEntity(id = 10))
        db.sessionItemDao().insert(SessionItemEntity(id = 20, sessionId = 10, productId = 7, quantity = 2))
    }

    private suspend fun pending(type: OperationType, resource: ResourceType, resourceId: Long?, data: OperationData) {
        db.pendingOperationDao().insert(
            PendingOperationEntity(
                operationType = type.name,
                resourceType = resource.name,
                resourceId = resourceId,
                operationData = Json.encodeToString<OperationData>(data),
                status = OperationStatus.PENDING.name,
            )
        )
    }

    private fun countWrites(block: suspend () -> Unit): Int {
        val writes = AtomicInteger()
        val observer = object : InvalidationTracker.Observer("products", "distributors") {
            override fun onInvalidated(tables: Set<String>) { writes.incrementAndGet() }
        }
        db.invalidationTracker.addObserver(observer)
        runBlocking { block() }
        db.invalidationTracker.refreshVersionsAsync()
        Thread.sleep(200)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        db.invalidationTracker.removeObserver(observer)
        return writes.get()
    }

    @Test fun firstRunFillsTheCatalogAndMakesItSearchable() = runBlocking {
        val result = refresher().refresh()

        assertTrue(result is CatalogRefresher.Result.Updated)
        assertEquals(2, db.productDao().getAll().size)
        assertEquals(7L, db.productDao().searchFtsRaw("zuzu").single().id)
    }

    @Test fun unchangedCatalogSendsTheVersionAndWritesNothing() {
        runBlocking { refresher().refresh() }
        server.sentEtags.clear()

        var result: CatalogRefresher.Result? = null
        val writes = countWrites { result = refresher().refresh() }

        assertEquals(CatalogRefresher.Result.UpToDate, result)
        assertTrue(server.sentEtags.all { it != null })
        assertEquals(0, writes)
    }

    @Test fun newVersionWithIdenticalRowsWritesNothing() {
        runBlocking { refresher().refresh() }
        etags.put("products_etag", "\"older\"")
        etags.put("distributors_etag", "\"older\"")

        var result: CatalogRefresher.Result? = null
        val writes = countWrites { result = refresher().refresh() }

        assertEquals(CatalogRefresher.Result.UpToDate, result)
        assertEquals(0, writes)
    }

    @Test fun phoneOfflineDuringAnImportCatchesUpAndKeepsOpenWork() = runBlocking {
        refresher().refresh()
        seedOpenSession()
        pending(OperationType.ADD_SESSION_ITEM, ResourceType.SESSION_ITEM, null,
            OperationData.AddSessionItem(sessionId = 10, productId = 8, quantity = 1))

        server.offline = true
        server.products = server.products + (100L..102L).map { product(it, "Importat $it") }
        assertEquals(CatalogRefresher.Result.Failed, refresher().refresh())
        assertEquals(2, db.productDao().getAll().size)

        server.offline = false
        assertTrue(refresher().refresh() is CatalogRefresher.Result.Updated)

        assertEquals(5, db.productDao().getAll().size)
        assertEquals(2, db.sessionItemDao().getById(20)?.quantity)
        assertEquals(7L, db.sessionItemDao().getById(20)?.productId)
        assertEquals(1, db.pendingOperationDao().getUnfinished().size)
    }

    @Test fun updatedRowDoesNotDisturbAnOpenSessionItem() = runBlocking {
        refresher().refresh()
        seedOpenSession()

        server.products = listOf(product(7, "Lapte Zuzu 1,5L"), product(8, "Paine alba"))
        refresher().refresh()

        assertEquals("Lapte Zuzu 1,5L", db.productDao().getById(7)?.name)
        assertEquals(2, db.sessionItemDao().getById(20)?.quantity)
    }

    @Test fun serverRowCannotOverwriteAProductAwaitingCreation() = runBlocking {
        db.distributorDao().insert(DistributorEntity(1, "Metro"))
        db.productDao().insert(ProductEntity(50, "Creat offline", 1))
        pending(OperationType.CREATE_PRODUCT, ResourceType.PRODUCT, 50,
            OperationData.CreateProduct("Creat offline", 1, null))
        server.products = listOf(product(50, "Alt produs de pe server"), product(7, "Lapte"))

        val result = refresher().refresh() as CatalogRefresher.Result.Updated

        assertEquals("Creat offline", db.productDao().getById(50)?.name)
        assertEquals("Lapte", db.productDao().getById(7)?.name)
        assertEquals(1, result.skippedRows)
    }

    @Test fun skippedRowsAreCheckedAgainOnTheNextRefresh() = runBlocking {
        db.distributorDao().insert(DistributorEntity(1, "Metro"))
        db.productDao().insert(ProductEntity(50, "Creat offline", 1))
        pending(OperationType.CREATE_PRODUCT, ResourceType.PRODUCT, 50,
            OperationData.CreateProduct("Creat offline", 1, null))
        server.products = listOf(product(50, "Alt produs de pe server"))

        refresher().refresh()

        assertNull(etags.get("products_etag"))
    }

    @Test fun refreshDoesNotUndoAnOfflineEdit() = runBlocking {
        refresher().refresh()
        db.productDao().update(ProductEntity(7, "Editat offline", 1))
        pending(OperationType.UPDATE_PRODUCT, ResourceType.PRODUCT, 7,
            OperationData.UpdateProduct(7, "Editat offline", 1, null))
        etags.put("products_etag", null)

        refresher().refresh()

        assertEquals("Editat offline", db.productDao().getById(7)?.name)
    }

    @Test fun refreshDoesNotBringBackAnOfflineDelete() = runBlocking {
        refresher().refresh()
        db.productDao().deleteById(7)
        pending(OperationType.DELETE_PRODUCT, ResourceType.PRODUCT, 7, OperationData.DeleteProduct(7))
        etags.put("products_etag", null)

        refresher().refresh()

        assertNull(db.productDao().getById(7))
    }

    @Test fun pendingDistributorEditKeepsItsNameButNotItsProductsBack() = runBlocking {
        refresher().refresh()
        db.distributorDao().update(DistributorEntity(1, "Editat offline"))
        pending(OperationType.UPDATE_DISTRIBUTOR, ResourceType.DISTRIBUTOR, 1,
            OperationData.UpdateDistributor(1, "Editat offline"))
        server.products = server.products + product(100, "Importat")
        etags.put("distributors_etag", null)

        refresher().refresh()

        assertEquals("Editat offline", db.distributorDao().getById(1)?.distributorName)
        assertNotNull(db.productDao().getById(100))
    }

    @Test fun productsOfADistributorAwaitingCreationWait() = runBlocking {
        db.distributorDao().insert(DistributorEntity(5, "Creat offline"))
        pending(OperationType.CREATE_DISTRIBUTOR, ResourceType.DISTRIBUTOR, 5,
            OperationData.CreateDistributor("Creat offline"))
        server.distributors = listOf(distributor(1, "Metro"), distributor(5, "Alt furnizor"))
        server.products = listOf(product(7, "Lapte"), product(9, "Al altuia", distributorId = 5))

        val result = refresher().refresh() as CatalogRefresher.Result.Updated

        assertEquals("Creat offline", db.distributorDao().getById(5)?.distributorName)
        assertNotNull(db.productDao().getById(7))
        assertNull(db.productDao().getById(9))
        assertEquals(2, result.skippedRows)
    }

    @Test fun distributorNameHeldUnderAnotherIdDoesNotBlockTheRefresh() = runBlocking {
        db.distributorDao().insert(DistributorEntity(99, "Metro"))
        db.productDao().insert(ProductEntity(7, "Lapte", 99))
        seedOpenSession()
        server.distributors = listOf(distributor(1, "Metro"))
        server.products = listOf(product(7, "Lapte", 1))

        assertTrue(refresher().refresh() is CatalogRefresher.Result.Updated)

        assertNull(db.distributorDao().getById(99))
        assertEquals(1L, db.productDao().getById(7)?.distributorId)
        assertEquals(2, db.sessionItemDao().getById(20)?.quantity)
    }

    @Test fun twoDistributorsSwappingNamesBothLand() = runBlocking {
        server.distributors = listOf(distributor(1, "Metro"), distributor(2, "Auchan"))
        refresher().refresh()

        server.distributors = listOf(distributor(1, "Auchan"), distributor(2, "Metro"))
        assertTrue(refresher().refresh() is CatalogRefresher.Result.Updated)

        assertEquals("Auchan", db.distributorDao().getById(1)?.distributorName)
        assertEquals("Metro", db.distributorDao().getById(2)?.distributorName)
    }

    @Test fun productsAreFetchedBeforeTheirDistributors() = runBlocking {
        refresher().refresh()
        assertEquals(listOf("products", "distributors"), server.calls.take(2))
    }

    @Test fun rowGoneFromTheServerIsRemovedOnlyWhenNothingUsesIt() = runBlocking {
        server.products = listOf(
            product(7, "In sesiune"), product(8, "In inventar"), product(9, "In asteptare"),
            product(10, "Nefolosit"), product(11, "Ramane"),
        )
        refresher().refresh()
        seedOpenSession()
        db.inventoryItemDao().insert(
            InventoryItemEntity(name = "x", productId = 8, createdAt = 1, itemUuid = "uuid-1")
        )
        pending(OperationType.ADD_SESSION_ITEM, ResourceType.SESSION_ITEM, null,
            OperationData.AddSessionItem(sessionId = 10, productId = 9, quantity = 1))

        server.products = listOf(product(11, "Ramane"))
        refresher().refresh()

        assertNotNull(db.productDao().getById(7))
        assertNotNull(db.productDao().getById(8))
        assertNotNull(db.productDao().getById(9))
        assertNull(db.productDao().getById(10))
        assertEquals(2, db.sessionItemDao().getById(20)?.quantity)
    }

    @Test fun emptyServerListRemovesNothing() = runBlocking {
        refresher().refresh()
        server.products = emptyList()

        refresher().refresh()

        assertEquals(2, db.productDao().getAll().size)
    }

    @Test fun wipedDatabaseIgnoresTheSavedVersion() = runBlocking {
        refresher().refresh()
        db.productDao().deleteAll()
        db.distributorDao().deleteAll()
        server.sentEtags.clear()

        refresher().refresh()

        assertTrue(server.sentEtags.all { it == null })
        assertEquals(2, db.productDao().getAll().size)
    }

    @Test fun invalidParentRollsEverythingBackAndForcesAFullFetch() = runBlocking {
        refresher().refresh()
        val before = db.productDao().getAll()
        server.products = listOf(product(7, "Schimbat"), product(30, "Orfan", distributorId = 999))

        assertEquals(CatalogRefresher.Result.Failed, refresher().refresh())

        assertEquals(before, db.productDao().getAll())
        assertNull(etags.get("products_etag"))
    }

    @Test fun serverErrorKeepsTheCache() = runBlocking {
        refresher().refresh()
        server.productStatus = 503

        assertEquals(CatalogRefresher.Result.Failed, refresher().refresh())

        assertEquals(2, db.productDao().getAll().size)
    }
}
