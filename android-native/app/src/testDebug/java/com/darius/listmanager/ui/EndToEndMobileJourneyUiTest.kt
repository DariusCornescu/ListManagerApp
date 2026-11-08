package com.darius.listmanager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.darius.listmanager.data.local.dao.SessionItemWithProduct
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.model.OperationType
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.sync.SaveState
import com.darius.listmanager.sync.SyncService
import com.darius.listmanager.sync.isHeldForReview
import com.darius.listmanager.sync.isVersionConflict
import com.darius.listmanager.sync.saveStateOf
import com.darius.listmanager.ui.components.AppIconButton
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.ConfirmSheetContent
import com.darius.listmanager.ui.components.PendingOperationsDialogContent
import com.darius.listmanager.ui.components.SaveStateChipContent
import com.darius.listmanager.ui.screens.HomeContent
import com.darius.listmanager.ui.screens.LoginContent
import com.darius.listmanager.ui.screens.PdfsReadyContent
import com.darius.listmanager.ui.screens.ResolveContent
import com.darius.listmanager.ui.screens.SessionContent
import com.darius.listmanager.ui.screens.SessionFooter
import com.darius.listmanager.ui.screens.SuggestionSheetContent
import com.darius.listmanager.ui.screens.sessionSubtitle
import com.darius.listmanager.ui.theme.AppTheme
import com.darius.listmanager.ui.viewmodel.HomeUiState
import com.darius.listmanager.ui.viewmodel.LastAdded
import com.darius.listmanager.ui.viewmodel.ReviewItem
import com.darius.listmanager.ui.viewmodel.ReviewKind
import com.darius.listmanager.ui.viewmodel.ReviewLine
import com.darius.listmanager.ui.viewmodel.SessionUiState
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

