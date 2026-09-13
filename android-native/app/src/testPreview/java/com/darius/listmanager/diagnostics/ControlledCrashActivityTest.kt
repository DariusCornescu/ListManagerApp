package com.darius.listmanager.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ControlledCrashActivityTest {
    @Test
    fun controlledCrashHasRecognizableSignature() {
        try {
            Robolectric.buildActivity(ControlledCrashActivity::class.java).create()
            fail("The preview diagnostic activity must crash during creation")
        } catch (error: RuntimeException) {
            val controlled = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<ControlledCrashException>()
                .firstOrNull()
            if (controlled == null) throw error
            assertEquals("ListManager controlled preview crash", controlled.message)
        }
    }
}
