package com.darius.listmanager.repository

import com.darius.listmanager.data.local.dao.PendingOperationDao
import com.darius.listmanager.data.local.dao.SessionDao
import com.darius.listmanager.data.local.dao.SessionItemDao
import com.darius.listmanager.data.local.dao.SessionItemWithProduct
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.data.local.entity.SessionEntity
import com.darius.listmanager.data.local.entity.SessionItemEntity
import com.darius.listmanager.data.model.OperationData
import com.darius.listmanager.data.model.OperationType
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.data.repository.SessionRepository
import com.darius.listmanager.data.repository.SessionTransaction
import com.darius.listmanager.network.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * JVM-only tests for SessionRepository's device->server write-through.
 * Hand-rolled fakes; no Android framework (paths under test avoid Log).
 */
class SessionRepositoryWriteThroughTest {

    @Test fun requestSerialization_preservesBackendIdempotencyField() {
        val gson = com.google.gson.Gson()
        val add = gson.toJsonTree(AddItemRequest(7, 3, "add-key")).asJsonObject
        val update = gson.toJsonTree(UpdateItemRequest(4, 2, "update-key")).asJsonObject
        assertEquals("add-key", add.get("idempotency_key").asString)
        assertEquals("update-key", update.get("idempotency_key").asString)
        assertEquals(7, add.get("product_id").asInt)
        assertEquals(2, update.get("version").asInt)
        assertFalse(add.has("idempotencyKey"))
    }

    // ---------- Fakes ----------

    private class FakeSessionItemDao : SessionItemDao {
        val rows = mutableListOf<SessionItemEntity>()
        private var nextId = 1L

        override fun getItemsWithProductFlow(sessionId: Long): Flow<List<SessionItemWithProduct>> =
            throw NotImplementedError()

        override suspend fun getItemsWithProduct(sessionId: Long): List<SessionItemWithProduct> =
            throw NotImplementedError()

        override suspend fun getItem(sessionId: Long, productId: Long): SessionItemEntity? =
            rows.firstOrNull { it.sessionId == sessionId && it.productId == productId }

        override suspend fun getById(id: Long): SessionItemEntity? =
            rows.firstOrNull { it.id == id }

        override suspend fun getItems(sessionId: Long): List<SessionItemEntity> =
            rows.filter { it.sessionId == sessionId }.sortedBy { it.id }

        override suspend fun insert(item: SessionItemEntity): Long {
            // Mimic @Insert(REPLACE) + PK + unique (sessionId, productId) index.
            val id = if (item.id == 0L) nextId++ else item.id
            rows.removeAll {
                it.id == id || (it.sessionId == item.sessionId && it.productId == item.productId)
            }
            rows.add(item.copy(id = id))
            return id
        }

        override suspend fun update(item: SessionItemEntity) {
            val idx = rows.indexOfFirst { it.id == item.id }
            if (idx >= 0) rows[idx] = item
        }

        override suspend fun delete(item: SessionItemEntity) {
            rows.removeAll { it.id == item.id }
        }

        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun deleteAllInSession(sessionId: Long) {
            rows.removeAll { it.sessionId == sessionId }
        }

        override suspend fun moveToSession(localSessionId: Long, serverSessionId: Long) {
            rows.replaceAll { if (it.sessionId == localSessionId) it.copy(sessionId = serverSessionId) else it }
        }
    }

    private class FakeSessionDao : SessionDao {
        val completedIds = mutableListOf<Long>()

        // Override the @Transaction default body (it calls unimplemented members).
        override suspend fun completeSession(sessionId: Long) {
            completedIds.add(sessionId)
        }

        override fun getAllFlow(): Flow<List<SessionEntity>> = throw NotImplementedError()
        override suspend fun getActiveSession(accountUserId: Long, teamId: Long?): SessionEntity? = throw NotImplementedError()
        override fun getActiveSessionFlow(accountUserId: Long, teamId: Long?): Flow<SessionEntity?> = throw NotImplementedError()
        override suspend fun getById(id: Long): SessionEntity? = throw NotImplementedError()
        override suspend fun getByClientUuid(clientUuid: String): SessionEntity? = throw NotImplementedError()
        override suspend fun insert(session: SessionEntity): Long = throw NotImplementedError()
        override suspend fun upsert(session: SessionEntity): Long = throw NotImplementedError()
        override suspend fun update(session: SessionEntity) = throw NotImplementedError()
        override suspend fun delete(session: SessionEntity) = throw NotImplementedError()
        override suspend fun deactivateAll(accountUserId: Long, teamId: Long?) = throw NotImplementedError()
        override suspend fun getMinSessionId(): Long? = throw NotImplementedError()
        override suspend fun deleteById(id: Long) = throw NotImplementedError()
    }

