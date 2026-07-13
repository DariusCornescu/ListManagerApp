package com.darius.listmanager.util

import android.content.Context
import android.os.Handler
import android.os.Looper
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

    /**
     * A rapid-crash streak whose last crash is at least this old is stale:
     * an isolated incident, not a live can't-boot loop. Without this, three
     * rapid crashes spread over weeks would add up and wipe the local DB.
     */
    const val STREAK_TTL_MS = 24 * 60 * 60 * 1000L

    fun isRapid(elapsedSinceStartMs: Long): Boolean = elapsedSinceStartMs < RAPID_WINDOW_MS

    /** Negative age (wall clock moved backwards) counts as fresh — never wipe early. */
    fun isStreakStale(msSinceLastCrash: Long): Boolean = msSinceLastCrash >= STREAK_TTL_MS

    /** [msSinceLastCrash] is null when no previous crash time is recorded. */
    fun nextCount(rapid: Boolean, previous: Int, msSinceLastCrash: Long? = null): Int {
        if (!rapid) return 1
        val streak = if (msSinceLastCrash != null && isStreakStale(msSinceLastCrash)) 0 else previous
        return streak + 1
    }

    fun shouldHeal(count: Int): Boolean = count >= HEAL_THRESHOLD
}

/**
 * Startup self-healing: if the app crashed [CrashLoopPolicy.HEAL_THRESHOLD]
 * times in a row right after starting, the next launch deletes the local Room
 * cache (catalog/session re-sync from the server) so the phone doesn't stay
 * stuck in a crash loop. Login and pending crash reports are preserved.
 *
 * The streak is broken two ways so isolated incidents never add up to a wipe:
 * the counter resets once a session survives [CrashLoopPolicy.RAPID_WINDOW_MS],
 * and a crash arriving [CrashLoopPolicy.STREAK_TTL_MS] after the previous one
 * starts a fresh streak.
 */
object CrashLoopGuard {

    private const val TAG = "CrashLoopGuard"
    private const val PREFS = "crash_loop_guard"
    private const val KEY_COUNT = "rapid_crash_count"
    private const val KEY_LAST_CRASH_AT = "last_crash_at"
    private const val DB_NAME = "list_manager_db"

    @Volatile
    private var processStart = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var survivalReset: Runnable? = null

    /** Call FIRST in Application.onCreate. Returns true when a self-heal ran. */
    fun onAppStart(context: Context): Boolean {
        processStart = SystemClock.elapsedRealtime()
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Surviving the rapid window proves this session is healthy — break the
        // streak so isolated rapid crashes can never add up to a false heal.
        val reset = Runnable {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_COUNT, 0)
                .remove(KEY_LAST_CRASH_AT)
                .apply()
        }
        survivalReset = reset
        mainHandler.postDelayed(reset, CrashLoopPolicy.RAPID_WINDOW_MS)

        val count = prefs.getInt(KEY_COUNT, 0)
        if (!CrashLoopPolicy.shouldHeal(count)) return false

        Log.w(TAG, "$count rapid crashes in a row — clearing local cache to recover")
        try {
            context.deleteDatabase(DB_NAME)
        } catch (e: Exception) {
            Log.e(TAG, "Self-heal failed: ${e.message}", e)
        }
        prefs.edit().putInt(KEY_COUNT, 0).remove(KEY_LAST_CRASH_AT).commit()
        return true
    }

    /** Called from the crash handler; synchronous commit — the process is dying. */
    fun onCrash(context: Context) {
        // Don't let the pending survival reset race the dying process.
        survivalReset?.let(mainHandler::removeCallbacks)
        val elapsed = SystemClock.elapsedRealtime() - processStart
        val rapid = processStart > 0L && CrashLoopPolicy.isRapid(elapsed)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCrashAt = prefs.getLong(KEY_LAST_CRASH_AT, 0L)
        val sinceLast = if (lastCrashAt > 0L) now - lastCrashAt else null
        val next = CrashLoopPolicy.nextCount(rapid, prefs.getInt(KEY_COUNT, 0), sinceLast)
        prefs.edit().putInt(KEY_COUNT, next).putLong(KEY_LAST_CRASH_AT, now).commit()
    }
}
