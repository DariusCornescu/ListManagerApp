package com.darius.listmanager.util

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Release builds only, where the full Firebase App Distribution SDK is. Its install screen is an
 * AppCompat activity; with the app's own theme it crashed as an update began to install. Runs in
 * Android distribute's testReleaseUnitTest, before anything is sent to the phones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdatesInstallThemeTest {
    @Test fun theSdkInstallScreenHasAnAppCompatTheme() {
        val app = RuntimeEnvironment.getApplication()
        // The int-flags overload: Robolectric 4.14 doesn't implement the ComponentInfoFlags one.
        @Suppress("DEPRECATION")
        val info = app.packageManager.getActivityInfo(
            ComponentName(app, "com.google.firebase.appdistribution.impl.InstallActivity"),
            0,
        )
        assertEquals(androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar, info.theme)
    }
}
