package com.darius.listmanager.workspace

import androidx.room.Room
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.data.local.entity.SessionEntity
import com.darius.listmanager.data.local.entity.SessionItemEntity
import com.darius.listmanager.data.model.OperationData
import com.darius.listmanager.data.workspace.Workspace
import com.darius.listmanager.data.workspace.WorkspaceSessionResolver
import com.darius.listmanager.network.DistributorDTO
import com.darius.listmanager.network.GlobalSessionDTO
import com.darius.listmanager.network.GlobalSessionItemDTO
import com.darius.listmanager.network.ListManagerApi
import com.darius.listmanager.network.ProductDTO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import retrofit2.Response
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceSessionResolverTest {
    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun close() = database.close()

    @Test
    fun freshInstallCachesCatalogBeforePullingExistingSessionItems() = runBlocking {
        val result = WorkspaceSessionResolver(database, api()).resolve(Workspace.Personal)

        assertEquals(WorkspaceSessionResolver.ResolveResult.Resolved(10), result)
        assertNotNull(database.distributorDao().getById(1))
        assertNotNull(database.productDao().getById(7))
        val item = database.sessionItemDao().getItem(10, 7)
        assertNotNull(item)
        assertEquals(2, item?.quantity)
        assertEquals("Lapte Zuzu 1L", database.sessionItemDao().getItemsWithProduct(10).single().productName)
    }

    @Test
    fun catalogFailurePreservesExistingSessionSnapshot() = runBlocking {
        database.distributorDao().insert(DistributorEntity(1, "Supplier"))
        database.productDao().insert(ProductEntity(7, "Cached product", 1))
        database.sessionDao().insert(SessionEntity(id = 10))
        database.sessionItemDao().insert(SessionItemEntity(500, 10, 7, 8))

        WorkspaceSessionResolver(database, api(catalogAvailable = false)).resolve(Workspace.Personal)

        assertEquals(8, database.sessionItemDao().getItem(10, 7)?.quantity)
        assertEquals("Cached product", database.productDao().getById(7)?.name)
    }

    @Test
    fun queuedOfflineCreationPreventsResolverFromReplacingLocalSession() = runBlocking {
        val data = OperationData.CreateSession(-1, "stable", "Current Session", null)
        database.pendingOperationDao().insert(
            PendingOperationEntity(
                operationType = "CREATE_SESSION",
                resourceType = "SESSION",
                resourceId = -1,
                operationData = Json.encodeToString<OperationData>(data),
                status = "WAITING",
                userId = 0,
            )
        )
        val neverCalled = Proxy.newProxyInstance(
            ListManagerApi::class.java.classLoader,
            arrayOf(ListManagerApi::class.java),
        ) { _, method, _ -> error("Resolver called ${method.name}") } as ListManagerApi

        val result = WorkspaceSessionResolver(database, neverCalled).resolve(Workspace.Personal)

        assertEquals(WorkspaceSessionResolver.ResolveResult.Offline, result)
    }

    private fun api(catalogAvailable: Boolean = true): ListManagerApi {
        val unused = Proxy.newProxyInstance(
            ListManagerApi::class.java.classLoader,
            arrayOf(ListManagerApi::class.java),
        ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as ListManagerApi
        return object : ListManagerApi by unused {
            override suspend fun getActiveSession(teamId: Long?) = Response.success(
                GlobalSessionDTO(10, "Current Session", true, "2026-09-13", null, 0, 1, null)
            )

            override suspend fun getDistributorsIfChanged(etag: String?): Response<List<DistributorDTO>> =
                if (catalogAvailable) {
                    Response.success(listOf(DistributorDTO(1, "Supplier", null, "2026-09-13")))
                } else {
                    Response.error(503, "unavailable".toResponseBody())
                }

            override suspend fun getProductsIfChanged(etag: String?) = Response.success(
                listOf(ProductDTO(7, "Lapte Zuzu 1L", 1, "lapte, zuzu", "2026-09-13"))
            )

            override suspend fun getSessionItems(sessionId: Long) = Response.success(
                listOf(
                    GlobalSessionItemDTO(
                        500, sessionId, 7, 2, 0,
                        "2026-09-13", "2026-09-13", "Lapte Zuzu 1L", "Supplier",
                    )
                )
            )
        }
    }
}
