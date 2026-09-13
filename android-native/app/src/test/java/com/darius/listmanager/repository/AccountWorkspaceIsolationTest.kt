package com.darius.listmanager.repository

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.local.entity.SessionEntity
import com.darius.listmanager.data.model.OperationData
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.network.AddItemRequest
import com.darius.listmanager.network.GlobalSessionItemDTO
import com.darius.listmanager.network.ListManagerApi
import com.darius.listmanager.sync.SyncService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
class AccountWorkspaceIsolationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var database: AppDatabase
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun migration7To8_preservesLegacyOfflineRowsInQuarantine() {
        val path = temporary.newFile("migration.db").absolutePath
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(path)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE sessions (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                name TEXT NOT NULL,
                                createdAt INTEGER NOT NULL,
                                isActive INTEGER NOT NULL,
                                teamId INTEGER
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE pending_operations (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                operationType TEXT NOT NULL,
                                resourceType TEXT NOT NULL,
                                resourceId INTEGER,
                                operationData TEXT NOT NULL,
                                status TEXT NOT NULL,
                                retryCount INTEGER NOT NULL,
                                lastError TEXT,
                                createdAt INTEGER NOT NULL,
                                updatedAt INTEGER NOT NULL,
                                scheduledFor INTEGER NOT NULL,
                                conflictStrategy TEXT NOT NULL,
                                userId INTEGER
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build()
        )

        val sqlite = helper.writableDatabase
        sqlite.execSQL(
            "INSERT INTO sessions (id, name, createdAt, isActive, teamId) " +
                "VALUES (10, 'Offline', 1, 1, NULL)"
        )
        sqlite.execSQL(
            """
            INSERT INTO pending_operations (
                operationType, resourceType, resourceId, operationData, status,
                retryCount, createdAt, updatedAt, scheduledFor, conflictStrategy, userId
            ) VALUES ('CREATE_SESSION_ITEM', 'SESSION_ITEM', NULL, '{}', 'PENDING',
                      0, 1, 1, 1, 'LATEST_WINS', NULL)
            """.trimIndent()
        )

        AppDatabase.MIGRATION_7_8.migrate(sqlite)

        sqlite.query("SELECT accountUserId FROM sessions WHERE id = 10").use {
            it.moveToFirst()
            assertEquals(SessionEntity.LEGACY_ACCOUNT_ID, it.getLong(0))
        }
        sqlite.query("SELECT userId FROM pending_operations").use {
            it.moveToFirst()
            assertEquals(true, it.isNull(0))
        }
        helper.close()
    }

    @Test
    fun personalSessionReadsAreScopedToTheCurrentAccount() = runBlocking {
        database.sessionDao().insert(
            SessionEntity(id = 10, name = "A", accountUserId = 101)
        )
        database.sessionDao().insert(
            SessionEntity(id = 20, name = "B", accountUserId = 202)
        )

        assertEquals(10L, database.sessionDao().getActiveSession(101, null)?.id)
        assertEquals(20L, database.sessionDao().getActiveSession(202, null)?.id)
        assertNull(database.sessionDao().getActiveSession(303, null))

        database.sessionDao().insert(
            SessionEntity(
                id = 30,
                name = "Shared team",
                teamId = 77,
                accountUserId = SessionEntity.TEAM_SHARED_ACCOUNT_ID,
            )
        )
        assertEquals(30L, database.sessionDao().getActiveSession(101, 77)?.id)
        assertEquals(30L, database.sessionDao().getActiveSession(202, 77)?.id)
    }

    @Test
    fun replayUsesOnlyTheCurrentAccountsOperations() = runBlocking {
        database.distributorDao().insert(DistributorEntity(1, "Supplier"))
        database.productDao().insert(ProductEntity(7, "Product", 1))
        database.sessionDao().insert(SessionEntity(id = 10, accountUserId = 101))
        database.sessionDao().insert(SessionEntity(id = 20, accountUserId = 202))

        val json = Json { encodeDefaults = true }
        fun operation(sessionId: Long, userId: Long, key: String) =
            PendingOperationEntity(
                operationType = "ADD_SESSION_ITEM",
                resourceType = "SESSION_ITEM",
                resourceId = null,
                operationData = json.encodeToString<OperationData>(
                    OperationData.AddSessionItem(sessionId, 7, 1, key)
                ),
                status = "PENDING",
                userId = userId,
            )

        database.pendingOperationDao().insert(operation(10, 101, "user-a"))
        database.pendingOperationDao().insert(operation(20, 202, "user-b"))

        val sentSessions = mutableListOf<Long>()
        val api = Proxy.newProxyInstance(
            ListManagerApi::class.java.classLoader,
            arrayOf(ListManagerApi::class.java),
        ) { _, method, args ->
            check(method.name == "addSessionItem") { "Unexpected method: ${method.name}" }
            val sessionId = args!![0] as Long
            val request = args[1] as AddItemRequest
            sentSessions += sessionId
            Response.success(
                GlobalSessionItemDTO(
                    id = sessionId * 10,
                    session_id = sessionId,
                    product_id = request.product_id,
                    quantity = request.quantity,
                    version = 1,
                    created_at = "2026-09-13T00:00:00",
                    updated_at = "2026-09-13T00:00:00",
                    product_name = "Product",
                    distributor_name = "Supplier",
                )
            )
        } as ListManagerApi

        val userB = SyncService(
            context,
            database,
            api,
            currentUserId = { 202L },
            now = { Long.MAX_VALUE - 1 },
        )
        userB.syncPendingOperations()

        assertEquals(listOf(20L), sentSessions)
        assertEquals(1, database.pendingOperationDao().getUnfinishedForUser(101).size)
        assertEquals(0, database.pendingOperationDao().getUnfinishedForUser(202).size)

        val userA = SyncService(
            context,
            database,
            api,
            currentUserId = { 101L },
            now = { Long.MAX_VALUE - 1 },
        )
        userA.syncPendingOperations()

        assertEquals(listOf(20L, 10L), sentSessions)
        assertEquals(0, database.pendingOperationDao().getUnfinishedForUser(101).size)
    }
}
