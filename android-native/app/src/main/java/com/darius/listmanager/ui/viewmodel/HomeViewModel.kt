package com.darius.listmanager.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.embedding.EmbeddingBackfill
import com.darius.listmanager.data.embedding.EmbeddingModel
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.repository.*
import com.darius.listmanager.data.speech.AndroidSpeechProvider
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.data.usecase.AddProductUseCase
import com.darius.listmanager.data.usecase.ResolveResult
import com.darius.listmanager.data.usecase.ResolveSpokenProductUseCase
import com.darius.listmanager.data.usecase.UndoAddUseCase
import com.darius.listmanager.data.workspace.WorkspaceManager
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.util.RankedProduct
import com.darius.listmanager.util.SensitiveLog
import com.darius.listmanager.util.SpokenQuantity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The most recent add from Home. [token] tells two adds of the same product apart. */
data class LastAdded(
    val token: Long,
    val sessionId: Long,
    val productId: Long,
    val name: String,
    val quantity: Int,
) {
    companion object { const val UNDO_WINDOW_MILLIS = 6_000L }
}

/** A phrase that matched several products. It's already saved in "De rezolvat" as [reviewId]. */
data class SuggestionPrompt(
    val reviewId: Long,
    val spokenText: String,
    val sessionId: Long,
    val quantity: Int,
    val candidates: List<RankedProduct>,
)

