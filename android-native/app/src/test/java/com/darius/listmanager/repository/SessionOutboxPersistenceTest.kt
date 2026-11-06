package com.darius.listmanager.repository

import androidx.room.Room
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.dao.PendingOperationDao
import com.darius.listmanager.data.local.entity.*
import com.darius.listmanager.data.model.OperationData
import com.darius.listmanager.data.repository.*
import com.darius.listmanager.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy

/** Real Room-generated DAOs and native SQLite, including file close/reopen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SessionOutboxPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: AppDatabase
    private var committedQuantity = 0
    private val seenKeys = mutableSetOf<String>()
    private var loseResponse = false
    private val requests = mutableListOf<AddItemRequest>()

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(
        RuntimeEnvironment.getApplication(), AppDatabase::class.java,
        File(temporary.root, "outbox.db").absolutePath,
    ).allowMainThreadQueries().build()

    @Before fun setup() = runBlocking {
        database = openDatabase()
        database.distributorDao().insert(DistributorEntity(1, "Supplier"))
        database.productDao().insert(ProductEntity(7, "Product", 1))
        database.sessionDao().insert(SessionEntity(id = 10))
        Unit
    }

    @After fun close() { database.close() }

    private fun api(): ListManagerApi = Proxy.newProxyInstance(
        ListManagerApi::class.java.classLoader, arrayOf(ListManagerApi::class.java)
    ) { _, method, args ->
        check(method.name == "addSessionItem") { "Unexpected method: ${method.name}" }
        val request = args!![1] as AddItemRequest
        requests.add(request)
        val key = requireNotNull(request.idempotency_key)
        // The queue entry is visible outside the edit transaction before I/O.
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM pending_operations").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        if (seenKeys.add(key)) committedQuantity += request.quantity
        if (loseResponse) {
            loseResponse = false
            throw IOException("Server committed but response was lost")
        }
        Response.success(GlobalSessionItemDTO(
            id = 500, session_id = 10, product_id = 7, quantity = committedQuantity,
            version = 9, created_at = "2026-09-11T00:00:00", updated_at = "2026-09-11T00:00:00",
            product_name = "Product", distributor_name = "Supplier",
        ))
    } as ListManagerApi

    private fun repository(dao: PendingOperationDao = database.pendingOperationDao()) = SessionRepository(
        database.sessionDao(), database.sessionItemDao(), api(),
        PendingOperationRepository(dao), SessionTransaction.room(database),
    )

    @Test fun lostResponse_survivesDatabaseReopen_andReplaysOnce() = runBlocking {
        loseResponse = true
        repository().addOrIncrementItem(10, 7, 3)
        val original = database.pendingOperationDao().getAllPending().single()
        assertEquals(3, database.sessionItemDao().getItem(10, 7)!!.quantity)
        database.close()
        database = openDatabase()
        val pending = PendingOperationRepository(database.pendingOperationDao())
        val restored = pending.prepareForReplay(database.pendingOperationDao().getAllPending().single())
        assertEquals(original.operationData, restored.operationData)
        val operation = Json.decodeFromString<OperationData>(restored.operationData)
        val response = SessionItemOperationSender(api()).send(operation)
        assertTrue(response.isSuccessful)
        assertEquals(3, committedQuantity)
        assertEquals(requests[0].idempotency_key, requests[1].idempotency_key)
    }

    @Test fun outboxInsertFailure_rollsBackTheLocalEdit_andNeverSends() = runBlocking {
        val failingDao = object : PendingOperationDao by database.pendingOperationDao() {
            override suspend fun insert(operation: PendingOperationEntity): Long = throw IOException("disk full")
        }
        try {
            repository(failingDao).addOrIncrementItem(10, 7, 3)
            fail("Persistence failure should propagate")
        } catch (_: IOException) {
            assertNull(database.sessionItemDao().getItem(10, 7))
            assertTrue(database.pendingOperationDao().getAllPending().isEmpty())
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun success_rekeysLocalItem_andAcknowledgesOutbox() = runBlocking {
        repository().addOrIncrementItem(10, 7, 3)
        val item = database.sessionItemDao().getItem(10, 7)!!
        assertEquals(500L, item.id)
        assertEquals(9, item.version)
        assertTrue(database.pendingOperationDao().getAllPending().isEmpty())
    }

    @Test fun acknowledgementFailure_rollsBackServerMirror_andRetainsReplay() = runBlocking {
        val failingDao = object : PendingOperationDao by database.pendingOperationDao() {
            override suspend fun deleteById(id: Long) { throw IllegalStateException("ack failed") }
        }
        repository(failingDao).addOrIncrementItem(10, 7, 3)
        val local = database.sessionItemDao().getItem(10, 7)!!
        assertNotEquals(500L, local.id)
        assertEquals(1, local.version)
        assertEquals(1, database.pendingOperationDao().getAllPending().size)
    }
    @Test fun failedOperation_becomesReadyWhenBackoffExpires() = runBlocking {
        loseResponse = true
        repository().addOrIncrementItem(10, 7, 3)
        val dao = database.pendingOperationDao()
        val op = dao.getAllPending().single()
        dao.markAsFailed(op.id, error = "offline", scheduledFor = 100)
        assertTrue(dao.getReadyForSync(99).isEmpty())
        assertEquals(op.id, dao.getReadyForSync(100).single().id)
    }

    @Test fun interruptedOperation_isAvailableForRecovery() = runBlocking {
        loseResponse = true
        repository().addOrIncrementItem(10, 7, 3)
        val dao = database.pendingOperationDao()
        val op = dao.getAllPending().single()
        dao.markAsInProgress(op.id)
        assertEquals(op.id, dao.getReadyForSync(Long.MAX_VALUE).single().id)
    }

}
