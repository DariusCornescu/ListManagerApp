package com.darius.listmanager.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import com.darius.listmanager.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appdistribution.FirebaseAppDistribution
import com.google.firebase.appdistribution.FirebaseAppDistributionException
import com.google.firebase.appdistribution.UpdateStatus

/**
 * In-app update prompt for builds sent through Firebase App Distribution (DEPLOYMENT.md,
 * "Android updates"). When a newer release exists, the SDK shows its own dialog, downloads the
 * APK and opens the Android installer. Only release builds carry the full SDK and the Firebase
 * settings; everywhere else every call here does nothing.
 */
object AppUpdates {
    private const val TAG = "AppUpdates"
    private const val PREFS = "app_updates"
    private const val KEY_LAST_CHECK = "last_check_millis"
    internal const val CHECK_INTERVAL_MILLIS = 60 * 60 * 1000L

    @Volatile private var ready = false
    @Volatile private var checking = false
    @Volatile private var checkedThisProcess = false
    @Volatile private var lastStatus: UpdateStatus? = null

    /** True when this build can check for updates: Firebase settings were given at build time. */
    val isAvailable: Boolean get() = ready

    fun init(app: Application) {
        if (BuildConfig.FIREBASE_APP_ID.isBlank()) return
        try {
            if (FirebaseApp.getApps(app).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .apply { senderIdFrom(BuildConfig.FIREBASE_APP_ID)?.let(::setGcmSenderId) }
                    .build()
                FirebaseApp.initializeApp(app, options)
            }
            ready = true
        } catch (e: Exception) {
            // The app must start even if Firebase cannot; updates then come through App Tester.
            Log.w(TAG, "Firebase did not start, no in-app update check: ${e.message}")
        }
    }

    /**
     * Every time the app starts, then at most once an hour when it comes back to the front, so a
     * dismissed prompt doesn't nag. Before, the hour also held back a fresh start: a version
     * published minutes after the last check was not offered until the hour passed.
     */
    fun checkIfDue(activity: Activity) {
        if (!ready) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!shouldCheck(checkedThisProcess, last, now)) {
            Log.d(TAG, "Update check skipped: the last one was ${(now - last) / 60_000} min ago")
            return
        }
        checkedThisProcess = true
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        check()
    }

    /** For the button on About: someone who dismissed the prompt can ask again. */
    fun checkNow(activity: Activity) {
        if (!ready) return
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        check()
    }

    private fun check() {
        // The SDK's sign-in page and dialog resume the activity; don't start a second check then.
        if (checking) return
        checking = true
        lastStatus = null
        Log.i(TAG, "Checking for a newer release")
        try {
            FirebaseAppDistribution.getInstance().updateIfNewReleaseAvailable()
                // Logged so a phone's log shows what happened, including "no newer release".
                // The SDK reports DOWNLOADING about four times a second; log each status once.
                .addOnProgressListener { progress ->
                    if (progress.updateStatus != lastStatus) {
                        lastStatus = progress.updateStatus
                        Log.i(TAG, "Update status: ${progress.updateStatus}")
                    }
                }
                .addOnSuccessListener { Log.i(TAG, "Update check finished") }
                .addOnFailureListener { e ->
                    val status = (e as? FirebaseAppDistributionException)?.errorCode
                    Log.i(TAG, "Update check ended without an update: $status ${e.message}")
                }
                .addOnCompleteListener { checking = false }
        } catch (e: Exception) {
            checking = false
            Log.w(TAG, "Update check could not start: ${e.message}")
        }
    }

    internal fun shouldCheck(checkedThisProcess: Boolean, lastCheckMillis: Long, nowMillis: Long): Boolean =
        !checkedThisProcess || isCheckDue(lastCheckMillis, nowMillis)

    internal fun isCheckDue(lastCheckMillis: Long, nowMillis: Long): Boolean =
        // A clock set back would otherwise block checks until it caught up.
        lastCheckMillis <= 0L || nowMillis < lastCheckMillis ||
            nowMillis - lastCheckMillis >= CHECK_INTERVAL_MILLIS

    /** The project number, the second part of an app ID like `1:971486621873:android:abc`. */
    internal fun senderIdFrom(appId: String): String? =
        appId.split(':').getOrNull(1)?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
}
