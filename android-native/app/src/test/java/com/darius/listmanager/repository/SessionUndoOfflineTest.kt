package com.darius.listmanager.repository

import androidx.room.Room
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.local.entity.SessionEntity
import com.darius.listmanager.data.model.OperationData
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.data.repository.SessionRepository
import com.darius.listmanager.data.repository.SessionTransaction
import com.darius.listmanager.data.usecase.UndoAddUseCase
import com.darius.listmanager.network.ListManagerApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.IOException
import java.lang.reflect.Proxy

/** Undo on a real Room database with no connection. Every step must wait in the queue, in order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SessionUndoOfflineTest {
    private lateinit var database: AppDatabase
    private lateinit var pending: PendingOperationRepository
    private lateinit var repository: SessionRepository

    private val offlineApi = Proxy.newProxyInstance(
        ListManagerApi::class.java.classLoader, arrayOf(ListManagerApi::class.java)
    ) { _, _, _ -> throw IOException("No connection") } as ListManagerApi

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.distributorDao().insert(DistributorEntity(1, "Distribuitor A"))
        database.productDao().insert(ProductEntity(7, "Pâine albă", 1))
        database.sessionDao().insert(SessionEntity(id = 10))
        pending = PendingOperationRepository(database.pendingOperationDao())
        repository = SessionRepository(
            database.sessionDao(), database.sessionItemDao(), offlineApi, pending, SessionTransaction.room(database),
        )
    }

    @After fun close() = database.close()

    private suspend fun queuedKinds(): List<String> = pending.getUnfinished()
        .sortedBy { it.id }
        .map { Json.decodeFromString<OperationData>(it.operationData)::class.simpleName.orEmpty() }

    @Test fun undoingAnAdd_keepsWhatWasThereBefore() = runBlocking {
        repository.addOrIncrementItem(10, 7, 3)
        repository.addOrIncrementItem(10, 7, 2)

        UndoAddUseCase(repository).execute(10, 7, 2)

        assertEquals(3, database.sessionItemDao().getItem(10, 7)!!.quantity)
        assertEquals(listOf("AddSessionItem", "AddSessionItem", "UpdateSessionItem"), queuedKinds())
    }

    @Test fun undoingTheOnlyAdd_removesTheProduct() = runBlocking {
        repository.addOrIncrementItem(10, 7, 2)

        UndoAddUseCase(repository).execute(10, 7, 2)

        assertNull(database.sessionItemDao().getItem(10, 7))
        assertEquals(listOf("AddSessionItem", "DeleteSessionItem"), queuedKinds())
    }

    @Test fun undoingAnAdd_afterTheProductWasDeleted_changesNothing() = runBlocking {
        repository.addOrIncrementItem(10, 7, 2)
        val item = database.sessionItemDao().getItem(10, 7)!!
        repository.deleteItem(item.id)
        val before = queuedKinds()

        UndoAddUseCase(repository).execute(10, 7, 2)

        assertEquals(before, queuedKinds())
    }

    @Test fun undoingADelete_putsTheQuantityBack() = runBlocking {
        repository.addOrIncrementItem(10, 7, 3)
        val item = database.sessionItemDao().getItem(10, 7)!!
        repository.deleteItem(item.id)
        assertNull(database.sessionItemDao().getItem(10, 7))

        repository.addOrIncrementItem(10, 7, 3)

        assertEquals(3, database.sessionItemDao().getItem(10, 7)!!.quantity)
        assertEquals(listOf("AddSessionItem", "DeleteSessionItem", "AddSessionItem"), queuedKinds())
        assertTrue(pending.getUnfinished().isNotEmpty())
    }
}
