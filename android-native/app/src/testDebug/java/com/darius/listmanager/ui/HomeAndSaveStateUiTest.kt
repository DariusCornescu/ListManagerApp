package com.darius.listmanager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
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
import com.darius.listmanager.ui.screens.StageKind
import com.darius.listmanager.ui.screens.SuggestionSheetContent
import com.darius.listmanager.ui.screens.homeStageFor
import com.darius.listmanager.ui.screens.recordActionFor
import com.darius.listmanager.ui.theme.AppTheme
import com.darius.listmanager.ui.viewmodel.HomeUiState
import com.darius.listmanager.ui.viewmodel.LastAdded
import com.darius.listmanager.ui.viewmodel.ReviewKind
import com.darius.listmanager.ui.viewmodel.ReviewLine
import com.darius.listmanager.ui.viewmodel.SuggestionPrompt
import com.darius.listmanager.util.RankedProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class HomeAndSaveStateUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var starts = 0
    private var stops = 0
    private var sessionOpens = 0
    private var resolveOpens = 0
    private var undos = 0
    private val modeChanges = mutableListOf<Boolean>()

    /** Endless animations never let the test clock settle, so they're off except where tested. */
    @Before fun turnAnimationsOff() = setAnimatorScale(0f)

    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }

    @Composable
    private fun Home(state: HomeUiState, dark: Boolean = false) {
        AppTheme(darkTheme = dark) {
            // The app's Scaffold paints this background, so snapshots need it too.
            Surface(color = MaterialTheme.colorScheme.background) {
                HomeContent(
                    uiState = state,
                    onStart = { starts++ },
                    onStop = { stops++ },
                    onUndoLastAdded = { undos++ },
                    onOpenSession = { sessionOpens++ },
                    onOpenResolve = { resolveOpens++ },
                    onContinuousChange = { modeChanges += it },
                )
            }
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

    @Test fun dictationMode_startsAsOnePhrase_andTurnsContinuous() {
        rule.setContent { Home(HomeUiState()) }

        rule.onNodeWithText("Dictare continuă").assertExists()
        rule.onNodeWithText("Se oprește după o frază").assertExists()
        rule.onNode(isToggleable()).assertIsOff().performClick()
        assertEquals(listOf(true), modeChanges)
    }

    @Test fun dictationMode_continuous_listensUntilStop() {
        rule.setContent { Home(HomeUiState(continuousDictation = true)) }

        rule.onNodeWithText("Ascultă până apeși Oprește").assertExists()
        rule.onNode(isToggleable()).assertIsOn().performClick()
        assertEquals(listOf(false), modeChanges)
        shoot("home-idle-continuous")
    }

    @Test fun dictationMode_darkTheme() {
        rule.setContent { Home(HomeUiState(), dark = true) }

        rule.onNodeWithText("Se oprește după o frază").assertExists()
        shoot("home-idle-dark")
    }

    @Test fun listening_turnsTheSameButtonIntoStop() {
        rule.setContent { Home(HomeUiState(speechState = SpeechState.Listening)) }

        rule.onNodeWithText("Te ascult…").assertExists()
        rule.onNodeWithContentDescription("Oprește înregistrarea").performClick()
        assertEquals(1, stops)
        assertEquals(0, starts)
        shoot("home-listening")
    }

    @Test fun listening_withAnimationsOn_stillShowsStop() {
        setAnimatorScale(1f)
        rule.mainClock.autoAdvance = false // The rings never settle.
        rule.setContent { Home(HomeUiState(speechState = SpeechState.Listening)) }
        rule.mainClock.advanceTimeBy(600)

        rule.onNodeWithContentDescription("Oprește înregistrarea").assertIsEnabled()
    }

    @Test fun midPhrase_stopStillWorks() {
        rule.setContent { Home(HomeUiState(speechState = SpeechState.Partial("două lapte"))) }

        rule.onNodeWithText("două lapte").assertExists()
        rule.onNodeWithContentDescription("Oprește înregistrarea").performClick()
        assertEquals(1, stops)
    }

    @Test fun earlierPhraseStillProcessing_saysItsSearching() {
        rule.setContent { Home(HomeUiState(speechState = SpeechState.Partial("zahăr"), isProcessing = true)) }

        rule.onNodeWithText("Caut în catalog…").assertExists()
    }

    @Test fun processing_disablesTheButton() {
        rule.mainClock.autoAdvance = false // The spinner never settles.
        rule.setContent { Home(HomeUiState(speechState = SpeechState.Final("lapte"))) }
        rule.mainClock.advanceTimeByFrame()

        rule.onNodeWithContentDescription("Se procesează").assertIsNotEnabled()
    }

    @Test fun error_offersRecordAgain_withTheReason() {
        rule.setContent { Home(HomeUiState(speechState = SpeechState.Error("Fără microfon"))) }

        rule.onNodeWithText("Nu am putut asculta").assertExists()
        rule.onNodeWithText("Fără microfon").assertExists()
        rule.onNodeWithContentDescription("Începe înregistrarea").performClick()
        assertEquals(1, starts)
    }

    @Test fun dock_showsCounts_andOpensBothLists() {
        rule.setContent { Home(HomeUiState(reviewCount = 2, unknownProductCount = 1, sessionItemCount = 6)) }

        rule.onNodeWithContentDescription("Lista curentă: 6 produse").performClick()
        rule.onNodeWithContentDescription("De rezolvat: 3").performClick()
        assertEquals(1, sessionOpens)
        assertEquals(1, resolveOpens)
        rule.onNodeWithText("6").assertExists()
        rule.onNodeWithText("3").assertExists()
        shoot("home-counts")
    }

    @Test fun dock_hidesEmptyCounts() {
        rule.setContent { Home(HomeUiState()) }

        rule.onNodeWithText("0").assertDoesNotExist()
    }

    @Test fun lastAdded_offersUndoWhileTheWindowIsOpen() {
        val last = LastAdded(1, 10, 20, "Pâine albă", 2)
        rule.setContent { Home(HomeUiState(lastAdded = last, canUndoLastAdded = true)) }

        rule.onNodeWithText("Pâine albă × 2").assertExists()
        shoot("home-last-added")
        rule.onNodeWithText("Anulează").performClick()
        assertEquals(1, undos)
    }

    @Test fun darkTheme_showsTheSameScreen() {
        val last = LastAdded(1, 10, 20, "Pâine albă", 2)
        rule.setContent {
            Home(
                HomeUiState(
                    speechState = SpeechState.Partial("două pâini albe"),
                    lastAdded = last,
                    canUndoLastAdded = true,
                    reviewCount = 2,
                    unknownProductCount = 1,
                    sessionItemCount = 6,
                ),
                dark = true,
            )
        }

        rule.onNodeWithText("două pâini albe").assertExists()
        shoot("home-dark")
    }

    @Test fun lastAdded_staysAfterTheWindow_withoutUndo() {
        val last = LastAdded(1, 10, 20, "Pâine albă", 2)
        rule.setContent { Home(HomeUiState(lastAdded = last, canUndoLastAdded = false)) }

        rule.onNodeWithText("Pâine albă × 2").assertExists()
        rule.onNodeWithText("Anulează").assertDoesNotExist()
    }

    @Test fun suggestionSheet_picksACandidate_orLeavesItForLater() {
        val candidates = listOf("Ciment alb 25 kg", "Ciment Portland 40 kg").mapIndexed { index, name ->
            RankedProduct(ProductEntity(index + 1L, name, 1), 80.0)
        }
        var picked: RankedProduct? = null
        var dismissed = 0
        rule.setContent {
            AppTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    SuggestionSheetContent(
                        prompt = SuggestionPrompt(1, "ciment", 10, 1, candidates),
                        onPick = { picked = it },
                        onDismiss = { dismissed++ },
                    )
                }
            }
        }

        rule.onNodeWithText("Ai vrut să zici?").assertExists()
        rule.onNodeWithText("Am auzit „ciment”.").assertExists()
        shoot("home-suggestion-sheet")
        rule.onNodeWithText("Ciment Portland 40 kg").performClick()
        assertEquals(candidates[1], picked)
        rule.onNodeWithText("Niciunul").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun stage_coversEverySpeechState() {
        assertEquals(StageKind.IDLE, homeStageFor(SpeechState.Idle, false).kind)
        assertEquals(StageKind.LISTENING, homeStageFor(SpeechState.Idle, true).kind)
        assertEquals(StageKind.LISTENING, homeStageFor(SpeechState.Listening, false).kind)
        assertEquals("x", homeStageFor(SpeechState.Partial("x"), false).text)
        assertTrue(homeStageFor(SpeechState.Final("x"), false).seeking)
        assertEquals(StageKind.ERROR, homeStageFor(SpeechState.Error("x"), false).kind)
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
            SaveState.NeedsAttention(1) to "De decis (1)",
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

    @Test fun unknownOutcome_doesNotOfferABlindRetry() {
        val used = mutableListOf<Long>()
        rule.setContent { Dialog(listOf(line(8, ReviewKind.OUTCOME_UNKNOWN)), onUse = { used += it }) }

        assertEquals(0, rule.onAllNodesWithTextCount("Reîncearcă"))
        rule.onNodeWithText("Arată lista de pe server").performClick()
        assertEquals(listOf(8L), used)
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
