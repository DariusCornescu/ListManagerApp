package com.darius.listmanager.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashReporterEventIdTest {
    @Test
    fun pendingFileKeepsTheSameEventIdAcrossRetries() {
        val pending = File("crash_7c905d24-7f67-48eb-9169-13d63fbc70ac.txt")

        val firstAttempt = CrashReporter.eventIdFor(pending)
        val retry = CrashReporter.eventIdFor(pending)

        assertEquals("crash_7c905d24-7f67-48eb-9169-13d63fbc70ac", firstAttempt)
        assertEquals(firstAttempt, retry)
    }
}