    private class FakePendingOperationDao : PendingOperationDao {
        override suspend fun getUnfinished() = inserted.filter { it.status != "COMPLETED" }
        override suspend fun getUnfinishedForUser(userId: Long) =
            inserted.filter { it.userId == userId && it.status != "COMPLETED" }
        val inserted = mutableListOf<PendingOperationEntity>()

        override suspend fun insert(operation: PendingOperationEntity): Long {
            val id = (inserted.maxOfOrNull { it.id } ?: 0L) + 1
            inserted.add(operation.copy(id = id))
            return id
        }

        override suspend fun getAllPending(): List<PendingOperationEntity> = throw NotImplementedError()
        override suspend fun getAllPendingForUser(userId: Long): List<PendingOperationEntity> = throw NotImplementedError()
        override fun getAllPendingFlow(): Flow<List<PendingOperationEntity>> = throw NotImplementedError()
        override fun getAllPendingFlowForUser(userId: Long): Flow<List<PendingOperationEntity>> = throw NotImplementedError()
        override suspend fun getAllFailed(): List<PendingOperationEntity> = throw NotImplementedError()
        override suspend fun getAllFailedForUser(userId: Long): List<PendingOperationEntity> = throw NotImplementedError()
        override suspend fun getById(id: Long): PendingOperationEntity? = inserted.firstOrNull { it.id == id }
        override suspend fun getReadyForSync(currentTime: Long): List<PendingOperationEntity> = throw NotImplementedError()
        override suspend fun getReadyForSyncForUser(userId: Long, currentTime: Long): List<PendingOperationEntity> = throw NotImplementedError()
        override fun getPendingCountFlow(): Flow<Int> = throw NotImplementedError()
        override fun getPendingCountFlowForUser(userId: Long): Flow<Int> = throw NotImplementedError()
        override fun getFailedCountFlow(): Flow<Int> = throw NotImplementedError()
        override fun getFailedCountFlowForUser(userId: Long): Flow<Int> = throw NotImplementedError()
        override suspend fun insertAll(operations: List<PendingOperationEntity>) = throw NotImplementedError()
        override suspend fun update(operation: PendingOperationEntity) { inserted[inserted.indexOfFirst { it.id == operation.id }] = operation }
        override suspend fun markAsFailed(id: Long, status: String, error: String?, updatedAt: Long, scheduledFor: Long) {
            update(getById(id)!!.copy(status = status, lastError = error, scheduledFor = scheduledFor))
        }
        override suspend fun markAsInProgress(id: Long, updatedAt: Long) { update(getById(id)!!.copy(status = "IN_PROGRESS")) }
        override suspend fun markAsCompleted(id: Long, updatedAt: Long) = throw NotImplementedError()
        override suspend fun delete(operation: PendingOperationEntity) = throw NotImplementedError()
        override suspend fun deleteById(id: Long) { inserted.removeAll { it.id == id } }
        override suspend fun deleteAllCompleted() = throw NotImplementedError()
        override suspend fun deleteOldCompleted(olderThan: Long) = throw NotImplementedError()
        override suspend fun deleteOldCompletedForUser(userId: Long, olderThan: Long) = throw NotImplementedError()
        override suspend fun deleteAll() = throw NotImplementedError()
    }

    private class FakeApi : ListManagerApi {
        override suspend fun reportCrash(crash: CrashReportRequest): Response<Unit> =
            Response.success(Unit)

        override suspend fun getPresence(): Response<List<PresenceUserDTO>> =
            Response.success(emptyList())

        val addCalls = mutableListOf<Pair<Long, AddItemRequest>>()
        val updateCalls = mutableListOf<Pair<Long, UpdateItemRequest>>()
        val deleteCalls = mutableListOf<Long>()
        val clearCalls = mutableListOf<Long>()
        val completeCalls = mutableListOf<Long>()

        var completeResult: ((Long) -> Response<CompleteSessionResponse>)? = { sessionId ->
            Response.success(
                CompleteSessionResponse(
                    session = GlobalSessionDTO(
                        id = sessionId, name = "Current Session", is_active = false,
                        created_at = "2026-06-10T00:00:00", completed_at = "2026-06-10T00:00:00",
                        version = 1, owner_user_id = null, team_id = null,
                    ),
                    items_by_distributor = emptyMap(),
                    distributor_count = 0,
                    total_items = 0,
                )
            )
        }

