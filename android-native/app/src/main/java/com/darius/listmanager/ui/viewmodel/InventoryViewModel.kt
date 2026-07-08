package com.darius.listmanager.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.InventoryItemEntity
import com.darius.listmanager.data.repository.InventoryPdfRow
import com.darius.listmanager.data.repository.InventoryRepository
import com.darius.listmanager.data.repository.PdfRepository
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.data.repository.SpeechRepository
import com.darius.listmanager.data.speech.AndroidSpeechProvider
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.util.InventoryLineParser
import com.darius.listmanager.util.InventoryMath
import com.darius.listmanager.util.ParsedInventoryLine
import com.darius.listmanager.util.ProductRanker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class InventoryUiState(
    val items: List<InventoryItemEntity> = emptyList(),
    val totalBani: Long = 0L,
    val speechState: SpeechState = SpeechState.Idle,
    val message: String? = null,
    val isGeneratingPdf: Boolean = false,
    val generatedPdf: File? = null,
    val partialText: String? = null,
    val draft: ParsedInventoryLine? = null,
    val continuous: Boolean = false
)

class InventoryViewModel(application: Application) : AndroidViewModel(application) {

    private val speechRepository: SpeechRepository = AndroidSpeechProvider(application)
    private val database = AppDatabase.getInstance(application)
    private val pendingOperationRepo = PendingOperationRepository(database.pendingOperationDao())
    private val productRepository = ProductRepository(
        database.productDao(), pendingOperationRepo, application.applicationContext, RetrofitClient.api
    )
    private val inventoryRepository = InventoryRepository(database.inventoryItemDao())
    private val pdfRepository = PdfRepository(application)

    private val _uiState = MutableStateFlow(InventoryUiState())
    val uiState: StateFlow<InventoryUiState> = _uiState.asStateFlow()

    /** How many segments of the CURRENT utterance were already inserted live. */
    private var committedSegments = 0

    init {
        viewModelScope.launch {
            inventoryRepository.getAllFlow().collect { items ->
                _uiState.value = _uiState.value.copy(
                    items = items,
                    totalBani = InventoryMath.totalBani(items.map { it.quantity to it.priceBani })
                )
            }
        }
        viewModelScope.launch {
            speechRepository.speechState.collect { state ->
                when (state) {
                    is SpeechState.Partial -> {
                        // Live "chatbot" feedback: raw words + a draft row parsed
                        // from the CURRENT (last) segment. Plain parse — partials
                        // fire several times a second; catalog scoring runs at
                        // insert time.
                        val segments = InventoryLineParser.segmentLines(state.text)
                        _uiState.value = _uiState.value.copy(
                            speechState = state,
                            partialText = state.text,
                            draft = segments.lastOrNull()?.let { InventoryLineParser.parse(it) }
                        )
                        // Fluent dictation: the moment a NEXT line starts, the
                        // previous one is complete — insert it live. No forced
                        // pauses; lines are cut from TEXT, not from silence.
                        if (_uiState.value.continuous && segments.size - 1 > committedSegments) {
                            val completed = segments.subList(committedSegments, segments.size - 1).toList()
                            committedSegments = segments.size - 1
                            viewModelScope.launch {
                                completed.forEach { insertSegment(it, quiet = true) }
                            }
                        }
                    }
                    is SpeechState.Final -> {
                        _uiState.value = _uiState.value.copy(
                            speechState = state, partialText = null, draft = null
                        )
                        if (!_uiState.value.continuous) {
                            // One line per tap: stop BEFORE the provider's policy
                            // can restart (collector runs synchronously on
                            // Main.immediate). In continuous mode the loop stays
                            // alive and restarts by itself.
                            speechRepository.stopListening()
                        }
                        val segments = InventoryLineParser.segmentLines(state.text)
                        val remaining = if (committedSegments < segments.size) {
                            segments.subList(committedSegments, segments.size).toList()
                        } else emptyList()
                        committedSegments = 0
                        addSegments(remaining)
                    }
                    else -> {
                        // Idle / Listening / Error: a new utterance begins (or
                        // the session ended) — reset the live-commit cursor.
                        committedSegments = 0
                        _uiState.value = _uiState.value.copy(
                            speechState = state, partialText = null, draft = null
                        )
                    }
                }
            }
        }
    }

    // ==================== Speech ====================

    fun startListening() {
        _uiState.value = _uiState.value.copy(message = null)
        speechRepository.startListening()
    }

    fun stopListening() { speechRepository.stopListening() }

    fun setContinuous(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(continuous = enabled)
    }

    // ==================== Rows ====================

