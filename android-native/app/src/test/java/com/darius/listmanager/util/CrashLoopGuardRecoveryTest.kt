package com.darius.listmanager.util

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CrashLoopGuardRecoveryTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("crash_loop_guard", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.deleteDatabase("list_manager_db")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("crash_loop_guard", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.deleteDatabase("list_manager_db")
    }

    @Test
    fun threeRapidStartupCrashes_clearDatabaseAndResetTheStreak() {
        context.openOrCreateDatabase("list_manager_db", Context.MODE_PRIVATE, null).close()
        assertTrue(context.getDatabasePath("list_manager_db").exists())

        repeat(CrashLoopPolicy.HEAL_THRESHOLD) {
            assertFalse(CrashLoopGuard.onAppStart(context))
            ShadowSystemClock.advanceBy(Duration.ofMillis(10))
            CrashLoopGuard.onCrash(context)
        }

        assertTrue(CrashLoopGuard.onAppStart(context))
        assertFalse(context.getDatabasePath("list_manager_db").exists())
        assertFalse(CrashLoopGuard.onAppStart(context))
    }
}