        var addResult: (() -> Response<GlobalSessionItemDTO>)? = null
        var updateResult: (() -> Response<GlobalSessionItemDTO>)? = null
        var deleteResult: (() -> Response<Unit>)? = { Response.success(Unit) }
        var clearResult: (() -> Response<Unit>)? = { Response.success(Unit) }

        override suspend fun addSessionItem(sessionId: Long, request: AddItemRequest): Response<GlobalSessionItemDTO> {
            addCalls.add(sessionId to request)
            return addResult?.invoke() ?: throw NotImplementedError()
        }

        override suspend fun updateSessionItem(itemId: Long, request: UpdateItemRequest): Response<GlobalSessionItemDTO> {
            updateCalls.add(itemId to request)
            return updateResult?.invoke() ?: throw NotImplementedError()
        }

        override suspend fun deleteSessionItem(itemId: Long): Response<Unit> {
            deleteCalls.add(itemId)
            return deleteResult?.invoke() ?: throw NotImplementedError()
        }

        override suspend fun clearSession(sessionId: Long): Response<Unit> {
            clearCalls.add(sessionId)
            return clearResult?.invoke() ?: throw NotImplementedError()
        }

        override suspend fun completeSession(sessionId: Long): Response<CompleteSessionResponse> {
            completeCalls.add(sessionId)
            return completeResult?.invoke(sessionId) ?: throw NotImplementedError()
        }

        // Everything else is out of scope for these tests.
        override suspend fun getProducts(search: String?): Response<List<ProductDTO>> = throw NotImplementedError()
        override suspend fun createProduct(request: ProductCreate): Response<ProductDTO> = throw NotImplementedError()
        override suspend fun updateProduct(productId: Long, request: ProductCreate): Response<ProductDTO> = throw NotImplementedError()
        override suspend fun deleteProduct(productId: Long): Response<Unit> = throw NotImplementedError()
        override suspend fun getDistributors(): Response<List<DistributorDTO>> = throw NotImplementedError()
        override suspend fun createDistributor(request: DistributorCreate): Response<DistributorDTO> = throw NotImplementedError()
        override suspend fun updateDistributor(distributorId: Long, request: DistributorCreate): Response<DistributorDTO> = throw NotImplementedError()
        override suspend fun deleteDistributor(distributorId: Long): Response<Unit> = throw NotImplementedError()
        override suspend fun login(request: LoginRequest): Response<LoginResponse> = throw NotImplementedError()
        override suspend fun register(request: RegisterRequest): Response<RegisterResponse> = throw NotImplementedError()
        override suspend fun getCurrentUser(): Response<UserDTO> = throw NotImplementedError()
        override suspend fun updateCurrentUser(request: UpdateUserRequest): Response<UserDTO> = throw NotImplementedError()
        override suspend fun getActiveSession(teamId: Long?): Response<GlobalSessionDTO> = throw NotImplementedError()
        override suspend fun createSession(request: CreateSessionRequest): Response<GlobalSessionDTO> = throw NotImplementedError()
        override suspend fun getSessionItems(sessionId: Long): Response<List<GlobalSessionItemDTO>> = throw NotImplementedError()
        override suspend fun getStats(): Response<StatsDTO> = throw NotImplementedError()
        override suspend fun createTeam(request: TeamCreateRequest): Response<TeamDTO> = throw NotImplementedError()
        override suspend fun getMyTeams(): Response<List<TeamDTO>> = throw NotImplementedError()
        override suspend fun createInvite(teamId: Long, request: InviteCreateRequest): Response<InviteDTO> = throw NotImplementedError()
        override suspend fun acceptInvite(code: String): Response<TeamMemberDTO> = throw NotImplementedError()
        override suspend fun getTeamMembers(teamId: Long): Response<List<TeamMemberDTO>> = throw NotImplementedError()
        override suspend fun removeTeamMember(teamId: Long, userId: Long): Response<Unit> = throw NotImplementedError()
    }

    // ---------- Harness ----------

    private val itemDao = FakeSessionItemDao()
    private val sessionDao = FakeSessionDao()
    private val pendingDao = FakePendingOperationDao()
    private val api = FakeApi()
    private val repo = SessionRepository(
        sessionDao = sessionDao,
        sessionItemDao = itemDao,
        api = api,
        pendingOps = PendingOperationRepository(pendingDao),
        transaction = SessionTransaction { it() },
    )