data class HomeUiState(
    val speechState: SpeechState = SpeechState.Idle,
    val suggestionPrompt: SuggestionPrompt? = null,
    val message: String? = null,
    val isProcessing: Boolean = false,
    val unknownProductCount: Int = 0,
    val reviewCount: Int = 0,
    val sessionAddedCount: Int = 0,
    val sessionItemCount: Int = 0,
    val lastAdded: LastAdded? = null,
    val canUndoLastAdded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val speechRepository: SpeechRepository = AndroidSpeechProvider(application)
    private val database = AppDatabase.getInstance(application)
    private val pendingOperationRepository = PendingOperationRepository(database.pendingOperationDao())
    private val productRepository = ProductRepository( database.productDao(), pendingOperationRepository, application.applicationContext, RetrofitClient.api )
    private val sessionRepository = SessionRepository(
        database.sessionDao(), database.sessionItemDao(), pendingOps = pendingOperationRepository,
        transaction = com.darius.listmanager.data.repository.SessionTransaction.room(database),
    )
    private val unknownRepository = UnknownRepository(database.unknownDao())
    private val needsReviewRepository = NeedsReviewRepository(database.needsReviewDao())
    private val embeddingModel = EmbeddingModel.getInstance(application)
    private val productEmbeddingRepository = ProductEmbeddingRepository(database.productEmbeddingDao())
    private val resolveSpokenProductUseCase =
        ResolveSpokenProductUseCase(productRepository, embeddingModel, productEmbeddingRepository)
    private val embeddingBackfill =
        EmbeddingBackfill(database.productDao(), productEmbeddingRepository, embeddingModel)
    private val addProductUseCase = AddProductUseCase(sessionRepository)
    private val undoAddUseCase = UndoAddUseCase(sessionRepository)
    private val workspaceManager = WorkspaceManager.getInstance(application)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var nextAddToken = 0L
    private var undoWindow: Job? = null

    init {
        // Collect how many products the current list holds, for the badge on "Lista"
        viewModelScope.launch {
            workspaceManager.currentWorkspace
                .flatMapLatest { sessionRepository.getActiveSessionFlow(it.teamIdOrNull) }
                .flatMapLatest { session ->
                    if (session == null) flowOf(emptyList()) else sessionRepository.getSessionItemsFlow(session.id)
                }
                .collect { items ->
                    _uiState.value = _uiState.value.copy(sessionItemCount = items.size)
                }
        }

        // Collect speech state
        viewModelScope.launch {
            speechRepository.speechState.collect { state ->
                _uiState.value = _uiState.value.copy(speechState = state)

                // Process final speech result
                if (state is SpeechState.Final) {
                    processSpokenText(state.text, state.alternatives)
                }
            }
        }

        // Collect unknown products count
        viewModelScope.launch {
            unknownRepository.getAllFlow().collect { unknownProducts ->
                Log.d(TAG, "Unknown products count updated: ${unknownProducts.size}")
                _uiState.value = _uiState.value.copy(
                    unknownProductCount = unknownProducts.size
                )
            }
        }

        // Collect needs-review count
        viewModelScope.launch {
            needsReviewRepository.getAllFlow().collect { items ->
                _uiState.value = _uiState.value.copy(reviewCount = items.size)
            }
        }

        // Warm the embedding cache in the background (no-op if model unavailable)
        viewModelScope.launch(Dispatchers.IO) {
            embeddingBackfill.run()
        }
    }

    // ==================== Speech Recognition ====================

    fun startListening() {
        speechRepository.startListening()
        _uiState.value = _uiState.value.copy(
            message = null,
            sessionAddedCount = 0
        )
    }

    fun stopListening() { speechRepository.stopListening() }

    private fun processSpokenText(spokenText: String, alternatives: List<String> = emptyList()) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true)

            try {
                SensitiveLog.d(TAG, "Processing spoken result (+${alternatives.size} alternatives)") {
                    "Processing spoken text: '$spokenText' (+${alternatives.size} alternatives)"
                }

                // Segment the utterance so several products said in one breath
                // ("lalele pâine albă ouă") are each matched and added. Recognizer
                // N-best alternatives sharpen matching on single-product utterances.
                val results = resolveSpokenProductUseCase.resolveMultiple(spokenText, alternatives)

                val session = sessionRepository.getOrCreateActiveSession(
                    workspaceManager.currentWorkspace.value.teamIdOrNull
                )

                var added = 0
                var review = 0
                var unknown = 0
                for ((segment, result) in results) {
                    when (result) {
                        is ResolveResult.AutoAdd -> {
                            SensitiveLog.d(TAG, "AutoAdd accepted (score: ${result.score})") {
                                "AutoAdd: ${result.product.name} (score: ${result.score})"
                            }
                            val quantity = SpokenQuantity.from(segment)
                            addProductUseCase.execute(session.id, result.product.id, quantity)
                            showLastAdded(session.id, result.product.id, result.product.name, quantity)
                            added++
                        }
                        is ResolveResult.Suggestions -> {
                            SensitiveLog.d(TAG, "Ambiguous result queued for review") {
                                "Ambiguous -> needs review: '$segment'"
                            }
                            // Saved before asking, so closing the app mid-question loses nothing.
                            val reviewId = needsReviewRepository.insert(
                                spokenText = segment,
                                sessionId = session.id,
                                createdAt = System.currentTimeMillis()
                            )
                            // One question at a time. Anything else waits in "De rezolvat".
                            if (_uiState.value.suggestionPrompt == null && result.products.isNotEmpty()) {
                                _uiState.value = _uiState.value.copy(
                                    suggestionPrompt = SuggestionPrompt(
                                        reviewId = reviewId,
                                        spokenText = segment,
                                        sessionId = session.id,
                                        quantity = SpokenQuantity.from(segment),
                                        candidates = result.products.take(MAX_CANDIDATES),
                                    )
                                )
                            } else {
                                review++
                            }
                        }
                        is ResolveResult.Unknown -> {
                            SensitiveLog.d(TAG, "Unknown result stored") {
                                "Unknown: '${result.spokenText}'"
                            }
                            unknownRepository.insert(result.spokenText)
                            unknown++
                        }
                    }
                }

                val toResolve = review + unknown
                val nothingHappened = added == 0 && toResolve == 0 && results.none { it.second is ResolveResult.Suggestions }
                _uiState.value = _uiState.value.copy(
                    // An add already has its own card, so only the rest needs a message.
                    message = when {
                        nothingHappened -> "Nu am recunoscut niciun produs"
                        toResolve > 0 -> "De rezolvat: $toResolve"
                        else -> null
                    },
                    isProcessing = false,
                    sessionAddedCount = _uiState.value.sessionAddedCount + added
                )
                // Keep listening — do NOT reset speech state.
            } catch (e: Exception) {
                Log.e(TAG, "Error processing spoken text", e)
                _uiState.value = _uiState.value.copy(
                    message = "Nu am putut procesa ce ai spus. Încearcă din nou.",
                    isProcessing = false
                )
            }
        }
    }

    private fun showLastAdded(sessionId: Long, productId: Long, name: String, quantity: Int) {
        _uiState.value = _uiState.value.copy(
            lastAdded = LastAdded(nextAddToken++, sessionId, productId, name, quantity),
            canUndoLastAdded = true,
        )
        undoWindow?.cancel()
        undoWindow = viewModelScope.launch {
            delay(LastAdded.UNDO_WINDOW_MILLIS)
            _uiState.value = _uiState.value.copy(canUndoLastAdded = false)
        }
    }

    fun undoLastAdded() {
        val last = _uiState.value.lastAdded ?: return
        undoWindow?.cancel()
        _uiState.value = _uiState.value.copy(
            lastAdded = null,
            canUndoLastAdded = false,
            sessionAddedCount = (_uiState.value.sessionAddedCount - 1).coerceAtLeast(0),
        )
        viewModelScope.launch {
            try {
                undoAddUseCase.execute(last.sessionId, last.productId, last.quantity)
            } catch (e: Exception) {
                Log.e(TAG, "Error undoing an add", e)
                _uiState.value = _uiState.value.copy(message = "Nu am putut anula. Verifică lista.")
            }
        }
    }

    fun pickSuggestion(candidate: RankedProduct) {
        val prompt = _uiState.value.suggestionPrompt ?: return
        _uiState.value = _uiState.value.copy(suggestionPrompt = null)
        viewModelScope.launch {
            try {
                addProductUseCase.execute(prompt.sessionId, candidate.product.id, prompt.quantity)
                needsReviewRepository.deleteById(prompt.reviewId)
                showLastAdded(prompt.sessionId, candidate.product.id, candidate.product.name, prompt.quantity)
                _uiState.value = _uiState.value.copy(
                    sessionAddedCount = _uiState.value.sessionAddedCount + 1
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error adding the chosen product", e)
                _uiState.value = _uiState.value.copy(
                    message = "Nu am putut adăuga produsul. A rămas la De rezolvat."
                )
            }
        }
    }

    /** The phrase stays saved in "De rezolvat", so there's nothing to delete. */
    fun dismissSuggestion() {
        if (_uiState.value.suggestionPrompt == null) return
        _uiState.value = _uiState.value.copy(suggestionPrompt = null, message = "L-am pus la De rezolvat")
    }

    // ==================== Cleanup ====================

    override fun onCleared() {
        super.onCleared()
        speechRepository.release()
        Log.d(TAG, "HomeViewModel cleared - resources released")
    }

    companion object {
        private const val TAG = "HomeViewModel"
        private const val MAX_CANDIDATES = 4
    }
}
