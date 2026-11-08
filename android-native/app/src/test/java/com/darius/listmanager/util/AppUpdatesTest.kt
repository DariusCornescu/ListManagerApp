package com.darius.listmanager.util

import android.app.Activity
import android.content.Context
import com.darius.listmanager.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AppUpdatesTest {
    private val hour = AppUpdates.CHECK_INTERVAL_MILLIS
    private val now = 1_800_000_000_000L

    @Test fun firstCheckIsAlwaysDue() {
        assertTrue(AppUpdates.isCheckDue(lastCheckMillis = 0L, nowMillis = now))
    }

    @Test fun notAgainWithinTheHour() {
        assertFalse(AppUpdates.isCheckDue(now - hour + 1, now))
        assertTrue(AppUpdates.isCheckDue(now - hour, now))
    }

    @Test fun aClockSetBackDoesNotBlockChecks() {
        assertTrue(AppUpdates.isCheckDue(lastCheckMillis = now + 5 * hour, nowMillis = now))
    }

    @Test fun senderIdIsTheProjectNumberInTheAppId() {
        assertEquals("971486621873", AppUpdates.senderIdFrom("1:971486621873:android:e8f61463d5863731ddb290"))
        assertNull(AppUpdates.senderIdFrom("not-an-app-id"))
        assertNull(AppUpdates.senderIdFrom(""))
    }

    @Test fun withoutFirebaseSettingsNothingStartsOrIsRecorded() {
        // Only meaningful without Firebase settings: debug and preview, and CI's plain test runs.
        // Android distribute runs the release tests with the settings, and then Firebase starts.
        assumeTrue(BuildConfig.FIREBASE_APP_ID.isBlank())
        val app = RuntimeEnvironment.getApplication()
        AppUpdates.init(app)
        assertFalse(AppUpdates.isAvailable)

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        AppUpdates.checkIfDue(activity)
        AppUpdates.checkNow(activity)

        val prefs = app.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        assertTrue(prefs.all.isEmpty())
    }
}
