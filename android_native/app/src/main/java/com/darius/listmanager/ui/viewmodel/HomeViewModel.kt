package com.darius.listmanager.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.data.repository.SessionRepository
import com.darius.listmanager.data.repository.SpeechRepository
import com.darius.listmanager.data.repository.UnknownRepository
import com.darius.listmanager.data.speech.AndroidSpeechProvider
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.data.usecase.AddProductUseCase
import com.darius.listmanager.data.usecase.ResolveResult
import com.darius.listmanager.data.usecase.ResolveSpokenProductUseCase
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
    val unknownProductCount: Int = 0
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val speechRepository: SpeechRepository = AndroidSpeechProvider(application)
    private val database = AppDatabase.getInstance(application)

    // Repositories
    private val productRepository = ProductRepository(database.productDao())
    private val sessionRepository = SessionRepository(database.sessionDao(), database.sessionItemDao())
    private val unknownRepository = UnknownRepository(database.unknownDao())

    // Use cases
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
                android.util.Log.d("HomeViewModel", "Unknown products count updated: ${unknownProducts.size}")
                _uiState.value = _uiState.value.copy(
                    unknownProductCount = unknownProducts.size
                )
            }
        }
    }

    fun startListening() {
        speechRepository.startListening()
        _uiState.value = _uiState.value.copy(
            suggestions = emptyList(),
            message = null
        )
    }

    fun stopListening() {
        speechRepository.stopListening()
    }

    private fun processSpokenText(spokenText: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true)

            try {
                Log.d("HomeViewModel", "Processing spoken text: '$spokenText'")

                when (val result = resolveSpokenProductUseCase.execute(spokenText)) {
                    is ResolveResult.AutoAdd -> {
                        Log.d("HomeViewModel", "AutoAdd: ${result.product.name} (score: ${result.score})")

                        // High confidence - auto-add to session
                        val session = sessionRepository.getOrCreateActiveSession()
                        addProductUseCase.execute(session.id, result.product.id, 1)

                        _uiState.value = _uiState.value.copy(
                            message = "Added: ${result.product.name} (confidence: ${(result.score * 100).toInt()}%)",
                            suggestions = emptyList(),
                            isProcessing = false
                        )

                        // Reset speech state for next recording
                        resetSpeechState()
                    }
                    is ResolveResult.Suggestions -> {
                        Log.d("HomeViewModel", "Suggestions: ${result.products.size} products")
                        result.products.forEach {
                            Log.d("HomeViewModel", "  - ${it.product.name}: ${it.score}")
                        }

                        // Medium confidence - show suggestions
                        _uiState.value = _uiState.value.copy(
                            suggestions = result.products,
                            message = "Found ${result.products.size} suggestions. Tap one to add.",
                            isProcessing = false
                        )
                    }
                    is ResolveResult.Unknown -> {
                        Log.d("HomeViewModel", "Unknown: ${result.spokenText}")

                        // Low confidence - save as unknown
                        val unknownId = unknownRepository.insert(result.spokenText)
                        Log.d("HomeViewModel", "Saved to unknown list with ID: $unknownId")

                        _uiState.value = _uiState.value.copy(
                            message = "Couldn't recognize '${result.spokenText}'. Saved to Unknown list.",
                            suggestions = emptyList(),
                            isProcessing = false
                        )

                        // Reset speech state for next recording
                        resetSpeechState()
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error processing spoken text", e)
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

                _uiState.value = _uiState.value.copy(
                    message = "Added: $productName",
                    suggestions = emptyList()
                )

                // Reset speech state to allow new recording
                resetSpeechState()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    message = "Error adding product: ${e.message}"
                )
            }
        }
    }

    fun clearSuggestions() {
        _uiState.value = _uiState.value.copy(
            suggestions = emptyList(),
            message = null
        )
        // Reset speech state to allow new recording
        resetSpeechState()
    }

    private fun resetSpeechState() {
        speechRepository.stopListening()
    }

    override fun onCleared() {
        super.onCleared()
        speechRepository.release()
    }
}