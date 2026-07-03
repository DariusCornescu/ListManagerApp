package com.darius.listmanager.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.repository.*
import com.darius.listmanager.data.usecase.AddProductUseCase
import com.darius.listmanager.data.usecase.ResolveResult
import com.darius.listmanager.data.usecase.ResolveSpokenProductUseCase
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.util.RankedProduct
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReviewItem(
    val id: Long,
    val spokenText: String,
    val candidates: List<RankedProduct>
)

data class NeedsReviewUiState(
    val items: List<ReviewItem> = emptyList(),
    val isLoading: Boolean = true,
    val message: String? = null
)

class NeedsReviewViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val pendingOperationRepo = PendingOperationRepository(database.pendingOperationDao())
    private val productRepository = ProductRepository(
        database.productDao(), pendingOperationRepo, application.applicationContext, RetrofitClient.api
    )
    private val sessionRepository = SessionRepository(database.sessionDao(), database.sessionItemDao())
    private val unknownRepository = UnknownRepository(database.unknownDao())
    private val needsReviewRepository = NeedsReviewRepository(database.needsReviewDao())
    private val resolveSpokenProductUseCase = ResolveSpokenProductUseCase(productRepository)
    private val addProductUseCase = AddProductUseCase(sessionRepository)

    private val _uiState = MutableStateFlow(NeedsReviewUiState())
    val uiState: StateFlow<NeedsReviewUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            needsReviewRepository.getAllFlow().collect { rows ->
                val items = rows.map { row ->
                    val candidates = when (val r = resolveSpokenProductUseCase.execute(row.spokenText)) {
                        is ResolveResult.Suggestions -> r.products
                        is ResolveResult.AutoAdd -> listOf(RankedProduct(r.product, r.score))
                        is ResolveResult.Unknown -> emptyList()
                    }
                    ReviewItem(id = row.id, spokenText = row.spokenText, candidates = candidates)
                }
                _uiState.value = _uiState.value.copy(items = items, isLoading = false)
            }
        }
    }

    fun addCandidate(reviewId: Long, productId: Long, productName: String) {
        viewModelScope.launch {
            try {
                val workspaceManager = com.darius.listmanager.data.workspace.WorkspaceManager.getInstance(getApplication())
                val session = sessionRepository.getOrCreateActiveSession(
                    workspaceManager.currentWorkspace.value.teamIdOrNull
                )
                addProductUseCase.execute(session.id, productId, 1)
                needsReviewRepository.deleteById(reviewId)
                _uiState.value = _uiState.value.copy(message = "Adăugat: $productName")
            } catch (e: Exception) {
                Log.e("NeedsReviewVM", "addCandidate failed", e)
                _uiState.value = _uiState.value.copy(message = "Eroare: ${e.message}")
            }
        }
    }

    fun sendToUnknown(reviewId: Long, spokenText: String) {
        viewModelScope.launch {
            unknownRepository.insert(spokenText)
            needsReviewRepository.deleteById(reviewId)
            _uiState.value = _uiState.value.copy(message = "Mutat la necunoscute: '$spokenText'")
        }
    }

    fun dismiss(reviewId: Long) {
        viewModelScope.launch { needsReviewRepository.deleteById(reviewId) }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