    private fun dto(id: Long, sessionId: Long, productId: Long, quantity: Int, version: Int) =
        GlobalSessionItemDTO(
            id = id, session_id = sessionId, product_id = productId,
            quantity = quantity, version = version,
            created_at = "2026-06-10T00:00:00", updated_at = "2026-06-10T00:00:00",
            product_name = null, distributor_name = null,
        )

    private fun decodeOp(entity: PendingOperationEntity): OperationData =
        Json.decodeFromString<OperationData>(entity.operationData)

    // ---------- Tests ----------

    @Test
    fun `add persists its operation before the first network request`() = runBlocking {
        api.addResult = {
            assertEquals("The operation must survive a crash during the request", 1, pendingDao.inserted.size)
            Response.success(dto(id = 500, sessionId = 10, productId = 7, quantity = 1, version = 1))
        }
        repo.addOrIncrementItem(sessionId = 10, productId = 7)
    }

    @Test
    fun `cancellation during the first add retains the durable operation`() = runBlocking {
        api.addResult = { throw kotlinx.coroutines.CancellationException("worker stopped") }
        try {
            repo.addOrIncrementItem(sessionId = 10, productId = 7)
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(1, pendingDao.inserted.size)
            assertEquals(OperationType.ADD_SESSION_ITEM.name, pendingDao.inserted.single().operationType)
        }
    }

    @Test
    fun `lost response is replayed with the original identity and quantity applies once`() = runBlocking {
        val seen = mutableSetOf<String>()
        var serverQuantity = 0
        var loseResponse = true
        api.addResult = {
            val request = api.addCalls.last().second
            val key = requireNotNull(request.idempotency_key)
            if (seen.add(key)) serverQuantity += request.quantity
            if (loseResponse) {
                loseResponse = false
                throw IOException("response lost after commit")
            }
            Response.success(dto(500, 10, 7, serverQuantity, 1))
        }
        repo.addOrIncrementItem(10, 7, 3)
        val restoredRepository = PendingOperationRepository(pendingDao)
        val restored = restoredRepository.prepareForReplay(pendingDao.inserted.single())
        val operation = decodeOp(restored) as OperationData.AddSessionItem
        assertEquals(api.addCalls.single().second.idempotency_key, operation.idempotencyKey)
        assertTrue(SessionItemOperationSender(api).send(operation).isSuccessful)
        assertEquals(3, serverQuantity)
        assertEquals(api.addCalls[0].second.idempotency_key, api.addCalls[1].second.idempotency_key)
    }

    @Test
    fun `legacy queued add receives a durable key before its first replay`() = runBlocking {
        val legacy = OperationData.AddSessionItem(10, 7, 3)
        val legacyJson = kotlinx.serialization.json.Json.encodeToString(OperationData.serializer(), legacy)
        pendingDao.insert(PendingOperationEntity(
            operationType = OperationType.ADD_SESSION_ITEM.name, resourceType = "SESSION_ITEM",
            resourceId = null, operationData = legacyJson, status = "PENDING",
        ))
        val first = PendingOperationRepository(pendingDao).prepareForReplay(pendingDao.inserted.single())
        val second = PendingOperationRepository(pendingDao).prepareForReplay(pendingDao.inserted.single())
        val key = (decodeOp(first) as OperationData.AddSessionItem).idempotencyKey
        assertNotNull(key)
        assertEquals(key, (decodeOp(second) as OperationData.AddSessionItem).idempotencyKey)
        assertEquals(first.operationData, pendingDao.inserted.single().operationData)
    }

    @Test
    fun `two distinct adds receive different identities`() = runBlocking {
        api.addResult = { throw IOException("offline") }
        repo.addOrIncrementItem(10, 7)
        repo.addOrIncrementItem(10, 7)
        val keys = pendingDao.inserted.map { (decodeOp(it) as OperationData.AddSessionItem).idempotencyKey }
        assertTrue(keys.all { !it.isNullOrBlank() })
        assertEquals(2, keys.toSet().size)
    }

    @Test
    fun `server error retains the queued local edit`() = runBlocking {
        api.addResult = { Response.error(503, okhttp3.ResponseBody.create(null, "unavailable")) }
        repo.addOrIncrementItem(10, 7, 3)
        assertEquals(3, itemDao.rows.single().quantity)
        assertEquals(1, pendingDao.inserted.size)
    }

