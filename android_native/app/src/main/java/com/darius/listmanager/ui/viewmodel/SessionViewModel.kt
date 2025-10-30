package com.darius.listmanager.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.dao.SessionItemWithProduct
import com.darius.listmanager.data.repository.PdfRepository
import com.darius.listmanager.data.repository.SessionRepository
import com.darius.listmanager.data.usecase.GeneratePdfsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File


data class SessionUiState(
    val items: List<SessionItemWithProduct> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val isGeneratingPdfs: Boolean = false,
    val pdfGenerationProgress: String? = null,
    val generatedPdfs: Map<String, File>? = null
)

class SessionViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val sessionRepository = SessionRepository(database.sessionDao(), database.sessionItemDao())
    private val pdfRepository = PdfRepository(application)
    private val generatePdfsUseCase = GeneratePdfsUseCase(sessionRepository, pdfRepository)

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private var currentSessionId: Long? = null

    init {
        loadSession()
    }

    private fun loadSession() {
        viewModelScope.launch {
            try {
                val session = sessionRepository.getOrCreateActiveSession()
                currentSessionId = session.id
                sessionRepository.getSessionItemsFlow(session.id).collect { items ->
                    _uiState.value = _uiState.value.copy(
                        items = items,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.value = SessionUiState(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    fun updateQuantity(productId: Long, newQuantity: Int) {
        viewModelScope.launch {
            currentSessionId?.let { sessionId ->
                sessionRepository.setItemQuantity(sessionId, productId, newQuantity)
            }
        }
    }

    fun deleteItem(itemId: Long) {
        viewModelScope.launch {
            sessionRepository.deleteItem(itemId)
        }
    }

    fun clearSession() {
        viewModelScope.launch {
            currentSessionId?.let { sessionId ->
                sessionRepository.clearSession(sessionId)
            }
        }
    }

    fun generatePdfs() {
        viewModelScope.launch {
            currentSessionId?.let { sessionId ->
                try {
                    _uiState.value = _uiState.value.copy(isGeneratingPdfs = true)

                    Log.d("SessionViewModel", "Starting PDF generation for session $sessionId")

                    val pdfs = generatePdfsUseCase.execute(sessionId)

                    Log.d("SessionViewModel", "Generated ${pdfs.size} PDFs")
                    pdfs.forEach { (distributor, file) ->
                        Log.d("SessionViewModel", "  - $distributor: ${file.absolutePath}")
                    }

                    _uiState.value = _uiState.value.copy(
                        isGeneratingPdfs = false,
                        generatedPdfs = pdfs
                    )

                    // Clear session after successful PDF generation
                    sessionRepository.clearSession(sessionId)

                } catch (e: Exception) {
                    Log.e("SessionViewModel", "Error generating PDFs", e)
                    _uiState.value = _uiState.value.copy(
                        isGeneratingPdfs = false,
                        error = "Failed to generate PDFs: ${e.message}"
                    )
                }
            }
        }
    }

    fun createShareIntent(): Intent? {
        val pdfs = _uiState.value.generatedPdfs
        if (pdfs.isNullOrEmpty()) return null

        val context = getApplication<Application>()
        val uris = pdfs.values.map { file ->
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }

        return Intent().apply {
            if (uris.size == 1) {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uris.first())
                type = "application/pdf"
            } else {
                action = Intent.ACTION_SEND_MULTIPLE
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                type = "application/pdf"
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun clearGeneratedPdfs() {
        _uiState.value = _uiState.value.copy(generatedPdfs = null)
    }
}