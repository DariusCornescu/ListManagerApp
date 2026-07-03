package com.darius.listmanager.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.repository.*
import com.darius.listmanager.data.speech.AndroidSpeechProvider
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.data.usecase.AddProductUseCase
import com.darius.listmanager.data.usecase.ResolveResult
import com.darius.listmanager.data.usecase.ResolveSpokenProductUseCase
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.util.RankedProduct
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val speechState: SpeechState = SpeechState.Idle,
    val suggestions: List<RankedProduct> = emptyList(),
    val message: String? = null,
    val isProcessing: Boolean = false,
    val unknownProductCount: Int = 0,
    val reviewCount: Int = 0,
    val sessionAddedCount: Int = 0
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val speechRepository: SpeechRepository = AndroidSpeechProvider(application)
    private val database = AppDatabase.getInstance(application)
    private val pendingOperationRepository = PendingOperationRepository(database.pendingOperationDao())
    private val productRepository = ProductRepository( database.productDao(), pendingOperationRepository, application.applicationContext, RetrofitClient.api )
    private val sessionRepository = SessionRepository( database.sessionDao(), database.sessionItemDao() )
    private val unknownRepository = UnknownRepository(database.unknownDao())
    private val needsReviewRepository = NeedsReviewRepository(database.needsReviewDao())
    private val resolveSpokenProductUseCase = ResolveSpokenProductUseCase(productRepository)
    private val addProductUseCase = AddProductUseCase(sessionRepository)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // Collect speech state
        viewModelScope.launch {
            speechRepository.speechState.collect { state ->
                _uiState.value = _uiState.value.copy(speechState = state)

                // Process final speech result
                if (state is SpeechState.Final) {
                    processSpokenText(state.text)
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
    }

    // ==================== Speech Recognition ====================

    fun startListening() {
        speechRepository.startListening()
        _uiState.value = _uiState.value.copy(
            suggestions = emptyList(),
            message = null,
            sessionAddedCount = 0
        )
    }

    fun stopListening() { speechRepository.stopListening() }

    private fun processSpokenText(spokenText: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true)

            try {
                Log.d(TAG, "Processing spoken text: '$spokenText'")

                when (val result = resolveSpokenProductUseCase.execute(spokenText)) {
                    is ResolveResult.AutoAdd -> {
                        Log.d(TAG, "AutoAdd: ${result.product.name} (score: ${result.score})")
                        val session = sessionRepository.getOrCreateActiveSession()
                        addProductUseCase.execute(session.id, result.product.id, 1)
                        _uiState.value = _uiState.value.copy(
                            message = "Adăugat: ${result.product.name}",
                            suggestions = emptyList(),
                            isProcessing = false,
                            sessionAddedCount = _uiState.value.sessionAddedCount + 1
                        )
                        // Keep listening — do NOT reset speech state.
                    }
                    is ResolveResult.Suggestions -> {
                        Log.d(TAG, "Ambiguous -> needs review: '$spokenText'")
                        val session = sessionRepository.getOrCreateActiveSession()
                        needsReviewRepository.insert(
                            spokenText = spokenText,
                            sessionId = session.id,
                            createdAt = System.currentTimeMillis()
                        )
                        _uiState.value = _uiState.value.copy(
                            message = "De verificat: '$spokenText'",
                            suggestions = emptyList(),
                            isProcessing = false
                        )
                        // Keep listening.
                    }
                    is ResolveResult.Unknown -> {
                        Log.d(TAG, "Unknown: ${result.spokenText}")
                        unknownRepository.insert(result.spokenText)
                        _uiState.value = _uiState.value.copy(
                            message = "Nerecunoscut: '${result.spokenText}'. Salvat.",
                            suggestions = emptyList(),
                            isProcessing = false
                        )
                        // Keep listening.
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing spoken text", e)
                _uiState.value = _uiState.value.copy(
                    message = "Error: ${e.message}",
                    suggestions = emptyList(),
                    isProcessing = false
                )
            }
        }
    }

    fun addSuggestedProduct(productId: Long, productName: String) {
        viewModelScope.launch {
            try {
                val session = sessionRepository.getOrCreateActiveSession()
                addProductUseCase.execute(session.id, productId, 1)

                _uiState.value = _uiState.value.copy( message = "Added: $productName", suggestions = emptyList() )
                resetSpeechState()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    message = "Error adding product: ${e.message}"
                )
            }
        }
    }

    fun clearSuggestions() {
        _uiState.value = _uiState.value.copy( suggestions = emptyList(), message = null )
        resetSpeechState()
    }

    private fun resetSpeechState() { speechRepository.stopListening() }

    // ==================== Cleanup ====================

    override fun onCleared() {
        super.onCleared()
        speechRepository.release()
        Log.d(TAG, "HomeViewModel cleared - resources released")
    }

    companion object { private const val TAG = "HomeViewModel" }
}