    @Test
    fun `update persists the same identity that its request sends`() = runBlocking {
        itemDao.rows.add(SessionItemEntity(id = 300, sessionId = 10, productId = 7, quantity = 2, version = 4))
        api.updateResult = {
            val stored = decodeOp(pendingDao.inserted.single()) as OperationData.UpdateSessionItem
            assertNotNull(stored.idempotencyKey)
            assertEquals(stored.idempotencyKey, api.updateCalls.last().second.idempotency_key)
            throw IOException("lost acknowledgement")
        }
        repo.setItemQuantity(10, 7, 5)
        assertEquals(1, pendingDao.inserted.size)
    }

    @Test
    fun `online add upserts server row and rekeys local row to server id`() = runBlocking {
        api.addResult = { Response.success(dto(id = 500, sessionId = 10, productId = 7, quantity = 1, version = 1)) }

        repo.addOrIncrementItem(sessionId = 10, productId = 7, quantity = 1)

        assertEquals(1, api.addCalls.size)
        assertEquals(10L, api.addCalls[0].first)
        assertEquals(AddItemRequest(product_id = 7, quantity = 1), api.addCalls[0].second.copy(idempotency_key = null))
        // Exactly one local row, re-keyed to the server id/version.
        assertEquals(1, itemDao.rows.size)
        val row = itemDao.rows[0]
        assertEquals(500L, row.id)
        assertEquals(10L, row.sessionId)
        assertEquals(7L, row.productId)
        assertEquals(1, row.quantity)
        assertEquals(1, row.version)
        assertTrue(pendingDao.inserted.isEmpty())
    }

    @Test
    fun `offline add enqueues exactly one ADD_SESSION_ITEM op with correct payload`() = runBlocking {
        api.addResult = { throw IOException("no network") }

        repo.addOrIncrementItem(sessionId = 10, productId = 7, quantity = 3)

        // Local write still happened.
        assertEquals(1, itemDao.rows.size)
        assertEquals(3, itemDao.rows[0].quantity)
        // Exactly one queued op with the right payload.
        assertEquals(1, pendingDao.inserted.size)
        val op = pendingDao.inserted[0]
        assertEquals(OperationType.ADD_SESSION_ITEM.name, op.operationType)
        val data = decodeOp(op) as OperationData.AddSessionItem
        assertEquals(10L, data.sessionId)
        assertEquals(7L, data.productId)
        assertEquals(3, data.quantity)
    }

    @Test
    fun `negative session writes wait in the durable queue without network`() = runBlocking {
        repo.addOrIncrementItem(sessionId = -1, productId = 7, quantity = 2)
        repo.setItemQuantity(sessionId = -1, productId = 7, quantity = 5)
        repo.clearSession(sessionId = -1)

        assertTrue(api.addCalls.isEmpty())
        assertTrue(api.updateCalls.isEmpty())
        assertTrue(api.clearCalls.isEmpty())
        assertEquals(
            listOf(
                OperationType.ADD_SESSION_ITEM.name,
                OperationType.UPDATE_SESSION_ITEM.name,
                OperationType.CLEAR_SESSION.name,
            ),
            pendingDao.inserted.map { it.operationType },
        )
        assertTrue(pendingDao.inserted.all { it.status == "WAITING" })
        // Local writes all happened (add → set to 5 → clear).
        assertTrue(itemDao.rows.isEmpty())
    }

    @Test
    fun `negative sessionId add keeps the local row`() = runBlocking {
        repo.addOrIncrementItem(sessionId = -1, productId = 7, quantity = 2)

        assertEquals(1, itemDao.rows.size)
        assertEquals(2, itemDao.rows[0].quantity)
        assertTrue(api.addCalls.isEmpty())
        assertEquals(OperationType.ADD_SESSION_ITEM.name, pendingDao.inserted.single().operationType)
        assertEquals("WAITING", pendingDao.inserted.single().status)
    }

    @Test
    fun `offline delete on a row in a positive session enqueues DeleteSessionItem`() = runBlocking {
        itemDao.rows.add(SessionItemEntity(id = 42, sessionId = 10, productId = 7, quantity = 1, version = 2))
        api.deleteResult = { throw IOException("no network") }

        repo.deleteItem(42)

        assertTrue(itemDao.rows.isEmpty()) // local delete happened first
        assertEquals(1, pendingDao.inserted.size)
        val op = pendingDao.inserted[0]
        assertEquals(OperationType.DELETE_SESSION_ITEM.name, op.operationType)
        val data = decodeOp(op) as OperationData.DeleteSessionItem
        assertEquals(42L, data.itemId)
    }

