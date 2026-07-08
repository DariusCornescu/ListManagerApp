package com.darius.listmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard: the manifest must register [ListManagerApp] via
 * android:name, otherwise Android silently uses the base Application class
 * and everything wired in [ListManagerApp.onCreate] (crash reporting,
 * crash-loop guard, WorkManager setup, auth-token restore) never runs.
 * This was broken from the first commit and only surfaced when an induced
 * test crash produced no crash report.
 */
@RunWith(AndroidJUnit4::class)
class ApplicationRegistrationTest {
    @Test
    fun manifestRegistersListManagerApp() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        assertTrue(
            "Expected ListManagerApp but got ${app.javaClass.name} — " +
                "is android:name=\".ListManagerApp\" missing from the manifest?",
            app is ListManagerApp
        )
    }
}
