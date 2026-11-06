package com.darius.listmanager.repository

import androidx.room.Room
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.*
import com.darius.listmanager.data.repository.*
import com.darius.listmanager.network.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CatalogFirstRunTest {
    private lateinit var db: AppDatabase
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }
    private fun api(parentId: Long = 1, failParents: Boolean = false): ListManagerApi {
        val unused = Proxy.newProxyInstance(ListManagerApi::class.java.classLoader, arrayOf(ListManagerApi::class.java)) { _, m, _ ->
            error("Unexpected call: ${m.name}")
        } as ListManagerApi
        return object : ListManagerApi by unused {
            override suspend fun getProducts(search: String?) = Response.success(listOf(ProductDTO(7, "Lapte Zuzu 1L", parentId, "lapte, zuzu", "2026-09-11")))
            override suspend fun getDistributors(): Response<List<DistributorDTO>> =
                if (failParents) Response.error(503, "unavailable".toResponseBody())
                else Response.success(listOf(DistributorDTO(1, "Updated supplier", null, "2026-09-11")))
        }
    }
    private fun repo(api: ListManagerApi) = ProductRepository(db.productDao(), PendingOperationRepository(db.pendingOperationDao()), context, api, db) { true }
    private suspend fun seedSession() {
        db.distributorDao().insert(DistributorEntity(1, "Original supplier"))
        db.productDao().insert(ProductEntity(7, "Original product", 1))
        db.productDao().insert(ProductEntity(8, "Local pending product", 1))
        db.sessionDao().insert(SessionEntity(id = 10))
        db.sessionItemDao().insert(SessionItemEntity(id = 20, sessionId = 10, productId = 7, quantity = 2))
    }
    @Test fun firstFetchCachesParentsAndMakesProductSearchable() = runBlocking {
        assertEquals(1, repo(api()).getAll().size)
        assertEquals("Updated supplier", db.distributorDao().getById(1)?.distributorName)
        assertEquals(7L, db.productDao().searchFtsRaw("zuzu").single().id)
    }
    @Test fun productAndDistributorRefreshPreserveSessionAndLocalOnlyProduct() = runBlocking {
        seedSession()
        repo(api()).getAll()
        DistributorRepository(db.distributorDao(), api(), PendingOperationRepository(db.pendingOperationDao()), context) { true }.getAll()
        assertEquals(2, db.sessionItemDao().getById(20)?.quantity)
        assertEquals("Lapte Zuzu 1L", db.productDao().getById(7)?.name)
        assertNotNull(db.productDao().getById(8))
    }
    @Test fun failedParentFetchLeavesCacheUntouched() = runBlocking {
        seedSession()
        assertEquals(2, repo(api(failParents = true)).getAll().size)
        assertEquals("Original product", db.productDao().getById(7)?.name)
        assertEquals(2, db.sessionItemDao().getById(20)?.quantity)
    }
    @Test fun invalidForeignKeyRollsBackBothCatalogHalves() = runBlocking {
        seedSession()
        repo(api(parentId = 999)).getAll()
        assertEquals("Original supplier", db.distributorDao().getById(1)?.distributorName)
        assertEquals("Original product", db.productDao().getById(7)?.name)
        assertEquals(2, db.sessionItemDao().getById(20)?.quantity)
    }
}
