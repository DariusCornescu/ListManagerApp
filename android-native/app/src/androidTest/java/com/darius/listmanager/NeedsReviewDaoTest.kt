package com.darius.listmanager

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.dao.NeedsReviewDao
import com.darius.listmanager.data.local.entity.NeedsReviewEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeedsReviewDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: NeedsReviewDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.needsReviewDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun insert_thenQuery_returnsRow() = runBlocking {
        val id = dao.insert(
            NeedsReviewEntity(spokenText = "cartafi", sessionId = 7L, createdAt = 1000L)
        )
        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals("cartafi", all.first().spokenText)
        assertEquals(7L, all.first().sessionId)
        assertEquals(id, all.first().id)
    }

    @Test
    fun deleteById_removesOnlyThatRow() = runBlocking {
        val id1 = dao.insert(NeedsReviewEntity(spokenText = "a", sessionId = 1L, createdAt = 1L))
        dao.insert(NeedsReviewEntity(spokenText = "b", sessionId = 1L, createdAt = 2L))
        dao.deleteById(id1)
        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals("b", all.first().spokenText)
    }

    @Test
    fun getAllFlow_emitsInserted() = runBlocking {
        dao.insert(NeedsReviewEntity(spokenText = "x", sessionId = 1L, createdAt = 1L))
        val emitted = dao.getAllFlow().first()
        assertEquals(1, emitted.size)
    }
}
