package com.darius.listmanager.data.usecase

import androidx.room.Room
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.SessionEntity
import com.darius.listmanager.data.repository.PdfRepository
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.data.repository.SessionRepository
import com.darius.listmanager.data.repository.SessionTransaction
import com.darius.listmanager.network.ListManagerApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.lang.reflect.Proxy

/**
 * PDF generation through the real repositories, offline. Rendering itself cannot be exercised
 * here: under Robolectric android.graphics.pdf.PdfDocument throws "document is closed!" on
 * startPage, so one-PDF-per-distributor content and the "Page X of N" footer stay device-only.
 * Pagination maths is covered by PdfRepositoryPaginationTest, file naming by PdfFileNameTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GeneratePdfsUseCaseTest {
    private lateinit var db: AppDatabase
    private lateinit var useCase: GeneratePdfsUseCase
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val offlineApi = Proxy.newProxyInstance(
            ListManagerApi::class.java.classLoader, arrayOf(ListManagerApi::class.java)
        ) { _, method, _ -> error("PDF generation must not call the network: ${method.name}") } as ListManagerApi
        val sessions = SessionRepository(
            db.sessionDao(), db.sessionItemDao(), offlineApi,
            PendingOperationRepository(db.pendingOperationDao()), SessionTransaction.room(db)
        ) { null }
        useCase = GeneratePdfsUseCase(sessions, PdfRepository(context))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun emptySessionProducesNoPdfs() = runBlocking {
        db.sessionDao().insert(SessionEntity(id = 10))
        assertEquals(emptyMap<String, File>(), useCase.execute(10))
    }
}
