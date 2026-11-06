package com.darius.listmanager.repository

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.InventoryItemEntity
import com.darius.listmanager.data.repository.InventoryRepository
import com.darius.listmanager.network.InventoryClearRequest
import com.darius.listmanager.network.InventoryDTO
import com.darius.listmanager.network.InventoryItemCreate
import com.darius.listmanager.network.InventoryItemDTO
import com.darius.listmanager.network.InventoryItemUpdate
import com.darius.listmanager.network.ListManagerApi
import com.darius.listmanager.sync.ReviewOutcome
import com.darius.listmanager.sync.SyncReviewResolver
import com.darius.listmanager.sync.SyncService
import com.darius.listmanager.sync.isHeldForReview
import com.darius.listmanager.sync.isVersionConflict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.random.Random

/**
 * EX-44: two phones sharing one inventory. Each [Phone] has its own database, outbox and
 * worker, and they meet only at a server that enforces versions, tombstones and the ledger.
 * This exercises the convergence logic; it is not a substitute for real devices (EX-28).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class InventorySyncTest {
    @get:Rule val temporary = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()
    private val openDatabases = mutableListOf<AppDatabase>()

    private class Row(
        val id: Long,
        val uuid: String,
        var name: String,
        var productId: Long?,
        var quantity: Double?,
        var priceBani: Long?,
        var version: Int = 1,
        var deleted: Boolean = false,
    )

    /** The rules of backend-fastapi/app/routers/inventory.py. */
    private class Server {
        val rows = linkedMapOf<String, Row>()
        val keys = mutableSetOf<String>()
        var reachable = true
        var loseNextReply = false
        var adds = 0
        var appliedUpdates = 0
        var clears = 0
        private var nextId = 1L

        val live get() = rows.values.filter { !it.deleted }

        private fun Row.dto() = InventoryItemDTO(id, 1, uuid, name, productId, quantity, priceBani, version, deleted)

        private fun gate() {
            if (!reachable) throw IOException("offline")
        }

        /** The write has already happened when the phone loses the answer. */
        private fun reply() {
            if (loseNextReply) {
                loseNextReply = false
                throw IOException("Reply lost")
            }
        }

        val api: ListManagerApi = run {
            val unused = Proxy.newProxyInstance(
                ListManagerApi::class.java.classLoader, arrayOf(ListManagerApi::class.java),
            ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as ListManagerApi

            object : ListManagerApi by unused {
                override suspend fun getInventory(teamId: Long?): Response<InventoryDTO> {
                    gate()
                    return Response.success(InventoryDTO(1, null, teamId, live.map { it.dto() }))
                }

                override suspend fun addInventoryItem(inventoryId: Long, request: InventoryItemCreate): Response<InventoryItemDTO> {
                    gate()
                    val row = rows.getOrPut(request.item_uuid) {
                        adds++
                        Row(nextId++, request.item_uuid, request.name, request.product_id, request.quantity, request.price_bani)
                    }
                    reply()
                    return Response.success(row.dto())
                }

                override suspend fun updateInventoryItem(inventoryId: Long, itemUuid: String, request: InventoryItemUpdate): Response<InventoryItemDTO> {
                    gate()
                    val row = rows[itemUuid]?.takeUnless { it.deleted }
                    if (row == null) return Response.error(404, "gone".toResponseBody())
                    if (request.idempotency_key in keys) return Response.success(row.dto())
                    if (row.version != request.version) return Response.error(409, "stale".toResponseBody())
                    row.name = request.name
                    row.productId = request.product_id
                    row.quantity = request.quantity
                    row.priceBani = request.price_bani
                    row.version++
                    keys += request.idempotency_key
                    appliedUpdates++
                    reply()
                    return Response.success(row.dto())
                }

                override suspend fun deleteInventoryItem(inventoryId: Long, itemUuid: String): Response<ResponseBody> {
                    gate()
                    rows[itemUuid]?.deleted = true
                    reply()
                    return Response.success("{}".toResponseBody())
                }

                override suspend fun clearInventory(inventoryId: Long, request: InventoryClearRequest): Response<ResponseBody> {
                    gate()
                    if (keys.add(request.idempotency_key)) {
                        clears++
                        live.forEach { it.deleted = true }
                    }
                    reply()
                    return Response.success("{}".toResponseBody())
                }
            }
        }
    }

    /** One phone: its own database and outbox, sharing only the server. */
    private inner class Phone(val server: Server, userId: Long, val teamId: Long? = TEAM) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build().also { openDatabases += it }
        val inventory = InventoryRepository(db, currentUserId = { userId })
        val sync = SyncService(context, db, server.api, currentUserId = { userId }, now = { Long.MAX_VALUE - 1 })
        val resolver = SyncReviewResolver(db, server.api) { sync.syncPendingOperations() }

        suspend fun rows() = inventory.getAllFlow(teamId).first()
        suspend fun add(name: String, quantity: Double? = 1.0, price: Long? = 100) =
            inventory.insert(teamId, name, null, quantity, price, 0L)

        suspend fun row(name: String) = rows().single { it.name == name }
        suspend fun edit(name: String, quantity: Double) = inventory.update(row(name).copy(quantity = quantity))
        suspend fun remove(name: String) = inventory.delete(row(name))

        suspend fun push() = sync.syncPendingOperations()
        suspend fun pull() = inventory.refresh(teamId) { server.api.getInventory(teamId) }
        suspend fun unfinished() = db.pendingOperationDao().getUnfinished()
        suspend fun held() = unfinished().filter { it.isHeldForReview }

        suspend fun settle() {
            push()
            pull()
        }

        suspend fun view() = rows().map { Triple(it.itemUuid, it.quantity, it.version) }.sortedBy { it.first }
    }

    @After fun close() { openDatabases.forEach { it.close() } }

    @Test fun anEdit_isSavedLocally_withItsOutboxEntry_beforeAnySend() = runBlocking {
        val phone = Phone(Server().apply { reachable = false }, USER_A)

        phone.add("Lapte", 2.0, 450)

        assertEquals("Lapte", phone.rows().single().name)
        assertEquals(1, phone.unfinished().size)
    }

    @Test fun anAdd_reachesTheOtherPhone() = runBlocking {
        val server = Server()
        val a = Phone(server, USER_A)
        val b = Phone(server, USER_B)

        a.add("Lapte", 2.0, 450)
        a.settle()
        b.pull()

        val row = b.rows().single()
        assertEquals("Lapte", row.name)
        assertEquals(2.0, row.quantity!!, 0.0)
        assertEquals(450L, row.priceBani)
        assertEquals(a.rows().single().itemUuid, row.itemUuid)
    }

    @Test fun workOfflineThenReconnect_uploadsEverythingInOrder() = runBlocking {
        val server = Server().apply { reachable = false }
        val phone = Phone(server, USER_A)
        phone.add("Lapte")
        phone.add("Pâine")
        phone.edit("Lapte", 7.0)
        phone.remove("Pâine")

        phone.push()
        assertTrue(server.rows.isEmpty())

        server.reachable = true
        phone.settle()

        assertEquals(listOf("Lapte"), server.live.map { it.name })
        assertEquals(7.0, server.live.single().quantity!!, 0.0)
        assertTrue(phone.unfinished().isEmpty())
    }

    @Test fun deletingARow_whoseAddWasNeverAcknowledged_works() = runBlocking {
        val server = Server().apply { reachable = false }
        val phone = Phone(server, USER_A)
        phone.add("Lapte")
        phone.remove("Lapte")

        server.reachable = true
        phone.settle()

        assertTrue(server.live.isEmpty())
        assertTrue(phone.rows().isEmpty())
        assertTrue(phone.unfinished().isEmpty())
    }

    @Test fun aLostReply_doesNotApplyAnAddTwice() = runBlocking {
        val server = Server().apply { loseNextReply = true }
        val phone = Phone(server, USER_A)
        phone.add("Lapte")

        phone.push() // Applied on the server; the phone hears nothing.
        phone.push()

        assertEquals(1, server.adds)
        assertEquals(1, server.live.size)
    }

    @Test fun aLostReply_doesNotApplyAnUpdateTwice() = runBlocking {
        val server = Server()
        val phone = Phone(server, USER_A)
        phone.add("Lapte")
        phone.settle()
        phone.edit("Lapte", 5.0)

        server.loseNextReply = true
        phone.push()
        phone.push()

        assertEquals(1, server.appliedUpdates)
        assertEquals(2, server.live.single().version)
        assertTrue(phone.unfinished().isEmpty())
    }

    @Test fun aLostReply_doesNotClearTwice() = runBlocking {
        val server = Server()
        val phone = Phone(server, USER_A)
        phone.add("Old")
        phone.settle()
        phone.inventory.clear(phone.teamId)

        server.loseNextReply = true
        phone.push()
        phone.push()

        assertEquals(1, server.clears)
    }

    @Test fun aRowAddedAfterClear_survivesTheClearRetry() = runBlocking {
        val server = Server()
        val a = Phone(server, USER_A)
        val b = Phone(server, USER_B)
        a.add("Old")
        a.settle()
        a.inventory.clear(a.teamId)
        server.loseNextReply = true
        a.push()

        b.add("New")
        b.settle()
        a.push()
        a.pull()

        assertEquals(listOf("New"), server.live.map { it.name })
        assertEquals(listOf("New"), a.rows().map { it.name })
    }

    @Test fun aRetriedAdd_doesNotResurrectARowAnotherPhoneDeleted() = runBlocking {
        val server = Server()
        val a = Phone(server, USER_A)
        val b = Phone(server, USER_B)
        a.add("Lapte")
        server.loseNextReply = true
        a.push() // Applied; A never hears.

        b.pull()
        b.remove("Lapte")
        b.settle()

        a.push()
        a.pull()

        assertTrue(server.live.isEmpty())
        assertTrue(a.rows().isEmpty())
        assertTrue(a.unfinished().isEmpty())
    }

    @Test fun aServerPull_neverOverwritesAnUnsentEdit() = runBlocking {
        val server = Server()
        val phone = Phone(server, USER_A)
        phone.add("Lapte", 1.0)
        phone.settle()
        server.reachable = false
        phone.edit("Lapte", 9.0)
        server.reachable = true

        val refreshed = phone.pull()

        assertFalse(refreshed)
        assertEquals(9.0, phone.row("Lapte").quantity!!, 0.0)
    }

    @Test fun twoPhonesEditingOneRow_conflict_isHeld_andNothingIsLost() = runBlocking {
        val server = Server()
        val a = Phone(server, USER_A)
        val b = Phone(server, USER_B)
        a.add("Lapte", 1.0)
        a.settle()
        b.pull()

        a.edit("Lapte", 5.0)
        b.edit("Lapte", 9.0)
        a.settle()
        b.push()

        assertTrue(b.held().single().isVersionConflict)
        assertEquals(5.0, server.live.single().quantity!!, 0.0)
        assertEquals(9.0, b.row("Lapte").quantity!!, 0.0)
    }

    @Test fun keepMine_winsOnTheNewestVersion_andBothPhonesAgree() = runBlocking {
        val server = Server()
        val a = Phone(server, USER_A)
        val b = Phone(server, USER_B)
        a.add("Lapte", 1.0)
        a.settle()
        b.pull()
        a.edit("Lapte", 5.0)
        b.edit("Lapte", 9.0)
        a.settle()
        b.push()

        assertEquals(ReviewOutcome.RESOLVED, b.resolver.keepMine(b.held().single().id))
        b.pull()
        a.pull()

        assertEquals(9.0, server.live.single().quantity!!, 0.0)
        assertEquals(a.view(), b.view())
    }

    @Test fun useTheirs_dropsMyEdit_andBothPhonesAgree() = runBlocking {
        val server = Server()
        val a = Phone(server, USER_A)
        val b = Phone(server, USER_B)
        a.add("Lapte", 1.0)
        a.settle()
        b.pull()
        a.edit("Lapte", 5.0)
        b.edit("Lapte", 9.0)
        a.settle()
        b.push()

        b.resolver.discard(b.held().single().id)
        b.pull()

        assertEquals(5.0, b.row("Lapte").quantity!!, 0.0)
        assertEquals(a.view(), b.view())
        assertTrue(b.unfinished().isEmpty())
    }

    @Test fun editingARowAnotherPhoneDeleted_offersToDropIt() = runBlocking {
        val server = Server()
        val a = Phone(server, USER_A)
        val b = Phone(server, USER_B)
        a.add("Lapte")
        a.settle()
        b.pull()
        a.remove("Lapte")
        a.settle()
        b.edit("Lapte", 3.0)

        b.push()
        val held = b.held().single()
        b.resolver.discard(held.id)
        b.pull()

        assertTrue(b.rows().isEmpty())
        assertTrue(b.unfinished().isEmpty())
    }

    /** Seeded offline work on both phones, resolving conflicts until everything lands. */
    @Test fun randomisedWorkOnTwoPhones_converges() = runBlocking {
        repeat(8) { seed ->
            val random = Random(seed)
            val server = Server()
            val phones = listOf(Phone(server, USER_A), Phone(server, USER_B))
            var counter = 0

            repeat(60) { step ->
                val phone = phones.random(random)
                val rows = phone.rows()
                when {
                    rows.isEmpty() || random.nextInt(4) == 0 -> phone.add("rand-$seed-${counter++}", step.toDouble())
                    random.nextInt(5) == 0 -> phone.inventory.delete(rows.random(random))
                    else -> phone.inventory.update(rows.random(random).copy(quantity = step.toDouble()))
                }
                if (random.nextInt(3) == 0) phone.settle()
                // Sometimes the network drops mid-exchange.
                if (random.nextInt(10) == 0) server.loseNextReply = true
                val heldOps = phone.held()
                if (heldOps.isNotEmpty() && random.nextBoolean()) {
                    phone.resolver.discard(heldOps.first().id)
                }
            }

            // Reconnect and settle: each phone resolves whatever is still waiting.
            server.loseNextReply = false
            repeat(40) {
                phones.forEach { phone ->
                    phone.push()
                    phone.held().firstOrNull()?.let { phone.resolver.discard(it.id) }
                }
            }
            phones.forEach { it.pull() }

            phones.forEach { assertTrue("seed $seed left work: ${it.unfinished()}", it.unfinished().isEmpty()) }
            val serverView = server.live.map { Triple(it.uuid, it.quantity, it.version) }.sortedBy { it.first }
            assertEquals("seed $seed", serverView, phones[0].view())
            assertEquals("seed $seed", serverView, phones[1].view())
        }
    }

    @Test fun personalAndTeamRows_stayApart() = runBlocking {
        val server = Server()
        val phone = Phone(server, USER_A)
        val personal = Phone(server, USER_A, teamId = null)
        val db = phone.db
        val inventory = phone.inventory

        inventory.insert(TEAM, "Team row", null, 1.0, 1, 0L)
        inventory.insert(null, "My row", null, 1.0, 1, 0L)

        assertEquals(listOf("Team row"), inventory.getAllFlow(TEAM).first().map { it.name })
        assertEquals(listOf("My row"), inventory.getAllFlow(null).first().map { it.name })

        inventory.clear(TEAM)
        assertEquals(listOf("My row"), db.inventoryItemDao().getAll(USER_A, null).map { it.name })
        assertNotEquals(phone, personal)
    }

    @Test fun anotherAccountsPersonalRows_areNotVisible() = runBlocking {
        val server = Server()
        val a = Phone(server, USER_A, teamId = null)
        a.inventory.insert(null, "Private", null, 1.0, 1, 0L)

        val nextAccount = InventoryRepository(a.db, currentUserId = { USER_B })

        assertTrue(nextAccount.getAllFlow(null).first().isEmpty())
    }

    @Test fun rowsFromBeforeSharing_areAdoptedOnce_andUploaded() = runBlocking {
        val server = Server()
        val phone = Phone(server, USER_A, teamId = null)
        phone.db.inventoryItemDao().insert(
            InventoryItemEntity(name = "Legacy", quantity = 3.0, priceBani = 500, createdAt = 1L, itemUuid = "legacy-uuid")
        )
        assertTrue(phone.rows().isEmpty())

        phone.inventory.adoptLegacyRows()
        phone.inventory.adoptLegacyRows()
        phone.settle()

        assertEquals("Legacy", phone.rows().single().name)
        assertEquals(1, server.adds)
        assertEquals(3.0, server.live.single().quantity!!, 0.0)
    }

    @Test fun migration9To10_keepsRows_andGivesEachAUniqueIdentity() {
        val path = temporary.newFile("migration.db").absolutePath
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(path)
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE inventory_items (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                name TEXT NOT NULL,
                                productId INTEGER,
                                quantity REAL,
                                priceBani INTEGER,
                                createdAt INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL("INSERT INTO inventory_items (name, quantity, priceBani, createdAt) VALUES ('Lapte', 2.5, 450, 10)")
                        db.execSQL("INSERT INTO inventory_items (name, quantity, priceBani, createdAt) VALUES ('Paine', NULL, NULL, 20)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase

        AppDatabase.MIGRATION_9_10.migrate(db)

        db.query("SELECT name, quantity, priceBani, version, accountUserId, teamId, itemUuid FROM inventory_items ORDER BY id").use { rows ->
            assertTrue(rows.moveToNext())
            assertEquals("Lapte", rows.getString(0))
            assertEquals(2.5, rows.getDouble(1), 0.0)
            assertEquals(450L, rows.getLong(2))
            assertEquals(1, rows.getInt(3))
            assertEquals(InventoryItemEntity.UNCLAIMED, rows.getLong(4))
            assertTrue(rows.isNull(5))
            val first = rows.getString(6)
            assertEquals(32, first.length)

            assertTrue(rows.moveToNext())
            assertEquals("Paine", rows.getString(0))
            assertTrue(rows.isNull(1))
            assertNotEquals(first, rows.getString(6))
        }
        // A migrated table that differs from the entity would crash the app on upgrade.
        val fresh = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build().also { openDatabases += it }
        val expected = fresh.openHelper.writableDatabase
        assertEquals(columnsOf(expected), columnsOf(db))
        assertEquals(uniqueIndexesOf(expected), uniqueIndexesOf(db))
        helper.close()
    }

    private fun columnsOf(db: SupportSQLiteDatabase): List<String> =
        db.query("PRAGMA table_info(inventory_items)").use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    // name, type, notnull, pk.
                    add("${rows.getString(1)} ${rows.getString(2)} ${rows.getInt(3)} ${rows.getInt(5)}")
                }
            }.sorted()
        }

    private fun uniqueIndexesOf(db: SupportSQLiteDatabase): List<String> =
        db.query("PRAGMA index_list(inventory_items)").use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    if (rows.getInt(2) == 1) add(rows.getString(1))
                }
            }.sorted()
        }

    private companion object {
        const val USER_A = 1L
        const val USER_B = 2L
        const val TEAM = 7L
    }
}