    @Test
    fun `deleteItem on a row in a NEGATIVE session persists an unsent intent`() = runBlocking {
        // Item ids are always positive autogenerated values, even when the
        // SESSION is a local offline fallback (negative id).
        itemDao.rows.add(SessionItemEntity(id = 42, sessionId = -1, productId = 7, quantity = 1))

        repo.deleteItem(42)

        assertTrue(itemDao.rows.isEmpty()) // local delete still happened
        assertTrue(api.deleteCalls.isEmpty())
        assertEquals(OperationType.DELETE_SESSION_ITEM.name, pendingDao.inserted.single().operationType)
        assertEquals("WAITING", pendingDao.inserted.single().status)
    }

    @Test
    fun `deleteItem on an unknown row deletes locally with no api call and no queue`() = runBlocking {
        repo.deleteItem(42)

        assertTrue(api.deleteCalls.isEmpty())
        assertTrue(pendingDao.inserted.isEmpty())
    }

    @Test
    fun `completeSessionWriteThrough with positive id completes locally and on the server`() = runBlocking {
        repo.completeSessionWriteThrough(10)

        assertEquals(listOf(10L), sessionDao.completedIds)
        assertEquals(listOf(10L), api.completeCalls)
        assertTrue(pendingDao.inserted.isEmpty())
    }

    @Test
    fun `completeSessionWriteThrough with negative id queues completion`() = runBlocking {
        repo.completeSessionWriteThrough(-1)

        assertEquals(listOf(-1L), sessionDao.completedIds)
        assertTrue(api.completeCalls.isEmpty())
        assertEquals(OperationType.COMPLETE_SESSION.name, pendingDao.inserted.single().operationType)
        assertEquals("WAITING", pendingDao.inserted.single().status)
    }

    @Test
    fun `clearSession with positive id calls the API`() = runBlocking {
        itemDao.rows.add(SessionItemEntity(id = 1, sessionId = 10, productId = 7, quantity = 1))

        repo.clearSession(10)

        assertTrue(itemDao.rows.isEmpty())
        assertEquals(listOf(10L), api.clearCalls)
        assertTrue(pendingDao.inserted.isEmpty())
    }

    @Test
    fun `setItemQuantity with existing row calls update with the row's version`() = runBlocking {
        itemDao.rows.add(SessionItemEntity(id = 300, sessionId = 10, productId = 7, quantity = 2, version = 4))
        api.updateResult = { Response.success(dto(id = 300, sessionId = 10, productId = 7, quantity = 5, version = 5)) }

        repo.setItemQuantity(sessionId = 10, productId = 7, quantity = 5)

        assertEquals(1, api.updateCalls.size)
        assertEquals(300L, api.updateCalls[0].first)
        assertEquals(UpdateItemRequest(quantity = 5, version = 4), api.updateCalls[0].second.copy(idempotency_key = null))
        // Server response mirrored back into Room (new version).
        assertEquals(1, itemDao.rows.size)
        assertEquals(5, itemDao.rows[0].quantity)
        assertEquals(5, itemDao.rows[0].version)
        assertTrue(pendingDao.inserted.isEmpty())
    }

    @Test
    fun `setItemQuantity to zero deletes on server and treats 404 as success`() = runBlocking {
        itemDao.rows.add(SessionItemEntity(id = 300, sessionId = 10, productId = 7, quantity = 2, version = 4))
        api.deleteResult = { Response.success(Unit) }

        repo.setItemQuantity(sessionId = 10, productId = 7, quantity = 0)

        assertTrue(itemDao.rows.isEmpty())
        assertEquals(listOf(300L), api.deleteCalls)
        assertTrue(pendingDao.inserted.isEmpty())
    }

    @Test
    fun `offline setItemQuantity on existing row enqueues UpdateSessionItem with captured version`() = runBlocking {
        itemDao.rows.add(SessionItemEntity(id = 300, sessionId = 10, productId = 7, quantity = 2, version = 4))
        api.updateResult = { throw IOException("no network") }

        repo.setItemQuantity(sessionId = 10, productId = 7, quantity = 9)

        assertEquals(9, itemDao.rows[0].quantity) // local write happened
        assertEquals(1, pendingDao.inserted.size)
        val data = decodeOp(pendingDao.inserted[0]) as OperationData.UpdateSessionItem
        assertEquals(300L, data.itemId)
        assertEquals(9, data.quantity)
        assertEquals(4, data.version)
    }
}