    /** Insert every remaining segment of an utterance (Final). */
    private fun addSegments(segments: List<String>) {
        if (segments.isEmpty()) return
        viewModelScope.launch {
            if (segments.size == 1) {
                insertSegment(segments[0], quiet = false)
            } else {
                var added = 0
                segments.forEach { if (insertSegment(it, quiet = true)) added++ }
                _uiState.value = _uiState.value.copy(message = "Adăugate: $added rânduri")
            }
        }
    }

    /**
     * Parse one raw line segment (catalog-aware split: "cuie de 5" keeps its
     * number when the catalog recognizes the longer name) and insert it as a
     * row. Quiet mode skips per-line snackbar messages (fluent dictation).
     */
    private suspend fun insertSegment(segText: String, quiet: Boolean): Boolean {
        val local = try {
            productRepository.getAllLocal()
        } catch (e: Exception) {
            Log.e(TAG, "getAllLocal failed: ${e.message}", e)
            emptyList()
        }
        val scorer: ((String) -> Double)? = if (local.isEmpty()) null else { candidate ->
            ProductRanker.rank(candidate, local).firstOrNull()?.score ?: 0.0
        }
        val parsed = InventoryLineParser.parse(segText, scorer)
        if (parsed == null || parsed.nameText.isBlank()) {
            if (!quiet) {
                _uiState.value = _uiState.value.copy(message = "Nu am înțeles produsul — mai zi o dată")
            }
            return false
        }
        val (name, productId) = resolveName(parsed.nameText, local)
        inventoryRepository.insert(
            InventoryItemEntity(
                name = name,
                productId = productId,
                quantity = parsed.quantity,
                priceBani = parsed.priceBani,
                createdAt = System.currentTimeMillis()
            )
        )
        if (!quiet) {
            val missing = mutableListOf<String>()
            if (parsed.quantity == null) missing.add("cantitatea")
            if (parsed.priceBani == null) missing.add("prețul")
            _uiState.value = _uiState.value.copy(
                message = if (missing.isEmpty()) null
                          else "Lipsește ${missing.joinToString(" și ")} — completează în tabel"
            )
        }
        return true
    }

    /** Adopt the catalog name + id only on a high-confidence match; else keep free text. */
    private fun resolveName(
        nameText: String,
        local: List<com.darius.listmanager.data.local.entity.ProductEntity>
    ): Pair<String, Long?> {
        if (local.isEmpty()) return nameText to null
        val top = ProductRanker.rank(nameText, local).firstOrNull()
        return if (top != null && top.score >= MATCH_THRESHOLD) top.product.name to top.product.id
        else nameText to null
    }

    fun updateItem(item: InventoryItemEntity) {
        viewModelScope.launch { inventoryRepository.update(item) }
    }

    fun deleteItem(id: Long) {
        viewModelScope.launch { inventoryRepository.deleteById(id) }
    }

    fun clearList() {
        viewModelScope.launch {
            inventoryRepository.deleteAll()
            _uiState.value = _uiState.value.copy(message = "Listă nouă")
        }
    }

    // ==================== Export ====================

    fun exportPdf() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.items.isEmpty()) {
                _uiState.value = state.copy(message = "Lista e goală")
                return@launch
            }
            try {
                _uiState.value = state.copy(isGeneratingPdf = true)
                val rows = state.items.map { item ->
                    InventoryPdfRow(
                        name = item.name,
                        quantityText = InventoryMath.formatQuantity(item.quantity),
                        priceText = item.priceBani?.let { InventoryMath.formatLei(it) } ?: "",
                        valueText = InventoryMath.lineValueBani(item.quantity, item.priceBani)
                            ?.let { InventoryMath.formatLei(it) } ?: ""
                    )
                }
                val file = pdfRepository.createInventoryPdf(
                    sessionDate = System.currentTimeMillis(),
                    items = rows,
                    totalText = InventoryMath.formatLei(state.totalBani)
                )
                _uiState.value = _uiState.value.copy(isGeneratingPdf = false, generatedPdf = file)
            } catch (e: Exception) {
                Log.e(TAG, "PDF export failed", e)
                _uiState.value = _uiState.value.copy(
                    isGeneratingPdf = false,
                    message = "Eroare la PDF: ${e.message}"
                )
            }
        }
    }

    fun createShareIntent(): Intent? {
        val file = _uiState.value.generatedPdf ?: return null
        val context = getApplication<Application>()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun clearGeneratedPdf() {
        _uiState.value = _uiState.value.copy(generatedPdf = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    override fun onCleared() {
        super.onCleared()
        speechRepository.release()
    }

    companion object {
        private const val TAG = "InventoryViewModel"
        private const val MATCH_THRESHOLD = 0.82
    }
}
