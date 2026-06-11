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
    }

    private class FakeSessionDao : SessionDao {
        override fun getAllFlow(): Flow<List<SessionEntity>> = throw NotImplementedError()
        override suspend fun getActiveSession(teamId: Long?): SessionEntity? = throw NotImplementedError()
        override fun getActiveSessionFlow(teamId: Long?): Flow<SessionEntity?> = throw NotImplementedError()
        override suspend fun getById(id: Long): SessionEntity? = throw NotImplementedError()
        override suspend fun insert(session: SessionEntity): Long = throw NotImplementedError()
        override suspend fun upsert(session: SessionEntity): Long = throw NotImplementedError()
        override suspend fun update(session: SessionEntity) = throw NotImplementedError()
        override suspend fun delete(session: SessionEntity) = throw NotImplementedError()
        override suspend fun deactivateAll(teamId: Long?) = throw NotImplementedError()
        override suspend fun getMinSessionId(): Long? = throw NotImplementedError()
    }

    private class FakePendingOperationDao : PendingOperationDao {
        val inserted = mutableListOf<PendingOperationEntity>()

        override suspend fun insert(operation: PendingOperationEntity): Long {
            inserted.add(operation)
            return inserted.size.toLong()
        }

        override suspend fun getAllPending(): List<PendingOperationEntity> = throw NotImplementedError()
        override fun getAllPendingFlow(): Flow<List<PendingOperationEntity>> = throw NotImplementedError()
        override suspend fun getAllFailed(): List<PendingOperationEntity> = throw NotImplementedError()
        override suspend fun getById(id: Long): PendingOperationEntity? = throw NotImplementedError()
        override suspend fun getReadyForSync(currentTime: Long): List<PendingOperationEntity> = throw NotImplementedError()
        override fun getPendingCountFlow(): Flow<Int> = throw NotImplementedError()
        override fun getFailedCountFlow(): Flow<Int> = throw NotImplementedError()
        override suspend fun insertAll(operations: List<PendingOperationEntity>) = throw NotImplementedError()
        override suspend fun update(operation: PendingOperationEntity) = throw NotImplementedError()
        override suspend fun markAsFailed(id: Long, status: String, error: String?, updatedAt: Long, scheduledFor: Long) = throw NotImplementedError()
        override suspend fun markAsInProgress(id: Long, updatedAt: Long) = throw NotImplementedError()
        override suspend fun markAsCompleted(id: Long, updatedAt: Long) = throw NotImplementedError()
        override suspend fun delete(operation: PendingOperationEntity) = throw NotImplementedError()
        override suspend fun deleteById(id: Long) = throw NotImplementedError()
        override suspend fun deleteAllCompleted() = throw NotImplementedError()
        override suspend fun deleteOldCompleted(olderThan: Long) = throw NotImplementedError()
        override suspend fun deleteAll() = throw NotImplementedError()
    }

    private class FakeApi : ListManagerApi {
        val addCalls = mutableListOf<Pair<Long, AddItemRequest>>()
        val updateCalls = mutableListOf<Pair<Long, UpdateItemRequest>>()
        val deleteCalls = mutableListOf<Long>()
        val clearCalls = mutableListOf<Long>()

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
        override suspend fun completeSession(sessionId: Long): Response<CompleteSessionResponse> = throw NotImplementedError()
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
    private val pendingDao = FakePendingOperationDao()
    private val api = FakeApi()
    private val repo = SessionRepository(
        sessionDao = FakeSessionDao(),
        sessionItemDao = itemDao,
        api = api,
        pendingOps = PendingOperationRepository(pendingDao),
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
    fun `online add upserts server row and rekeys local row to server id`() = runBlocking {
        api.addResult = { Response.success(dto(id = 500, sessionId = 10, productId = 7, quantity = 1, version = 1)) }

        repo.addOrIncrementItem(sessionId = 10, productId = 7, quantity = 1)

        assertEquals(1, api.addCalls.size)
        assertEquals(10L, api.addCalls[0].first)
        assertEquals(AddItemRequest(product_id = 7, quantity = 1), api.addCalls[0].second)
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
    fun `negative sessionId skips network and queue but writes locally`() = runBlocking {
        repo.addOrIncrementItem(sessionId = -1, productId = 7, quantity = 2)
        repo.setItemQuantity(sessionId = -1, productId = 7, quantity = 5)
        repo.clearSession(sessionId = -1)

        assertTrue(api.addCalls.isEmpty())
        assertTrue(api.updateCalls.isEmpty())
        assertTrue(api.clearCalls.isEmpty())
        assertTrue(pendingDao.inserted.isEmpty())
        // Local writes all happened (add → set to 5 → clear).
        assertTrue(itemDao.rows.isEmpty())
    }

    @Test
    fun `negative sessionId add keeps the local row`() = runBlocking {
        repo.addOrIncrementItem(sessionId = -1, productId = 7, quantity = 2)

        assertEquals(1, itemDao.rows.size)
        assertEquals(2, itemDao.rows[0].quantity)
        assertTrue(api.addCalls.isEmpty())
        assertTrue(pendingDao.inserted.isEmpty())
    }

    @Test
    fun `offline delete enqueues DeleteSessionItem`() = runBlocking {
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
        assertEquals(UpdateItemRequest(quantity = 5, version = 4), api.updateCalls[0].second)
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
