package com.darius.listmanager.util

import com.darius.listmanager.BuildConfig
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SensitiveLogTest {
    @Before
    fun clearLogs() {
        ShadowLog.clear()
    }

    @Test
    fun `release message omits sensitive content without evaluating it`() {
        var evaluated = false

        val message = selectSensitiveLogMessage(
            includeSensitiveContent = false,
            safeMessage = "Final result received (3 hypotheses)"
        ) {
            evaluated = true
            "Final result: 'două lapte Zuzu'"
        }

        assertEquals("Final result received (3 hypotheses)", message)
        assertFalse(evaluated)
        assertFalse(message.contains("lapte"))
    }

    @Test
    fun `diagnostic message retains content for explicit local inspection`() {
        var evaluated = false

        val message = selectSensitiveLogMessage(
            includeSensitiveContent = true,
            safeMessage = "Final result received"
        ) {
            evaluated = true
            "Final result: 'două lapte Zuzu'"
        }

        assertTrue(evaluated)
        assertEquals("Final result: 'două lapte Zuzu'", message)
    }

    @Test
    fun `logger follows the active build privacy mode`() {
        SensitiveLog.d("SensitiveLogTest", "Final result received") {
            "Final result: 'două lapte Zuzu'"
        }

        val message = ShadowLog.getLogsForTag("SensitiveLogTest").single().msg
        if (BuildConfig.DEBUG) {
            assertEquals("Final result: 'două lapte Zuzu'", message)
        } else {
            assertEquals("Final result received", message)
            assertFalse(message.contains("lapte"))
        }
    }
}
