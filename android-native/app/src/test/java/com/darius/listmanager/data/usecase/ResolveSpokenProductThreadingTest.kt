package com.darius.listmanager.data.usecase

import androidx.room.Room
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.network.ListManagerApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Dictation froze the phone until Android showed "isn't responding", because the
 * whole catalog was scored on the main thread. The use case has to hand that work
 * to its own dispatcher before doing anything on the caller's thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ResolveSpokenProductThreadingTest {
    private lateinit var database: AppDatabase

    /** Holds work back until the test lets it run. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val held = ConcurrentLinkedQueue<Runnable>()
        @Volatile private var open = false
        val heldCount: Int get() = held.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (open) {
                Dispatchers.Default.dispatch(context, block)
            } else {
                held.add(block)
            }
        }

        fun release() {
            open = true
            generateSequence { held.poll() }.forEach { Dispatchers.Default.dispatch(EmptyCoroutineContext, it) }
        }
    }

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        runBlocking {
            database.distributorDao().insert(DistributorEntity(1, "Furnizor"))
            database.productDao().insert(ProductEntity(7, "Burete tabla", 1))
        }
    }

    @After
    fun close() = database.close()

    private fun useCase(dispatcher: CoroutineDispatcher): ResolveSpokenProductUseCase {
        val api = Proxy.newProxyInstance(
            ListManagerApi::class.java.classLoader,
            arrayOf(ListManagerApi::class.java),
        ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as ListManagerApi
        val repository = ProductRepository(
            dao = database.productDao(),
            pendingOperationRepository = PendingOperationRepository(database.pendingOperationDao()),
            context = RuntimeEnvironment.getApplication(),
            api = api,
            database = database,
            isOnline = { false },
        )
        return ResolveSpokenProductUseCase(repository, computeDispatcher = dispatcher)
    }

    @Test
    fun resolveMultipleLeavesTheCallersThreadBeforeScoring() = runBlocking {
        val dispatcher = HeldDispatcher()
        val useCase = useCase(dispatcher)

        val pending = async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
            useCase.resolveMultiple("burete tabla")
        }

        assertEquals(1, dispatcher.heldCount)
        assertFalse(pending.isCompleted)

        dispatcher.release()
        val match = pending.await().single().second as ResolveResult.AutoAdd
        assertEquals(7L, match.product.id)
    }

    @Test
    fun executeLeavesTheCallersThreadBeforeScoring() = runBlocking {
        val dispatcher = HeldDispatcher()
        val useCase = useCase(dispatcher)

        val pending = async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
            useCase.execute("burete tabla")
        }

        assertEquals(1, dispatcher.heldCount)
        assertFalse(pending.isCompleted)

        dispatcher.release()
        val match = pending.await() as ResolveResult.AutoAdd
        assertEquals(7L, match.product.id)
    }
}