/**
 * Walks whole journeys across screens, where the other UI tests check one screen at a time.
 *
 * A small host below plays the part of the navigation graph and the view models: it keeps the
 * journey's state and renders the same stateless `*Content` composables the app shows. What this
 * proves is that each screen's output leads into the next one; the view models and the real
 * NavGraph are not exercised here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EndToEndMobileJourneyUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val milk = ProductEntity(101, "Lapte Zuzu 1L", 1)
    private val whiteCement = ProductEntity(201, "Ciment alb 25 kg", 2)
    private val portland = ProductEntity(202, "Ciment Portland 40 kg", 2)
    private val bread = ProductEntity(102, "Pâine albă", 1)
    private val flour = ProductEntity(203, "Făină albă 1 kg", 2)
    private val distributors = mapOf(1L to "Distribuitor A", 2L to "Distribuitor B")

    private lateinit var app: JourneyState

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

    private fun type(index: Int, text: String) {
        rule.onAllNodes(hasSetTextAction())[index].performTextClearance()
        rule.onAllNodes(hasSetTextAction())[index].performTextInput(text)
    }

    private fun countOf(text: String): Int = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    // ---------------------------------------------------------------------------------------
    // The host: screen switching plus the state changes the view models would make.
    // ---------------------------------------------------------------------------------------

    private enum class Screen { LOGIN, HOME, RESOLVE, SESSION, PDFS_READY }

    private inner class JourneyState(start: Screen, items: List<SessionItemWithProduct> = emptyList()) {
        var screen by mutableStateOf(start)
        var loginError by mutableStateOf<String?>(null)
        var home by mutableStateOf(HomeUiState(sessionItemCount = items.size))
        var reviews by mutableStateOf<List<ReviewItem>>(emptyList())
        var items by mutableStateOf(items)
        var operations by mutableStateOf<List<PendingOperationEntity>>(emptyList())
        var online by mutableStateOf(true)
        var showOperations by mutableStateOf(false)
        var showClearSheet by mutableStateOf(false)
        var generating by mutableStateOf(false)
        var pdfCount by mutableStateOf(0)
        var shares = 0
        var completedSessions = 0
        private var nextItemId = 1L + items.maxOfOrNull { it.id }.let { it ?: 0L }

        val saveState: SaveState get() = saveStateOf(operations, online)

        /** The same mapping SaveStateViewModel makes, built on the app's own predicates. */
        val lines: List<ReviewLine>
            get() = operations.map { op ->
                val kind = when {
                    op.isVersionConflict -> ReviewKind.CONFLICT
                    op.isHeldForReview -> ReviewKind.HELD
                    else -> ReviewKind.QUEUED
                }
                ReviewLine(op.id, "Operația ${op.id}", kind, op.operationType)
            }

        val session: SessionUiState get() = SessionUiState(items = items, isLoading = false)

        fun login(user: String, password: String) {
            if (user == "ana" && password == "secret1") {
                loginError = null
                screen = Screen.HOME
            } else {
                loginError = "Utilizator sau parolă incorectă"
            }
        }

        fun add(product: ProductEntity, quantity: Int) {
            val existing = items.firstOrNull { it.productId == product.id }
            items = if (existing != null) {
                items.map { if (it.id == existing.id) it.copy(quantity = it.quantity + quantity) else it }
            } else {
                items + SessionItemWithProduct(
                    nextItemId++, SESSION_ID, product.id, product.name,
                    product.distributorId, distributors.getValue(product.distributorId), quantity,
                )
            }
            refreshHomeCounts()
        }

        fun setQuantity(item: SessionItemWithProduct, quantity: Int) {
            items = items.map { if (it.id == item.id) it.copy(quantity = quantity) else it }
        }

        fun delete(item: SessionItemWithProduct) {
            items = items.filterNot { it.id == item.id }
            refreshHomeCounts()
        }

        fun refreshHomeCounts() {
            home = home.copy(sessionItemCount = items.size, reviewCount = reviews.size)
        }

        fun finishSession() {
            completedSessions++
            items = emptyList()
            pdfCount = 0
            refreshHomeCounts()
            screen = Screen.HOME
        }
    }

    @Composable
    private fun JourneyApp(state: JourneyState) {
        AppTheme(darkTheme = false) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                when (state.screen) {
                    Screen.LOGIN -> LoginContent(
                        isLoading = false,
                        error = state.loginError,
                        registrationSuccess = false,
                        onLogin = state::login,
                        onRegister = { _, _, _ -> },
                        onClearError = { state.loginError = null },
                        onContinueOffline = { state.screen = Screen.HOME },
                    )
                    Screen.HOME -> HomeHost(state)
                    Screen.RESOLVE -> Column {
                        AppTopBar("De rezolvat", onBack = { state.screen = Screen.HOME })
                        ResolveContent(
                            reviewItems = state.reviews,
                            unknownItems = emptyList(),
                            isLoading = false,
                            hasError = false,
                            canAddToCatalog = false,
                            onPick = { review, candidate ->
                                state.reviews = state.reviews - review
                                state.add(candidate.product, 1)
                            },
                            onNoneOfThese = { state.reviews = state.reviews - it },
                            onAdd = {},
                            onDelete = {},
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Screen.SESSION -> SessionHost(state)
                    Screen.PDFS_READY -> PdfsReadyContent(
                        count = state.pdfCount,
                        onShare = { state.shares++ },
                        onDone = state::finishSession,
                    )
                }
            }
        }
    }

    @Composable
    private fun HomeHost(state: JourneyState) {
        Box(Modifier.fillMaxSize()) {
            HomeContent(
                uiState = state.home,
                onStart = { state.home = state.home.copy(speechState = SpeechState.Listening) },
                onStop = {
                    // What HomeViewModel ends up with after "două lapte, ciment": the clear match is
                    // added straight away, the ambiguous one waits in "De rezolvat" and is offered now.
                    state.add(milk, 2)
                    val ciment = ReviewItem(
                        id = 7,
                        spokenText = "ciment",
                        candidates = listOf(RankedProduct(whiteCement, 0.74), RankedProduct(portland, 0.71)),
                    )
                    state.reviews = listOf(ciment)
                    state.home = state.home.copy(
                        speechState = SpeechState.Idle,
                        lastAdded = LastAdded(1, SESSION_ID, milk.id, milk.name, 2),
                        canUndoLastAdded = true,
                        sessionAddedCount = 1,
                        suggestionPrompt = SuggestionPrompt(ciment.id, ciment.spokenText, SESSION_ID, 1, ciment.candidates),
                    )
                    state.refreshHomeCounts()
                },
                onUndoLastAdded = {},
                onOpenSession = { state.screen = Screen.SESSION },
                onOpenResolve = { state.screen = Screen.RESOLVE },
            )
            state.home.suggestionPrompt?.let { prompt ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                ) {
                    SuggestionSheetContent(
                        prompt = prompt,
                        onPick = { candidate ->
                            state.reviews = state.reviews.filterNot { it.id == prompt.reviewId }
                            state.add(candidate.product, prompt.quantity)
                            state.home = state.home.copy(suggestionPrompt = null)
                        },
                        // "Niciunul" leaves it in "De rezolvat" for later.
                        onDismiss = { state.home = state.home.copy(suggestionPrompt = null) },
                    )
                }
            }
        }
    }

    @Composable
    private fun SessionHost(state: JourneyState) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    "Lista curentă",
                    subtitle = sessionSubtitle(state.session),
                    onBack = { state.screen = Screen.HOME },
                    actions = {
                        SaveStateChipContent(state.saveState, onClick = { state.showOperations = true })
                        if (state.items.isNotEmpty()) {
                            AppIconButton(Icons.Rounded.Delete, "Golește lista", { state.showClearSheet = true })
                        }
                    },
                )
                SessionContent(
                    state = state.session,
                    onQuantityChange = state::setQuantity,
                    onDelete = state::delete,
                    onRetry = {},
                    onRecord = { state.screen = Screen.HOME },
                    modifier = Modifier.weight(1f),
                )
                if (state.items.isNotEmpty()) {
                    SessionFooter(state.generating) {
                        state.pdfCount = state.items.map { it.distributorId }.distinct().size
                        state.screen = Screen.PDFS_READY
                    }
                }
            }
            if (state.showOperations) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                ) {
                    PendingOperationsDialogContent(
                        lines = state.lines,
                        message = null,
                        isResolving = false,
                        onDismiss = { state.showOperations = false },
                        onSync = {},
                        // Keeping mine rebases onto the server version and sends it: nothing left.
                        onKeepMine = { id -> state.operations = state.operations.filterNot { it.id == id } },
                        onUseServer = { id -> state.operations = state.operations.filterNot { it.id == id } },
                        onRetry = { id -> state.operations = state.operations.filterNot { it.id == id } },
                    )
                }
            }
            if (state.showClearSheet) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                ) {
                    ConfirmSheetContent(
                        title = "Golești lista?",
                        message = "Toate produsele vor fi șterse din lista curentă.",
                        confirmLabel = "Golește lista",
                        onConfirm = {
                            state.items = emptyList()
                            state.showClearSheet = false
                        },
                        onDismiss = { state.showClearSheet = false },
                    )
                }
            }
        }
    }

    private fun operation(id: Long, type: OperationType, held: Boolean = false, error: String? = null) =
        PendingOperationEntity(
            id = id,
            operationType = type.name,
            resourceType = "SESSION_ITEM",
            resourceId = id,
            operationData = "{}",
            status = if (held) "FAILED" else "PENDING",
            lastError = error,
            scheduledFor = if (held) Long.MAX_VALUE else 0L,
        )

    // ---------------------------------------------------------------------------------------
    // Journeys
    // ---------------------------------------------------------------------------------------

    @Test fun signIn_dictate_resolve_thenSeeTheListGroupedByDistributor() {
        app = JourneyState(Screen.LOGIN)
        rule.setContent { JourneyApp(app) }

        // A wrong password keeps you on the sign-in screen and says why.
        type(0, "ana")
        type(1, "gresit")
        rule.onNodeWithText("Intră în cont").performClick()
        rule.onNodeWithText("Utilizator sau parolă incorectă").assertExists()
        type(1, "secret1")
        rule.onNodeWithText("Intră în cont").performClick()

        // Home: one button starts listening and the same button stops it.
        rule.onNodeWithContentDescription("Începe înregistrarea").performClick()
        rule.onNodeWithContentDescription("Oprește înregistrarea").performClick()

        // The clear match is already on the list; the unclear one is asked about right away.
        rule.onNodeWithText("Lapte Zuzu 1L × 2").assertExists()
        rule.onNodeWithText("Ai vrut să zici?").assertExists()
        rule.onNodeWithText("Am auzit „ciment”.").assertExists()
        shoot("journey-home-suggestion")

        // "Niciunul" puts it off; the dock then shows one thing to resolve and one product.
        rule.onNodeWithText("Niciunul").performClick()
        rule.onNodeWithText("Ai vrut să zici?").assertDoesNotExist()
        rule.onNodeWithContentDescription("De rezolvat: 1").performClick()

        // Resolving it later adds it to the list and empties "De rezolvat".
        rule.onNodeWithText("De verificat").assertExists()
        rule.onNodeWithText("Ciment Portland 40 kg").performClick()
        rule.onNodeWithText("Nimic de rezolvat").assertExists()
        rule.onNodeWithContentDescription("Înapoi").performClick()

        rule.onNodeWithContentDescription("Lista curentă: 2 produse").performClick()
        rule.onNodeWithText("Personal · 2 produse").assertExists()
        rule.onNodeWithText("Distribuitor A").assertExists()
        rule.onNodeWithText("Distribuitor B").assertExists()
        rule.onNodeWithText("Lapte Zuzu 1L").assertExists()
        rule.onNodeWithText("Ciment Portland 40 kg").assertExists()
        shoot("journey-list")

        assertEquals(listOf(2, 1), app.items.map { it.quantity })
    }

    @Test fun offlineChanges_conflictDecision_thenPdfsAndANewList() {
        app = JourneyState(
            Screen.SESSION,
            items = listOf(
                SessionItemWithProduct(1, SESSION_ID, milk.id, milk.name, 1, "Distribuitor A", 2),
                SessionItemWithProduct(2, SESSION_ID, flour.id, flour.name, 2, "Distribuitor B", 3),
            ),
        )
        app.online = false
        app.operations = listOf(
            operation(41, OperationType.ADD_SESSION_ITEM),
            operation(42, OperationType.UPDATE_SESSION_ITEM),
        )
        rule.setContent { JourneyApp(app) }

        // Offline: both changes are kept on the phone.
        rule.onNodeWithText("Pe telefon (2)").assertExists()

        // Back online: the add goes through, the quantity change hits a 409 and is held.
        app.online = true
        app.operations = listOf(
            operation(42, OperationType.UPDATE_SESSION_ITEM, held = true, error = SyncService.VERSION_CONFLICT_MESSAGE),
        )
        rule.onNodeWithText("De decis (1)").assertExists().performClick()

        rule.onNodeWithText("Ai de decis").assertExists()
        rule.onNodeWithText("Varianta lor").assertExists()
        shoot("journey-conflict")
        rule.onNodeWithText("Varianta mea").performClick()
        rule.onNodeWithText("Totul este salvat").assertExists()
        rule.onNodeWithText("Închide").performClick()
        rule.onNodeWithText("Salvat").assertExists()

        // One PDF per distributor, shared, then the list starts over.
        rule.onNodeWithText("Generează PDF-uri").performClick()
        rule.onNodeWithText("PDF-urile sunt gata").assertExists()
        rule.onNodeWithText(
            "Câte un PDF pentru fiecare dintre cei 2 distribuitori. Lista poate începe din nou.",
        ).assertExists()
        rule.onNodeWithText("Distribuie").performClick()
        rule.onNodeWithText("Gata").performClick()

        assertEquals(1, app.shares)
        assertEquals(1, app.completedSessions)
        rule.onNodeWithContentDescription("Începe înregistrarea").assertExists()
        assertEquals(0, countOf("Lista curentă: 2 produse"))
    }

    @Test fun quantitiesAcrossDistributors_minusBecomesDelete_andClearingAsksFirst() {
        app = JourneyState(
            Screen.SESSION,
            items = listOf(
                SessionItemWithProduct(1, SESSION_ID, bread.id, bread.name, 1, "Distribuitor A", 1),
                SessionItemWithProduct(2, SESSION_ID, flour.id, flour.name, 2, "Distribuitor B", 3),
                SessionItemWithProduct(3, SESSION_ID, portland.id, portland.name, 2, "Distribuitor B", 1),
            ),
        )
        rule.setContent { JourneyApp(app) }
        rule.onNodeWithText("Personal · 3 produse").assertExists()

        // At 1 there is no minus, only delete. Going up brings the minus back...
        rule.onNodeWithContentDescription("Scade cantitatea pentru Pâine albă").assertDoesNotExist()
        rule.onNodeWithContentDescription("Crește cantitatea pentru Pâine albă").performClick()
        rule.onNodeWithContentDescription("Șterge Pâine albă").assertDoesNotExist()
        // ...and coming back down to 1 turns it into delete again.
        rule.onNodeWithContentDescription("Scade cantitatea pentru Pâine albă").performClick()
        rule.onNodeWithContentDescription("Scade cantitatea pentru Pâine albă").assertDoesNotExist()
        rule.onNodeWithContentDescription("Șterge Pâine albă").performClick()

        // Its distributor had nothing else, so its group goes too; the other keeps both rows.
        rule.onNodeWithText("Pâine albă").assertDoesNotExist()
        rule.onNodeWithText("Distribuitor A").assertDoesNotExist()
        rule.onNodeWithText("Distribuitor B").assertExists()
        rule.onNodeWithText("Personal · 2 produse").assertExists()

        // Lowering one product in a shared group leaves its neighbour alone.
        rule.onNodeWithContentDescription("Scade cantitatea pentru Făină albă 1 kg").performClick()
        assertEquals(listOf(2, 1), app.items.map { it.quantity })

        // Clearing asks first; cancelling keeps everything.
        rule.onNodeWithContentDescription("Golește lista").performClick()
        rule.onNodeWithText("Golești lista?").assertExists()
        shoot("journey-clear-sheet")
        rule.onNodeWithText("Anulează").performClick()
        rule.onNodeWithText("Golești lista?").assertDoesNotExist()
        rule.onNodeWithText("Făină albă 1 kg").assertExists()

        rule.onNodeWithContentDescription("Golește lista").performClick()
        rule.onNodeWithText("Golește lista").performClick()

        rule.onNodeWithText("Lista e goală").assertExists()
        rule.onNodeWithContentDescription("Golește lista").assertDoesNotExist()
        rule.onNodeWithText("Generează PDF-uri").assertDoesNotExist()
        assertTrue(app.items.isEmpty())
    }

    private companion object {
        const val SESSION_ID = 10L
    }
}
