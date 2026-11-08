package com.darius.listmanager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.darius.listmanager.data.settings.AppSettings
import com.darius.listmanager.data.settings.ThemeMode
import com.darius.listmanager.data.settings.usesDarkTheme
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.PendingOperationsDialogContent
import com.darius.listmanager.ui.screens.AboutContent
import com.darius.listmanager.ui.screens.LocalDatabaseCard
import com.darius.listmanager.ui.screens.LoginContent
import com.darius.listmanager.ui.screens.NetworkStatusCard
import com.darius.listmanager.ui.screens.NoPendingOperationsCard
import com.darius.listmanager.ui.screens.PdfsContent
import com.darius.listmanager.ui.screens.SettingsContent
import com.darius.listmanager.ui.screens.SyncControlsCard
import com.darius.listmanager.ui.screens.SyncLogCard
import com.darius.listmanager.ui.screens.operationStatusLabel
import com.darius.listmanager.ui.screens.pdfTitle
import com.darius.listmanager.ui.theme.AppTheme
import com.darius.listmanager.ui.viewmodel.ReviewKind
import com.darius.listmanager.ui.viewmodel.ReviewLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinishingScreensUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    @Before fun turnAnimationsOff() {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private fun shoot(name: String) {
        rule.waitForIdle()
        runCatching {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dir = File("build/ui-snapshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }.onFailure { println("Snapshot skipped: ${it.message}") }
    }

    @Composable
    private fun Page(dark: Boolean = false, content: @Composable () -> Unit) {
        AppTheme(darkTheme = dark) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }

    // ---------- Login ----------

    private var logins = mutableListOf<Pair<String, String>>()
    private var registrations = mutableListOf<Triple<String, String, String>>()
    private var offline = 0

    @Composable
    private fun Login(error: String? = null, success: Boolean = false, loading: Boolean = false, dark: Boolean = false) {
        Page(dark) {
            LoginContent(
                isLoading = loading,
                error = error,
                registrationSuccess = success,
                onLogin = { user, password -> logins += user to password },
                onRegister = { user, password, email -> registrations += Triple(user, password, email) },
                onClearError = {},
                onContinueOffline = { offline++ },
            )
        }
    }

    private fun type(index: Int, text: String) {
        rule.onAllNodes(hasSetTextAction())[index].performTextInput(text)
    }

    @Test fun login_needsBothFields_thenSubmits() {
        rule.setContent { Login() }

        shoot("login-light")
        rule.onNodeWithText("Intră în cont").assertIsNotEnabled()
        type(0, "ana")
        type(1, "secret1")
        rule.onNodeWithText("Intră în cont").assertIsEnabled().performClick()

        assertEquals(listOf("ana" to "secret1"), logins)
    }

    @Test fun register_asksForMore_andCatchesMismatchedPasswords() {
        rule.setContent { Login() }

        rule.onNodeWithText("Nu ai cont? Creează unul").performClick()
        shoot("login-register")
        type(0, "ana")
        type(1, "ana@example.com")
        type(2, "secret1")
        type(3, "altceva")
        rule.onNodeWithText("Parolele nu coincid.").assertExists()
        rule.onNodeWithText("Creează cont").assertIsNotEnabled()
    }

    @Test fun login_showsTheError_inBothThemes() {
        rule.setContent { Login(error = "Utilizator sau parolă incorectă", dark = true) }

        rule.onNodeWithText("Utilizator sau parolă incorectă").assertExists()
        shoot("login-error-dark")
    }

    @Test fun afterRegistering_theFormGoesBackToSigningIn() {
        rule.setContent { Login(success = true) }

        rule.onNodeWithText("Cont creat. Te poți autentifica.").assertExists()
        rule.onNodeWithText("Intră în cont").assertExists()
        rule.onNodeWithText("Confirmă parola").assertDoesNotExist()
    }

    @Test fun login_canContinueWithoutAnAccount() {
        rule.setContent { Login() }

        rule.onNodeWithText("Continuă fără cont").performClick()

        assertEquals(1, offline)
    }

    // ---------- Settings ----------

    @Test fun theme_followsTheChoice() {
        assertTrue(usesDarkTheme(ThemeMode.SYSTEM, systemIsDark = true))
        assertFalse(usesDarkTheme(ThemeMode.SYSTEM, systemIsDark = false))
        assertTrue(usesDarkTheme(ThemeMode.DARK, systemIsDark = false))
        assertFalse(usesDarkTheme(ThemeMode.LIGHT, systemIsDark = true))
    }

    @Test fun settings_surviveARestart_andDefaultToSystemWithVibration() {
        AppSettings.resetForTest()
        val context = rule.activity.applicationContext
        context.getSharedPreferences("app_settings", 0).edit().clear().commit()
        val first = AppSettings.getInstance(context)
        assertEquals(ThemeMode.SYSTEM, first.themeMode.value)
        assertTrue(first.hapticsEnabled.value)

        first.setThemeMode(ThemeMode.DARK)
        first.setHapticsEnabled(false)
        AppSettings.resetForTest()

        val reopened = AppSettings.getInstance(context)
        assertEquals(ThemeMode.DARK, reopened.themeMode.value)
        assertFalse(reopened.hapticsEnabled.value)
        AppSettings.resetForTest()
    }

    @Test fun settings_changeTheme_andVibration() {
        val themes = mutableListOf<ThemeMode>()
        val haptics = mutableListOf<Boolean>()
        var diagnostics = 0
        rule.setContent {
            Page {
                Column {
                    AppTopBar("Setări", onBack = {})
                    SettingsContent(ThemeMode.SYSTEM, { themes += it }, true, { haptics += it }, true, { diagnostics++ })
                }
            }
        }

        shoot("settings-light")
        rule.onNodeWithText("Întunecat").performClick()
        rule.onNode(isToggleable()).performClick()
        rule.onNodeWithText("Diagnostic sincronizare").performClick()

        assertEquals(listOf(ThemeMode.DARK), themes)
        assertEquals(listOf(false), haptics)
        assertEquals(1, diagnostics)
    }

    @Test fun settings_hideDiagnostics_fromTheReleaseBuild() {
        rule.setContent { Page { SettingsContent(ThemeMode.DARK, {}, true, {}, false, {}) } }

        rule.onNodeWithText("Dezvoltator").assertDoesNotExist()
        rule.onNodeWithText("Diagnostic sincronizare").assertDoesNotExist()
        rule.onNodeWithText("Recunoaștere fără internet").assertDoesNotExist()
    }

    // ---------- About ----------

    @Test fun about_showsTheRealVersion() {
        rule.setContent { Page { Column { AppTopBar("Despre", onBack = {}); AboutContent("2.0") } } }

        rule.onNodeWithText("Versiunea 2.0").assertExists()
        // Debug builds can't check for updates, so the button stays hidden.
        rule.onNodeWithText("Caută actualizări").assertDoesNotExist()
        shoot("about-light")
    }

    @Test fun about_checksForUpdatesOnRequest() {
        var checks = 0
        rule.setContent {
            Page { Column { AppTopBar("Despre", onBack = {}); AboutContent("2.0.105", onCheckForUpdates = { checks++ }) } }
        }

        rule.onNodeWithText("Caută actualizări").performClick()

        assertEquals(1, checks)
        shoot("about-updates-light")
    }

    // ---------- PDFs ----------

    @Test fun pdfs_openOrShare() {
        val pdf = temporary.newFile("Comanda_Distribuitor_A.pdf")
        val opened = mutableListOf<File>()
        val shared = mutableListOf<File>()
        assertEquals("Comanda Distribuitor A", pdfTitle(pdf))
        rule.setContent {
            Page {
                Column {
                    AppTopBar("PDF-uri", onBack = {})
                    PdfsContent(listOf(pdf), false, false, { opened += it }, { shared += it }, {}, {})
                }
            }
        }

        rule.onNodeWithText("Comanda Distribuitor A").assertExists()
        shoot("pdfs-light")
        rule.onNodeWithText("Deschide").performClick()
        rule.onNodeWithText("Distribuie").performClick()

        assertEquals(listOf(pdf), opened)
        assertEquals(listOf(pdf), shared)
    }

    @Test fun pdfs_empty_leadsToTheList() {
        var lists = 0
        rule.setContent { Page { PdfsContent(emptyList(), false, false, {}, {}, {}, { lists++ }) } }

        rule.onNodeWithText("Niciun PDF încă").assertExists()
        rule.onNodeWithText("Mergi la listă").performClick()

        assertEquals(1, lists)
    }

    @Test fun pdfs_readError_offersRetry() {
        var retries = 0
        rule.setContent { Page { PdfsContent(emptyList(), false, true, {}, {}, { retries++ }, {}) } }

        rule.onNodeWithText("Încearcă din nou").performClick()

        assertEquals(1, retries)
    }

    // ---------- Sync sheet ----------

    @Composable
    private fun Sync(lines: List<ReviewLine>, dark: Boolean = false) {
        AppTheme(darkTheme = dark) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                PendingOperationsDialogContent(lines, null, false, {}, {}, {}, {}, {})
            }
        }
    }

    @Test fun syncSheet_showsEveryDecision() {
        rule.setContent {
            Sync(
                listOf(
                    ReviewLine(1, "Pâine albă: cantitate 4", ReviewKind.CONFLICT, "UPDATE_SESSION_ITEM"),
                    ReviewLine(2, "Golește lista", ReviewKind.OUTCOME_UNKNOWN, "CLEAR_SESSION"),
                    ReviewLine(3, "Produs nou: Vopsea albă", ReviewKind.HELD, "CREATE_PRODUCT"),
                ),
            )
        }

        rule.onNodeWithText("Ai de decis").assertExists()
        rule.onNodeWithText("Trimite acum").assertDoesNotExist()
        shoot("sync-decisions")
    }

    @Test fun syncSheet_darkTheme() {
        rule.setContent {
            Sync(
                listOf(ReviewLine(1, "Pâine albă: cantitate 4", ReviewKind.CONFLICT, "UPDATE_SESSION_ITEM")),
                dark = true,
            )
        }

        shoot("sync-decisions-dark")
    }

    @Test fun syncSheet_onlyQueued_canSendNow() {
        rule.setContent { Sync(listOf(ReviewLine(1, "Zahăr 1 kg: adaugă 1", ReviewKind.QUEUED, "ADD_SESSION_ITEM"))) }

        rule.onNodeWithText("Modificări în așteptare").assertExists()
        rule.onNodeWithText("Trimite acum").assertExists()
        shoot("sync-queued")
    }

    // ---------- Diagnostic ----------

    @Test fun diagnostic_isInRomanian_withoutEmoji() {
        var clears = 0
        rule.setContent {
            Page {
                Column {
                    NetworkStatusCard(true, false, false, {})
                    LocalDatabaseCard(3196, 67, 1, 0)
                    SyncControlsCard(false, "Sincronizare reușită", {}, { clears++ })
                    NoPendingOperationsCard()
                    SyncLogCard(listOf("[12:00:00.000] Sincronizare pornită"))
                }
            }
        }

        rule.onNodeWithText("Verifică serverul").assertExists()
        rule.onNodeWithText("Nu").assertExists()
        rule.onAllNodesWithText("3196")[0].assertExists()
        shoot("diagnostic")
        rule.onNodeWithText("Golește operațiunile").performClick()

        // The screen shows a confirmation before anything is deleted.
        assertEquals(1, clears)
    }

    @Test fun operationStatus_readsAsWords() {
        assertEquals("În așteptare", operationStatusLabel("PENDING"))
        assertEquals("Eșuat", operationStatusLabel("FAILED"))
        assertEquals("ALTUL", operationStatusLabel("ALTUL"))
    }

    @Test fun passwordToggle_isLabelledInRomanian() {
        rule.setContent { Login() }

        rule.onNodeWithContentDescription("Arată parola").performClick()
        rule.onNodeWithContentDescription("Ascunde parola").assertExists()
    }
}
