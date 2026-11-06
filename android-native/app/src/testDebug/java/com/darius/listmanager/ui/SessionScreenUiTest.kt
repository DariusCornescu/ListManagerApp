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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.darius.listmanager.data.local.dao.SessionItemWithProduct
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.ConfirmSheetContent
import com.darius.listmanager.ui.screens.PdfsReadyContent
import com.darius.listmanager.ui.screens.SessionContent
import com.darius.listmanager.ui.screens.SessionFooter
import com.darius.listmanager.ui.screens.sessionSubtitle
import com.darius.listmanager.ui.theme.AppTheme
import com.darius.listmanager.ui.viewmodel.SessionUiState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionScreenUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val bread = SessionItemWithProduct(1, 10, 101, "Pâine albă", 1, "Distribuitor A", 2)
    private val milk = SessionItemWithProduct(2, 10, 102, "Lapte 1 L", 1, "Distribuitor A", 1)
    private val flour = SessionItemWithProduct(3, 10, 103, "Făină albă 1 kg", 2, "Distribuitor B", 3)
    private val full = SessionUiState(items = listOf(bread, milk, flour), isLoading = false)

    private val quantities = mutableListOf<Pair<Long, Int>>()
    private val deleted = mutableListOf<Long>()
    private var retries = 0
    private var records = 0

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
    private fun Screen(state: SessionUiState, dark: Boolean = false, generating: Boolean = false) {
        AppTheme(darkTheme = dark) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column {
                    AppTopBar("Lista curentă", subtitle = sessionSubtitle(state), onBack = {})
                    SessionContent(
                        state = state,
                        onQuantityChange = { item, quantity -> quantities += item.id to quantity },
                        onDelete = { deleted += it.id },
                        onRetry = { retries++ },
                        onRecord = { records++ },
                        modifier = Modifier.weight(1f),
                    )
                    if (state.items.isNotEmpty()) SessionFooter(generating, {})
                }
            }
        }
    }

    @Test fun list_groupsByDistributor_withCounts() {
        rule.setContent { Screen(full) }

        rule.onNodeWithText("Distribuitor A").assertExists()
        rule.onNodeWithText("2 produse").assertExists()
        rule.onNodeWithText("Distribuitor B").assertExists()
        rule.onNodeWithText("1 produs").assertExists()
        rule.onNodeWithText("Personal · 3 produse").assertExists()
        shoot("list-light")
    }

    @Test fun list_darkTheme() {
        rule.setContent { Screen(full, dark = true) }

        rule.onNodeWithText("Pâine albă").assertExists()
        shoot("list-dark")
    }

    @Test fun plusAndMinus_changeTheQuantity() {
        rule.setContent { Screen(full) }

        rule.onNodeWithContentDescription("Crește cantitatea pentru Pâine albă").performClick()
        rule.onNodeWithContentDescription("Scade cantitatea pentru Pâine albă").performClick()

        assertEquals(listOf(1L to 3, 1L to 1), quantities)
    }

    @Test fun minusAtOne_becomesDelete_soSwipingIsntTheOnlyWay() {
        rule.setContent { Screen(full) }

        rule.onNodeWithContentDescription("Scade cantitatea pentru Lapte 1 L").assertDoesNotExist()
        rule.onNodeWithContentDescription("Șterge Lapte 1 L").performClick()

        assertEquals(listOf(2L), deleted)
        assertEquals(emptyList<Pair<Long, Int>>(), quantities)
    }

    @Test fun swipingLeft_deletesTheRow() {
        rule.setContent { Screen(full) }

        rule.onNodeWithText("Făină albă 1 kg").performTouchInput { swipeLeft() }
        rule.waitForIdle()

        assertEquals(listOf(3L), deleted)
    }

    @Test fun loading_showsPlaceholderRows() {
        rule.setContent { Screen(SessionUiState()) }

        rule.onNodeWithContentDescription("Se încarcă").assertExists()
        shoot("list-loading")
    }

    @Test fun loadError_offersRetry() {
        rule.setContent { Screen(SessionUiState(isLoading = false, error = "x")) }

        rule.onNodeWithText("Nu am putut încărca lista").assertExists()
        rule.onNodeWithText("Încearcă din nou").performClick()

        assertEquals(1, retries)
    }

    @Test fun empty_leadsBackToRecording() {
        rule.setContent { Screen(SessionUiState(isLoading = false)) }

        rule.onNodeWithText("Lista e goală").assertExists()
        shoot("list-empty")
        rule.onNodeWithText("Înregistrează").performClick()

        assertEquals(1, records)
    }

    @Test fun generating_cannotBeStartedTwice() {
        rule.setContent { Screen(full, generating = true) }

        rule.onNodeWithText("Se generează…").assertIsNotEnabled()
    }

    @Test fun clearConfirmation_namesWhatHappens() {
        var cleared = 0
        rule.setContent {
            AppTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    ConfirmSheetContent(
                        title = "Golești lista?",
                        message = "Toate produsele vor fi șterse din lista curentă.",
                        confirmLabel = "Golește lista",
                        onConfirm = { cleared++ },
                        onDismiss = {},
                    )
                }
            }
        }

        shoot("list-clear-sheet")
        rule.onNodeWithText("Golește lista").performClick()

        assertEquals(1, cleared)
    }

    @Test fun pdfsReady_canShareAgain_orFinish() {
        var shares = 0
        var dones = 0
        rule.setContent {
            AppTheme(darkTheme = false) { PdfsReadyContent(2, { shares++ }, { dones++ }) }
        }

        rule.onNodeWithText("PDF-urile sunt gata").assertExists()
        shoot("list-pdfs-ready")
        rule.onNodeWithText("Distribuie").performClick()
        rule.onNodeWithText("Gata").performClick()

        assertEquals(1, shares)
        assertEquals(1, dones)
    }
}
