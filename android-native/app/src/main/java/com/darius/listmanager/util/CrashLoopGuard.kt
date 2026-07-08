package com.darius.listmanager.util

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Pure decision logic for the crash-loop guard, extracted for JVM testing.
 * A crash is "rapid" when it happens shortly after process start; N rapid
 * crashes in a row mean the app can't even boot (usually corrupted local
 * state), so the next start self-heals by clearing the local cache.
 */
object CrashLoopPolicy {
    const val RAPID_WINDOW_MS = 60_000L
    const val HEAL_THRESHOLD = 3

    fun isRapid(elapsedSinceStartMs: Long): Boolean = elapsedSinceStartMs < RAPID_WINDOW_MS

    fun nextCount(rapid: Boolean, previous: Int): Int = if (rapid) previous + 1 else 1

    fun shouldHeal(count: Int): Boolean = count >= HEAL_THRESHOLD
}

/**
 * Startup self-healing: if the app crashed [CrashLoopPolicy.HEAL_THRESHOLD]
 * times in a row right after starting, the next launch deletes the local Room
 * cache (catalog/session re-sync from the server) so the phone doesn't stay
 * stuck in a crash loop. Login and pending crash reports are preserved.
 */
object CrashLoopGuard {

    private const val TAG = "CrashLoopGuard"
    private const val PREFS = "crash_loop_guard"
    private const val KEY_COUNT = "rapid_crash_count"
    private const val DB_NAME = "list_manager_db"

    @Volatile
    private var processStart = 0L

    /** Call FIRST in Application.onCreate. Returns true when a self-heal ran. */
    fun onAppStart(context: Context): Boolean {
        processStart = SystemClock.elapsedRealtime()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_COUNT, 0)
        if (!CrashLoopPolicy.shouldHeal(count)) return false

        Log.w(TAG, "$count rapid crashes in a row — clearing local cache to recover")
        try {
            context.deleteDatabase(DB_NAME)
        } catch (e: Exception) {
            Log.e(TAG, "Self-heal failed: ${e.message}", e)
        }
        prefs.edit().putInt(KEY_COUNT, 0).commit()
        return true
    }

    /** Called from the crash handler; synchronous commit — the process is dying. */
    fun onCrash(context: Context) {
        val elapsed = SystemClock.elapsedRealtime() - processStart
        val rapid = processStart > 0L && CrashLoopPolicy.isRapid(elapsed)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = CrashLoopPolicy.nextCount(rapid, prefs.getInt(KEY_COUNT, 0))
        prefs.edit().putInt(KEY_COUNT, next).commit()
    }
}
