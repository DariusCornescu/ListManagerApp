package com.darius.listmanager.sync

import androidx.room.Room
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.local.entity.SessionEntity
import com.darius.listmanager.data.local.entity.SessionItemEntity
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.data.repository.SessionRepository
import com.darius.listmanager.data.repository.SessionTransaction
import com.darius.listmanager.data.workspace.SessionSnapshotStore
import com.darius.listmanager.network.AddItemRequest
import com.darius.listmanager.network.GlobalSessionItemDTO
import com.darius.listmanager.network.ListManagerApi
import com.darius.listmanager.network.UpdateItemRequest
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.IOException
import java.lang.reflect.Proxy

/** EX-25: the client path out of a 409 converges, loses nothing and cannot spin. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncReviewResolverTest {
    private lateinit var db: AppDatabase
    private val context get() = RuntimeEnvironment.getApplication()

    /** The server's copy of item 500, driven by the optimistic lock like the real one. */
    private inner class FakeServer(var quantity: Int = 1, var version: Int = 1) {
        var itemExists = true
        var updateAttempts = 0
        var offlineReads = false
        val appliedKeys = mutableListOf<String?>()
        val addedProducts = mutableListOf<Long>()

        /** Runs before each write, as another phone racing this one. */
        var rivalWrite: () -> Unit = {}

        private fun dto() = GlobalSessionItemDTO(
            id = ITEM_ID, session_id = SESSION_ID, product_id = PRODUCT_ID, quantity = quantity,
            version = version, created_at = "2026-11-06T00:00:00", updated_at = "2026-11-06T00:00:00",
            product_name = "Product", distributor_name = "Supplier",
        )

        val api: ListManagerApi = run {
            val unused = Proxy.newProxyInstance(
                ListManagerApi::class.java.classLoader, arrayOf(ListManagerApi::class.java),
            ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as ListManagerApi

            object : ListManagerApi by unused {
                override suspend fun getSessionItems(sessionId: Long): Response<List<GlobalSessionItemDTO>> {
                    if (offlineReads) throw IOException("offline")
                    return Response.success(if (itemExists) listOf(dto()) else emptyList())
                }

                override suspend fun updateSessionItem(itemId: Long, request: UpdateItemRequest): Response<GlobalSessionItemDTO> {
                    updateAttempts++
                    rivalWrite()
                    if (request.version != version) return Response.error(409, "stale".toResponseBody())
                    quantity = request.quantity
                    version++
                    appliedKeys += request.idempotency_key
                    return Response.success(dto())
                }

                override suspend fun addSessionItem(sessionId: Long, request: AddItemRequest): Response<GlobalSessionItemDTO> {
                    addedProducts += request.product_id
                    return Response.success(
                        dto().copy(id = 501, product_id = request.product_id, quantity = request.quantity, version = 1)
                    )
                }
            }
        }
    }

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.distributorDao().insert(DistributorEntity(1, "Supplier"))
        db.productDao().insert(ProductEntity(PRODUCT_ID, "Product", 1))
        db.productDao().insert(ProductEntity(OTHER_PRODUCT_ID, "Other", 1))
        db.sessionDao().insert(SessionEntity(id = SESSION_ID))
        db.sessionItemDao().insert(SessionItemEntity(ITEM_ID, SESSION_ID, PRODUCT_ID, 1, 1))
        Unit
    }

    @After fun close() { db.close() }

    private fun service(api: ListManagerApi) = SyncService(
        context, db, api, currentUserId = { 0L }, now = { Long.MAX_VALUE - 1 },
    )

    private fun resolver(api: ListManagerApi): SyncReviewResolver {
        val sync = service(api)
        return SyncReviewResolver(db, api) { sync.syncPendingOperations() }
    }

    private fun repository(api: ListManagerApi) = SessionRepository(
        db.sessionDao(), db.sessionItemDao(), api,
        PendingOperationRepository(db.pendingOperationDao()), SessionTransaction.room(db),
        currentUserId = { 0L },
    )

    private fun offlineApi(): ListManagerApi {
        val unused = Proxy.newProxyInstance(
            ListManagerApi::class.java.classLoader, arrayOf(ListManagerApi::class.java),
        ) { _, _, _ -> throw IOException("offline") } as ListManagerApi
        return object : ListManagerApi by unused {
            override suspend fun updateSessionItem(itemId: Long, request: UpdateItemRequest): Response<GlobalSessionItemDTO> =
                throw IOException("offline")
            override suspend fun addSessionItem(sessionId: Long, request: AddItemRequest): Response<GlobalSessionItemDTO> =
                throw IOException("offline")
        }
    }

    /** Edit offline against version 1, while the server has moved on, then hit the 409. */
    private suspend fun heldConflict(server: FakeServer, localQuantity: Int = 7): Long {
        repository(offlineApi()).setItemQuantity(SESSION_ID, PRODUCT_ID, localQuantity)
        service(server.api).syncPendingOperations()
        return db.pendingOperationDao().getUnfinished().single().id
    }

    @Test fun conflict_isMarkedForReview_withoutRetryingOrLosingTheEdit() = runBlocking {
        val server = FakeServer(quantity = 4, version = 3)
        val id = heldConflict(server)

        val held = db.pendingOperationDao().getById(id)!!
        assertTrue(held.isHeldForReview)
        assertTrue(held.isVersionConflict)
        assertEquals(1, server.updateAttempts)

        service(server.api).syncPendingOperations()
        assertEquals(1, server.updateAttempts)
        assertEquals(7, db.sessionItemDao().getItem(SESSION_ID, PRODUCT_ID)!!.quantity)
    }

    @Test fun keepMine_rebasesOntoTheServerVersion_andApplies() = runBlocking {
        val server = FakeServer(quantity = 4, version = 3)
        val id = heldConflict(server)

        assertEquals(ReviewOutcome.RESOLVED, resolver(server.api).keepMine(id))

        assertEquals(7, server.quantity)
        assertEquals(4, server.version)
        val local = db.sessionItemDao().getItem(SESSION_ID, PRODUCT_ID)!!
        assertEquals(7, local.quantity)
        assertEquals(4, local.version)
        assertTrue(db.pendingOperationDao().getUnfinished().isEmpty())
    }

    @Test fun keepMine_convergesThroughAStormOf409s() = runBlocking {
        val server = FakeServer(quantity = 4, version = 3)
        val id = heldConflict(server)

        // The rival wins the next three rounds, then stops.
        var rivalWins = 3
        server.rivalWrite = {
            if (rivalWins > 0) {
                rivalWins--
                server.quantity += 10
                server.version++
            }
        }
        val versionBefore = server.version

        assertEquals(ReviewOutcome.RESOLVED, resolver(server.api).keepMine(id))

        assertEquals(7, server.quantity)
        assertEquals(versionBefore + 4, server.version) // Three rival writes, then ours.
        assertEquals(1, server.appliedKeys.size) // Applied exactly once, despite the retries.
        assertTrue(db.pendingOperationDao().getUnfinished().isEmpty())
        assertEquals(server.version, db.sessionItemDao().getItem(SESSION_ID, PRODUCT_ID)!!.version)
    }

    @Test fun keepMine_givesUpAfterABoundedStorm_andKeepsTheEdit() = runBlocking {
        val server = FakeServer(quantity = 4, version = 3)
        val id = heldConflict(server)
        server.rivalWrite = { server.version++ }
        val attemptsBefore = server.updateAttempts

        assertEquals(ReviewOutcome.STILL_CONFLICTED, resolver(server.api).keepMine(id))

        assertEquals(5, server.updateAttempts - attemptsBefore)
        assertTrue(db.pendingOperationDao().getById(id)!!.isVersionConflict)
        assertEquals(7, db.sessionItemDao().getItem(SESSION_ID, PRODUCT_ID)!!.quantity)
    }

    @Test fun keepMine_offline_changesNothing() = runBlocking {
        val server = FakeServer(quantity = 4, version = 3)
        val id = heldConflict(server)
        val before = db.pendingOperationDao().getById(id)!!
        server.offlineReads = true

        assertEquals(ReviewOutcome.NO_CONNECTION, resolver(server.api).keepMine(id))

        assertEquals(before.operationData, db.pendingOperationDao().getById(id)!!.operationData)
        assertTrue(db.pendingOperationDao().getById(id)!!.isHeldForReview)
    }

    @Test fun keepMine_reportsAnItemSomeoneElseRemoved() = runBlocking {
        val server = FakeServer(quantity = 4, version = 3)
        val id = heldConflict(server)
        server.itemExists = false

        assertEquals(ReviewOutcome.ITEM_GONE, resolver(server.api).keepMine(id))
        assertTrue(db.pendingOperationDao().getById(id)!!.isHeldForReview)
    }

    @Test fun resolvingOneConflict_unblocksTheRestOfTheQueue() = runBlocking {
        val server = FakeServer(quantity = 4, version = 3)
        val id = heldConflict(server)
        repository(offlineApi()).addOrIncrementItem(SESSION_ID, OTHER_PRODUCT_ID, 2)

        // Blocked behind the conflict.
        service(server.api).syncPendingOperations()
        assertTrue(server.addedProducts.isEmpty())

        assertEquals(ReviewOutcome.RESOLVED, resolver(server.api).keepMine(id))

        assertEquals(listOf(OTHER_PRODUCT_ID), server.addedProducts)
        assertTrue(db.pendingOperationDao().getUnfinished().isEmpty())
    }

    @Test fun discard_acceptsTheServerValue_andLetsSnapshotsResume() = runBlocking {
        val server = FakeServer(quantity = 4, version = 3)
        val id = heldConflict(server)
        // A conflict blocks snapshots, so the local value cannot change under the user.
        assertFalse(SessionSnapshotStore(db) { 0L }.refresh(SESSION_ID) { server.api.getSessionItems(SESSION_ID) })

        assertEquals(ReviewOutcome.RESOLVED, resolver(server.api).discard(id))

        assertNull(db.pendingOperationDao().getById(id))
        assertTrue(SessionSnapshotStore(db) { 0L }.refresh(SESSION_ID) { server.api.getSessionItems(SESSION_ID) })
        assertEquals(4, db.sessionItemDao().getItem(SESSION_ID, PRODUCT_ID)!!.quantity)
        assertEquals(4, server.quantity)
    }

    @Test fun discard_dropsLaterEditsOfThatItem_butNotOtherWork() = runBlocking {
        val server = FakeServer(quantity = 4, version = 3)
        val id = heldConflict(server)
        repository(offlineApi()).setItemQuantity(SESSION_ID, PRODUCT_ID, 9)
        repository(offlineApi()).addOrIncrementItem(SESSION_ID, OTHER_PRODUCT_ID, 2)
        assertEquals(3, db.pendingOperationDao().getUnfinished().size)

        resolver(server.api).discard(id)

        assertEquals(listOf(OTHER_PRODUCT_ID), server.addedProducts)
        assertEquals(4, server.quantity)
        assertTrue(db.pendingOperationDao().getUnfinished().isEmpty())
    }

    @Test fun retry_releasesARejectedOperation() = runBlocking {
        val server = FakeServer()
        repository(offlineApi()).addOrIncrementItem(SESSION_ID, OTHER_PRODUCT_ID, 2)
        val op = db.pendingOperationDao().getUnfinished().single()
        PendingOperationRepository(db.pendingOperationDao()) { 0L }.holdForReview(op.id, "Server returned 400")

        assertEquals(ReviewOutcome.RESOLVED, resolver(server.api).retry(op.id))
        assertEquals(listOf(OTHER_PRODUCT_ID), server.addedProducts)
    }

    private companion object {
        const val SESSION_ID = 10L
        const val ITEM_ID = 500L
        const val PRODUCT_ID = 7L
        const val OTHER_PRODUCT_ID = 8L
    }
}
