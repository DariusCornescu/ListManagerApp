package com.darius.listmanager.repository

import androidx.room.Room
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.*
import com.darius.listmanager.data.model.*
import com.darius.listmanager.data.repository.*
import com.darius.listmanager.data.workspace.SessionSnapshotStore
import com.darius.listmanager.network.*
import com.darius.listmanager.sync.SyncService
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SessionSyncRecoveryTest {
    private lateinit var db: AppDatabase
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.distributorDao().insert(DistributorEntity(1, "Supplier"))
        db.productDao().insert(ProductEntity(7, "Product", 1))
        db.sessionDao().insert(SessionEntity(id = 10))
        Unit
    }
    @After fun close() { db.close() }
    private fun dto(quantity: Int = 3, version: Int = 5) = GlobalSessionItemDTO(
        id = 500, session_id = 10, product_id = 7, quantity = quantity, version = version,
        created_at = "2026-09-11T00:00:00", updated_at = "2026-09-11T00:00:00", product_name = "Product", distributor_name = "Supplier",
    )
    private fun error(code: Int): Response<GlobalSessionItemDTO> = Response.error(code, "error".toResponseBody())
    private fun api(
        add: suspend (AddItemRequest) -> Response<GlobalSessionItemDTO> = { Response.success(dto(it.quantity)) },
        addWithSession: (suspend (Long, AddItemRequest) -> Response<GlobalSessionItemDTO>)? = null,
        update: suspend (Long, UpdateItemRequest) -> Response<GlobalSessionItemDTO> = { _, r -> Response.success(dto(r.quantity, r.version + 1)) },
        delete: suspend (Long) -> Response<Unit> = { Response.success(Unit) },
        createSession: (suspend (CreateSessionRequest) -> Response<GlobalSessionDTO>)? = null,
        completeSession: (suspend (Long) -> Response<CompleteSessionResponse>)? = null,
    ): ListManagerApi {
        val unused = Proxy.newProxyInstance(ListManagerApi::class.java.classLoader, arrayOf(ListManagerApi::class.java)) { _, m, _ ->
            error("Unexpected API call: ${m.name}")
        } as ListManagerApi
        return object : ListManagerApi by unused {
            override suspend fun addSessionItem(sessionId: Long, request: AddItemRequest) =
                addWithSession?.invoke(sessionId, request) ?: add(request)
            override suspend fun updateSessionItem(itemId: Long, request: UpdateItemRequest) = update(itemId, request)
            override suspend fun deleteSessionItem(itemId: Long) = delete(itemId)
            override suspend fun createSession(request: CreateSessionRequest) =
                createSession?.invoke(request) ?: error("Unexpected API call: createSession")
            override suspend fun completeSession(sessionId: Long) =
                completeSession?.invoke(sessionId) ?: error("Unexpected API call: completeSession")
        }
    }
    private fun repo(api: ListManagerApi) = SessionRepository(db.sessionDao(), db.sessionItemDao(), api,
        PendingOperationRepository(db.pendingOperationDao()), SessionTransaction.room(db))
    private fun worker(api: ListManagerApi) = SyncService(
        context,
        db,
        api,
        currentUserId = { 0L },
        now = { Long.MAX_VALUE - 1 },
    )
    private suspend fun queuedAdd() {
        repo(api(add = { throw IOException("offline") })).addOrIncrementItem(10, 7, 3)
    }

    private fun sessionDto(
        id: Long,
        clientUuid: String,
        active: Boolean = true,
    ) = GlobalSessionDTO(
        id = id,
        name = "Current Session",
        is_active = active,
        created_at = "2026-09-13T00:00:00",
        completed_at = if (active) null else "2026-09-13T00:01:00",
        version = 1,
        owner_user_id = 0,
        team_id = null,
        client_uuid = clientUuid,
    )

    private fun completeDto(session: GlobalSessionDTO) = CompleteSessionResponse(
        session = session.copy(is_active = false, completed_at = "2026-09-13T00:01:00"),
        items_by_distributor = emptyMap(),
        distributor_count = 1,
        total_items = 2,
    )

    @Test fun offlineSession_survivesRestart_promotesAndCompletesInOrder() = runBlocking {
        db.sessionDao().deleteById(10)
        db.productDao().insert(ProductEntity(8, "Second product", 1))
        val localRepository = repo(api())
        val local = localRepository.getOrCreateActiveSession(null)
        assertTrue(local.id < 0)
        localRepository.addOrIncrementItem(local.id, 7, 2)
        localRepository.addOrIncrementItem(local.id, 8, 1)
        localRepository.completeSessionWriteThrough(local.id)

        val calls = mutableListOf<String>()
        var createdRequest: CreateSessionRequest? = null
        val remote = api(
            createSession = { request ->
                calls += "create"
                createdRequest = request
                Response.success(sessionDto(50, requireNotNull(request.client_uuid)))
            },
            addWithSession = { sessionId, request ->
                calls += "add:${request.product_id}"
                assertEquals(50L, sessionId)
                Response.success(
                    GlobalSessionItemDTO(
                        id = 500 + request.product_id,
                        session_id = sessionId,
                        product_id = request.product_id,
                        quantity = request.quantity,
                        version = 1,
                        created_at = "2026-09-13T00:00:00",
                        updated_at = "2026-09-13T00:00:00",
                        product_name = null,
                        distributor_name = null,
                    )
                )
            },
            completeSession = { sessionId ->
                calls += "complete"
                assertEquals(50L, sessionId)
                Response.success(completeDto(sessionDto(50, requireNotNull(createdRequest?.client_uuid))))
            },
        )

        // New service/repository instances model a process restart while Room
        // remains the durable source of truth.
        assertTrue(worker(remote).syncPendingOperations() is SyncResult.Success)

        assertEquals(listOf("create", "add:7", "add:8", "complete"), calls)
        assertNull(db.sessionDao().getById(local.id))
        assertEquals(false, db.sessionDao().getById(50)?.isActive)
        assertEquals(setOf(7L, 8L), db.sessionItemDao().getItems(50).map { it.productId }.toSet())
        assertTrue(db.pendingOperationDao().getUnfinishedForUser(0).isEmpty())
    }

    @Test fun lostCreateResponse_reusesClientUuidAndPromotesOnlyOnce() = runBlocking {
        db.sessionDao().deleteById(10)
        val local = repo(api()).getOrCreateActiveSession(null)
        val requests = mutableListOf<CreateSessionRequest>()
        var loseResponse = true
        val remote = api(createSession = { request ->
            requests += request
            if (loseResponse) {
                loseResponse = false
                throw IOException("response lost after commit")
            }
            Response.success(sessionDto(50, requireNotNull(request.client_uuid)))
        })
        val service = worker(remote)

        assertTrue(service.syncPendingOperations() is SyncResult.Error)
        assertEquals("FAILED", db.pendingOperationDao().getUnfinishedForUser(0).single().status)
        assertTrue(service.syncPendingOperations() is SyncResult.Success)

        assertEquals(2, requests.size)
        assertEquals(requests[0].client_uuid, requests[1].client_uuid)
        assertNull(db.sessionDao().getById(local.id))
        assertEquals(requests[0].client_uuid, db.sessionDao().getById(50)?.clientUuid)
        assertTrue(db.pendingOperationDao().getUnfinishedForUser(0).isEmpty())
    }

    @Test fun unauthorized_retainsIdentity_andRetriesAfterAuthentication() = runBlocking {
        queuedAdd()
        val before = db.pendingOperationDao().getUnfinished().single()
        assertTrue(worker(api(add = { error(401) })).syncPendingOperations() is SyncResult.Error)
        val failed = db.pendingOperationDao().getUnfinished().single()
        assertEquals(before.operationData, failed.operationData)
        assertEquals("FAILED", failed.status)
        assertTrue(failed.scheduledFor < Long.MAX_VALUE)
        assertTrue(worker(api()).syncPendingOperations() is SyncResult.Success)
        assertTrue(db.pendingOperationDao().getUnfinished().isEmpty())
    }

    @Test fun validationFailure_isRetained_andNotRetriedForever() = runBlocking {
        queuedAdd()
        val calls = AtomicInteger()
        val service = worker(api(add = { calls.incrementAndGet(); error(422) }))
        service.syncPendingOperations()
        service.syncPendingOperations()
        assertEquals(1, calls.get())
        assertEquals(Long.MAX_VALUE, db.pendingOperationDao().getUnfinished().single().scheduledFor)
        assertEquals(3, db.sessionItemDao().getItem(10, 7)!!.quantity)
    }

    @Test fun conflict_blocksLaterEdits_andPreservesLocalQuantity() = runBlocking {
        db.sessionItemDao().insert(SessionItemEntity(500, 10, 7, 1, 1))
        val offline = repo(api(update = { _, _ -> throw IOException("offline") }))
        offline.setItemQuantity(10, 7, 4)
        offline.addOrIncrementItem(10, 7, 2)
        val calls = AtomicInteger()
        val service = worker(api(add = { fail("Later edit overtook conflict"); Response.success(dto()) },
            update = { _, _ -> calls.incrementAndGet(); error(409) }))
        service.syncPendingOperations()
        service.syncPendingOperations()
        assertEquals(1, calls.get())
        assertEquals(2, db.pendingOperationDao().getUnfinished().size)
        assertEquals(6, db.sessionItemDao().getItem(10, 7)!!.quantity)
        assertFalse(SessionSnapshotStore(db).refresh(10) { Response.success(emptyList()) })
    }

    @Test fun interruptedKeyedRequest_isRecoveredAfterCancellation() = runBlocking {
        queuedAdd()
        val original = db.pendingOperationDao().getUnfinished().single().operationData
        val canceled = worker(api(add = { throw CancellationException("stopped") }))
        try { canceled.syncPendingOperations(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertFalse(canceled.syncStatus.value.isSyncing)
        assertEquals("IN_PROGRESS", db.pendingOperationDao().getUnfinished().single().status)
        assertEquals(original, db.pendingOperationDao().getUnfinished().single().operationData)
        worker(api()).syncPendingOperations()
        assertTrue(db.pendingOperationDao().getUnfinished().isEmpty())
    }

    @Test fun competingWorkers_sendOnlyOnce() = runBlocking {
        queuedAdd()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val remote = api(add = { calls.incrementAndGet(); entered.complete(Unit); release.await(); Response.success(dto()) })
        withTimeout(10000) {
            val first = async { worker(remote).syncPendingOperations() }
            entered.await()
            val second = async { worker(remote).syncPendingOperations() }
            release.complete(Unit)
            assertTrue(first.await() is SyncResult.Success)
            assertTrue(second.await() is SyncResult.Success)
        }
        assertEquals(1, calls.get())
    }

    @Test fun pendingEdit_preventsSnapshotReplacement() = runBlocking {
        queuedAdd()
        assertFalse(SessionSnapshotStore(db).refresh(10) { fail("Snapshot fetched despite pending edit"); Response.success(emptyList()) })
        assertEquals(3, db.sessionItemDao().getItem(10, 7)!!.quantity)
    }

    @Test fun missingSnapshotBody_preservesCachedItems() = runBlocking {
        db.sessionItemDao().insert(SessionItemEntity(500, 10, 7, 8))
        assertFalse(SessionSnapshotStore(db).refresh(10) { Response.success(null) })
        assertEquals(8, db.sessionItemDao().getItem(10, 7)!!.quantity)
    }

    @Test fun invalidSnapshot_rollsBackDeletion() = runBlocking {
        db.sessionItemDao().insert(SessionItemEntity(500, 10, 7, 8))
        try {
            SessionSnapshotStore(db).refresh(10) { Response.success(listOf(dto().copy(product_id = 999))) }
            fail("Expected foreign key failure")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(8, db.sessionItemDao().getItem(10, 7)!!.quantity)
    }

    @Test fun queuedAddThenUpdate_rekeysAndRebasesUnsentUpdate() = runBlocking {
        queuedAdd()
        repo(api(update = { _, _ -> fail("Unsent update overtook add"); Response.success(dto()) })).setItemQuantity(10, 7, 7)
        val service = worker(api(update = { id, request ->
            assertEquals(500L, id)
            assertEquals(5, request.version)
            assertEquals(7, request.quantity)
            Response.success(dto(7, 6))
        }))
        assertTrue(service.syncPendingOperations() is SyncResult.Success)
        assertEquals(7, db.sessionItemDao().getItem(10, 7)!!.quantity)
        assertEquals(6, db.sessionItemDao().getItem(10, 7)!!.version)
        assertTrue(db.pendingOperationDao().getUnfinished().isEmpty())
    }

    @Test fun queuedAddThenDelete_usesServerId_withoutResurrectingLocalRow() = runBlocking {
        queuedAdd()
        repo(api()).deleteItem(db.sessionItemDao().getItem(10, 7)!!.id)
        val calls = AtomicInteger()
        assertTrue(worker(api(delete = { id ->
            calls.incrementAndGet()
            assertEquals(500L, id)
            assertNull(db.sessionItemDao().getItem(10, 7))
            Response.success(Unit)
        })).syncPendingOperations() is SyncResult.Success)
        assertEquals(1, calls.get())
        assertNull(db.sessionItemDao().getItem(10, 7))
    }

    @Test fun backoff_preventsLaterOperationOvertakingHead() = runBlocking {
        queuedAdd()
        repo(api()).addOrIncrementItem(10, 7, 1)
        val head = db.pendingOperationDao().getUnfinished().first()
        db.pendingOperationDao().markAsFailed(head.id, error = "offline", scheduledFor = 200)
        val service = SyncService(
            context,
            db,
            api(add = { fail("Sent during backoff"); Response.success(dto()) }),
            currentUserId = { 0L },
            now = { 100 },
        )
        val result = service.syncPendingOperations() as SyncResult.Error
        assertTrue(result.retryable)
        assertEquals(2, db.pendingOperationDao().getUnfinished().size)
    }
    @Test fun snapshotAndLocalEdit_areSerializedWithoutLosingEdit() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withTimeout(10000) {
            val snapshot = async {
                SessionSnapshotStore(db).refresh(10) {
                    entered.complete(Unit)
                    release.await()
                    Response.success(listOf(dto(2)))
                }
            }
            entered.await()
            val edit = async { repo(api(add = { Response.success(dto(3, 6)) })).addOrIncrementItem(10, 7, 1) }
            release.complete(Unit)
            assertTrue(snapshot.await())
            edit.await()
        }
        assertEquals(3, db.sessionItemDao().getItem(10, 7)!!.quantity)
    }

    @Test fun temporaryServerError_retriesWithoutChangingIdentity() = runBlocking {
        queuedAdd()
        val keys = mutableListOf<String?>()
        var failOnce = true
        val service = worker(api(add = {
            keys.add(it.idempotency_key)
            if (failOnce) { failOnce = false; error(503) } else Response.success(dto())
        }))
        assertTrue(service.syncPendingOperations() is SyncResult.Error)
        assertTrue(service.syncPendingOperations() is SyncResult.Success)
        assertEquals(2, keys.size)
        assertNotNull(keys[0])
        assertEquals(keys[0], keys[1])
    }

    @Test fun interruptedLegacyAddWithoutKey_requiresReview() = runBlocking {
        val id = db.pendingOperationDao().insert(PendingOperationEntity(
            operationType = "ADD_SESSION_ITEM", resourceType = "SESSION_ITEM", resourceId = null,
            operationData = """{"type":"com.darius.listmanager.data.model.OperationData.AddSessionItem","sessionId":10,"productId":7,"quantity":3}""",
            status = "IN_PROGRESS",
            userId = 0L,
        ))
        val result = worker(api(add = { fail("Unsafe legacy replay"); Response.success(dto()) })).syncPendingOperations()
        assertTrue(result is SyncResult.Error)
        assertEquals(Long.MAX_VALUE, db.pendingOperationDao().getById(id)!!.scheduledFor)
    }

    @Test fun lostClearResponse_isHeldWithoutRepeatingClear() = runBlocking {
        db.sessionItemDao().insert(SessionItemEntity(500, 10, 7, 8))
        val calls = AtomicInteger()
        val remote = object : ListManagerApi by api() {
            override suspend fun clearSession(sessionId: Long): Response<Unit> {
                calls.incrementAndGet()
                throw IOException("Reply lost")
            }
        }
        repo(remote).clearSession(10)
        assertNull(db.sessionItemDao().getItem(10, 7))
        assertEquals(Long.MAX_VALUE, db.pendingOperationDao().getUnfinished().single().scheduledFor)
        worker(remote).syncPendingOperations()
        assertEquals(1, calls.get())
        assertFalse(SessionSnapshotStore(db).refresh(10) { Response.success(listOf(dto(8))) })
    }

    @Test fun legacyPossiblySentPayload_isNeverRewrittenByAcknowledgement() = runBlocking {
        queuedAdd()
        val local = db.sessionItemDao().getItem(10, 7)!!
        val pending = PendingOperationRepository(db.pendingOperationDao())
        val id = pending.queueOperation(OperationType.UPDATE_SESSION_ITEM, ResourceType.SESSION_ITEM, local.id,
            OperationData.UpdateSessionItem(local.id, 9, 1, "already-sent-key"))
        val original = db.pendingOperationDao().getById(id)!!.operationData
        worker(api(update = { _, _ -> error(409) })).syncPendingOperations()
        assertEquals(original, db.pendingOperationDao().getById(id)!!.operationData)
    }

    @Test fun malformedSavedOperation_isRetainedForReview() = runBlocking {
        val id = db.pendingOperationDao().insert(PendingOperationEntity(
            operationType = "ADD_SESSION_ITEM", resourceType = "SESSION_ITEM", resourceId = null,
            operationData = "broken-json", status = "PENDING",
            userId = 0L,
        ))
        val result = worker(api()).syncPendingOperations() as SyncResult.Error
        assertFalse(result.retryable)
        assertEquals(Long.MAX_VALUE, db.pendingOperationDao().getById(id)!!.scheduledFor)
    }

    @Test fun catalogProductRefresh_preservesPendingSessionItem() = runBlocking {
        queuedAdd()
        db.productDao().insert(ProductEntity(7, "Renamed product", 1))
        assertEquals(3, db.sessionItemDao().getItem(10, 7)!!.quantity)
    }

    @Test fun catalogDistributorRefresh_preservesPendingSessionItem() = runBlocking {
        queuedAdd()
        db.distributorDao().upsert(DistributorEntity(1, "Renamed supplier"))
        assertEquals(3, db.sessionItemDao().getItem(10, 7)!!.quantity)
        assertNotNull(db.productDao().getById(7))
    }

}
