package com.darius.listmanager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.sync.SaveState
import com.darius.listmanager.ui.components.PendingOperationsDialogContent
import com.darius.listmanager.ui.components.SaveStateChipContent
import com.darius.listmanager.ui.screens.HomeContent
import com.darius.listmanager.ui.screens.RecordAction
import com.darius.listmanager.ui.screens.recordActionFor
import com.darius.listmanager.ui.viewmodel.HomeUiState
import com.darius.listmanager.ui.viewmodel.ReviewKind
import com.darius.listmanager.ui.viewmodel.ReviewLine
import com.darius.listmanager.util.RankedProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class HomeAndSaveStateUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var starts = 0
    private var stops = 0
    private var sessionOpens = 0

    @Composable
    private fun Home(state: HomeUiState) {
        MaterialTheme {
            HomeContent(
                uiState = state,
                onStart = { starts++ },
                onStop = { stops++ },
                onClearSuggestions = {},
                onPickSuggestion = {},
                onOpenSession = { sessionOpens++ },
                onOpenReview = {},
                onOpenUnknown = {},
            )
        }
    }

    /** A visual record for review, when the platform can render one. Never fails a test. */
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

    @Test fun idle_theOnlyPrimaryActionIsRecord() {
        rule.setContent { Home(HomeUiState()) }

        rule.onNodeWithContentDescription("Începe înregistrarea").assertIsEnabled().performClick()
        rule.onNodeWithText("Apasă și spune produsele").assertExists()
        assertEquals(1, starts)
        shoot("home-idle")
    }

    @Test fun listening_turnsTheSameButtonIntoStop() {
        rule.setContent { Home(HomeUiState(speechState = SpeechState.Listening)) }

        rule.onNodeWithContentDescription("Oprește înregistrarea").performClick()
        assertEquals(1, stops)
        assertEquals(0, starts)
        shoot("home-listening")
    }

    @Test fun midPhrase_stopStillWorks() {
        rule.setContent { Home(HomeUiState(speechState = SpeechState.Partial("două lapte"))) }

        rule.onNodeWithText("Am auzit: două lapte").assertExists()
        rule.onNodeWithContentDescription("Oprește înregistrarea").performClick()
        assertEquals(1, stops)
    }

    @Test fun processing_disablesTheButton() {
        rule.mainClock.autoAdvance = false // The spinner never settles.
        rule.setContent { Home(HomeUiState(speechState = SpeechState.Final("lapte"))) }
        rule.mainClock.advanceTimeByFrame()

        rule.onNodeWithContentDescription("Se procesează").assertIsNotEnabled()
    }

    @Test fun error_offersRecordAgain_withTheReason() {
        rule.setContent { Home(HomeUiState(speechState = SpeechState.Error("Fără microfon"))) }

        rule.onNodeWithText("Eroare: Fără microfon").assertExists()
        rule.onNodeWithContentDescription("Începe înregistrarea").performClick()
        assertEquals(1, starts)
    }

    @Test fun secondaryActions_stayReachable_evenWithManySuggestions() {
        val suggestions = (1L..8L).map { id ->
            RankedProduct(ProductEntity(id, "Produs $id", 1), 80.0)
        }
        rule.setContent {
            Home(HomeUiState(suggestions = suggestions, reviewCount = 2, unknownProductCount = 1))
        }

        rule.onNodeWithText("Mergi spre sesiunea curentă").performClick()
        assertEquals(1, sessionOpens)
        rule.onNodeWithText("De verificat").assertExists()
        rule.onNodeWithText("Necunoscute").assertExists()
        shoot("home-suggestions")
    }

    @Test fun recordAction_coversEveryState() {
        assertEquals(RecordAction.START, recordActionFor(SpeechState.Idle, false))
        assertEquals(RecordAction.STOP, recordActionFor(SpeechState.Listening, false))
        // Continuous listening keeps processing earlier phrases while still recording.
        assertEquals(RecordAction.STOP, recordActionFor(SpeechState.Listening, true))
        assertEquals(RecordAction.STOP, recordActionFor(SpeechState.Partial("x"), false))
        assertEquals(RecordAction.BUSY, recordActionFor(SpeechState.Final("x"), false))
        assertEquals(RecordAction.BUSY, recordActionFor(SpeechState.Idle, true))
        assertEquals(RecordAction.START, recordActionFor(SpeechState.Error("x"), false))
    }

    @Test fun chip_answersIsItSaved_forEveryState_andTapsThrough() {
        var taps = 0
        val state = mutableStateOf<SaveState>(SaveState.Saved)
        rule.setContent {
            MaterialTheme { SaveStateChipContent(state.value, onClick = { taps++ }) }
        }

        val expectations = listOf(
            SaveState.Saved to "Salvat",
            SaveState.SavedOffline to "Salvat, offline",
            SaveState.Saving(2) to "Se salvează…",
            SaveState.StoredOnPhone(3) to "Pe telefon (3)",
            SaveState.NeedsAttention(1) to "De verificat (1)",
        )
        for ((saveState, label) in expectations) {
            state.value = saveState
            rule.onNodeWithText(label).assertExists()
        }

        rule.onNodeWithContentDescription("1 modificări au nevoie de decizia ta. Apasă pentru a le vedea.")
            .performClick()
        assertEquals(1, taps)
    }

    private fun line(id: Long, kind: ReviewKind) =
        ReviewLine(id, "Lapte Zuzu: cantitate 7", kind, "UPDATE_SESSION_ITEM")

    @Composable
    private fun Dialog(
        lines: List<ReviewLine>,
        onKeep: (Long) -> Unit = {},
        onUse: (Long) -> Unit = {},
        onRetry: (Long) -> Unit = {},
    ) {
        MaterialTheme {
            PendingOperationsDialogContent(
                lines = lines,
                message = null,
                isResolving = false,
                onDismiss = {},
                onSync = {},
                onKeepMine = onKeep,
                onUseServer = onUse,
                onRetry = onRetry,
            )
        }
    }

    @Test fun conflict_offersBothChoices_forThatOperation() {
        val kept = mutableListOf<Long>()
        val used = mutableListOf<Long>()
        rule.setContent {
            Dialog(listOf(line(5, ReviewKind.CONFLICT)), onKeep = { kept += it }, onUse = { used += it })
        }

        rule.onNodeWithText("Ai de decis").assertExists()
        rule.onNodeWithText("Varianta mea").performClick()
        rule.onNodeWithText("Varianta lor").performClick()
        assertEquals(listOf(5L), kept)
        assertEquals(listOf(5L), used)
        shoot("dialog-conflict")
    }

    @Test fun rejectedOperation_offersRetryOrGiveUp() {
        val retried = mutableListOf<Long>()
        rule.setContent { Dialog(listOf(line(6, ReviewKind.HELD)), onRetry = { retried += it }) }

        rule.onNodeWithText("Reîncearcă").performClick()
        rule.onNodeWithText("Renunță").assertExists()
        assertEquals(listOf(6L), retried)
    }

    @Test fun queuedWork_reassures_andNeedsNoDecision() {
        rule.setContent { Dialog(listOf(line(7, ReviewKind.QUEUED))) }

        rule.onNodeWithText("Sunt salvate pe telefon și se trimit automat.").assertExists()
        rule.onNodeWithText("Trimite acum").assertExists()
        assertTrue(rule.onAllNodesWithTextCount("Varianta mea") == 0)
    }

    @Test fun emptyQueue_saysEverythingIsSaved() {
        rule.setContent { Dialog(emptyList()) }
        rule.onNodeWithText("Totul este salvat").assertExists()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
