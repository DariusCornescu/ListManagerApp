package com.darius.listmanager.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.darius.listmanager.BuildConfig
import com.darius.listmanager.data.repository.AuthState
import com.darius.listmanager.network.CrashReportRequest
import com.darius.listmanager.network.RetrofitClient
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Self-hosted crash telemetry. [install] wraps the default uncaught-exception
 * handler: the stack trace is written to a local file (crash must stay fast
 * and offline-safe), then the original handler runs so Android still shows
 * the normal crash flow. On the NEXT app start, [flushPending] uploads the
 * saved reports to the backend (/api/crashes) and deletes them on success —
 * admins read them in the /admin web page. No external service involved.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val DIR_NAME = "crashes"
    private const val MAX_PENDING = 5
    private const val MAX_TRACE_CHARS = 20000

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                persist(context.applicationContext, throwable)
            } catch (_: Exception) {
                // never let crash reporting break crash handling
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun persist(context: Context, throwable: Throwable) {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        // Cap pending files so a crash loop can't fill the disk.
        val existing = dir.listFiles()?.sortedBy { it.name } ?: emptyList<File>()
        if (existing.size >= MAX_PENDING) {
            existing.take(existing.size - MAX_PENDING + 1).forEach { it.delete() }
        }
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        File(dir, "crash_${System.currentTimeMillis()}.txt")
            .writeText(writer.toString().take(MAX_TRACE_CHARS))
    }

    /** Upload saved reports (next launch, background); delete each on success. */
    suspend fun flushPending(context: Context) {
        val dir = File(context.filesDir, DIR_NAME)
        val files = dir.listFiles()?.sortedBy { it.name } ?: return
        for (file in files) {
            try {
                val response = RetrofitClient.api.reportCrash(
                    CrashReportRequest(
                        app_version = BuildConfig.VERSION_NAME,
                        android_version = Build.VERSION.RELEASE,
                        device = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(100),
                        username = AuthState.username.value,
                        stacktrace = file.readText().take(MAX_TRACE_CHARS)
                    )
                )
                if (response.isSuccessful) {
                    file.delete()
                    Log.d(TAG, "Uploaded crash report ${file.name}")
                } else {
                    Log.w(TAG, "Crash upload failed: ${response.code()} — keeping ${file.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Crash upload error (offline?): ${e.message} — keeping ${file.name}")
                return // no network — retry on a future launch
            }
        }
    }
}
