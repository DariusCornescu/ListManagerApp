package com.darius.listmanager.util

import android.util.Log
import com.darius.listmanager.BuildConfig

internal inline fun selectSensitiveLogMessage(
    includeSensitiveContent: Boolean,
    safeMessage: String,
    sensitiveMessage: () -> String
): String = if (includeSensitiveContent) sensitiveMessage() else safeMessage

/**
 * Keeps dictated and typed text out of release logs while preserving detailed
 * diagnostics in local debug and Preview builds.
 */
object SensitiveLog {
    fun d(tag: String, safeMessage: String, sensitiveMessage: () -> String) {
        Log.d(tag, selectSensitiveLogMessage(BuildConfig.DEBUG, safeMessage, sensitiveMessage))
    }

    fun e(tag: String, safeMessage: String, throwable: Throwable, sensitiveMessage: () -> String) {
        Log.e(
            tag,
            selectSensitiveLogMessage(BuildConfig.DEBUG, safeMessage, sensitiveMessage),
            throwable
        )
    }
}
