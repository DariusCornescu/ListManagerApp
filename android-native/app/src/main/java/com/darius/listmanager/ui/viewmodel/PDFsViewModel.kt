package com.darius.listmanager.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.repository.PdfRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class PDFsUiState(
    val pdfs: List<File> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class PDFsViewModel(application: Application) : AndroidViewModel(application) {
    private val pdfRepository = PdfRepository(application)

    private val _uiState = MutableStateFlow(PDFsUiState())
    val uiState: StateFlow<PDFsUiState> = _uiState.asStateFlow()

    init {
        loadPdfs()
    }

    fun loadPdfs() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)

                val pdfs = pdfRepository.getAllPdfs()
                android.util.Log.d("PDFsViewModel", "Loaded ${pdfs.size} PDFs")
                pdfs.forEach { pdf ->
                    android.util.Log.d("PDFsViewModel", "  - ${pdf.name} (${pdf.length()} bytes)")
                }

                _uiState.value = PDFsUiState(
                    pdfs = pdfs,
                    isLoading = false
                )
            } catch (e: Exception) {
                android.util.Log.e("PDFsViewModel", "Error loading PDFs", e)
                _uiState.value = PDFsUiState(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }
}