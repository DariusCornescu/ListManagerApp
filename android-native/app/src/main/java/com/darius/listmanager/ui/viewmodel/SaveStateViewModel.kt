package com.darius.listmanager.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.data.model.OperationData
import com.darius.listmanager.data.repository.AuthState
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.sync.ReviewOutcome
import com.darius.listmanager.sync.SaveState
import com.darius.listmanager.sync.SyncReviewResolver
import com.darius.listmanager.sync.SyncService
import com.darius.listmanager.sync.SyncWorker
import com.darius.listmanager.sync.isHeldForReview
import com.darius.listmanager.sync.isVersionConflict
import com.darius.listmanager.sync.saveStateOf
import com.darius.listmanager.util.NetworkHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

enum class ReviewKind { QUEUED, CONFLICT, HELD }

data class ReviewLine(
    val id: Long,
    val title: String,
    val kind: ReviewKind,
    val operationType: String,
)

/** Drives the save-state chip and the dialog behind it. */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveStateViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val syncService = SyncService(application)
    private val resolver = SyncReviewResolver(database, RetrofitClient.api) {
        syncService.syncPendingOperations()
    }
    private val json = Json { ignoreUnknownKeys = true }

    private val unfinished = AuthState.userId
        .flatMapLatest { userId ->
            database.pendingOperationDao()
                .getUnfinishedFlowForUser(userId ?: PendingOperationRepository.GUEST_ACCOUNT_ID)
        }

    private val online = flow {
        while (true) {
            emit(NetworkHelper.isNetworkAvailable(getApplication()))
            delay(ONLINE_POLL_MILLIS)
        }
    }.distinctUntilChanged()

    val saveState: StateFlow<SaveState> = combine(unfinished, online, ::saveStateOf)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SaveState.Saved)

    val lines: StateFlow<List<ReviewLine>> = unfinished
        .map { operations -> operations.map { it.toLine() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _isResolving = MutableStateFlow(false)
    val isResolving: StateFlow<Boolean> = _isResolving.asStateFlow()

    fun keepMine(id: Long) = resolve { resolver.keepMine(id) }
    fun useServer(id: Long) = resolve { resolver.discard(id) }
    fun retry(id: Long) = resolve { resolver.retry(id) }
    fun syncNow() = SyncWorker.scheduleImmediateSync(getApplication())
    fun clearMessage() { _message.value = null }

    private fun resolve(action: suspend () -> ReviewOutcome) {
        viewModelScope.launch {
            _isResolving.value = true
            try {
                _message.value = messageFor(action())
            } finally {
                _isResolving.value = false
            }
        }
    }

    private suspend fun PendingOperationEntity.toLine() = ReviewLine(
        id = id,
        title = describe(),
        kind = when {
            isVersionConflict -> ReviewKind.CONFLICT
            isHeldForReview -> ReviewKind.HELD
            else -> ReviewKind.QUEUED
        },
        operationType = operationType,
    )

    private suspend fun PendingOperationEntity.describe(): String {
        val data = runCatching { json.decodeFromString<OperationData>(operationData) }.getOrNull()
        return when (data) {
            is OperationData.AddSessionItem -> "${productName(data.productId)}: adaugă ${data.quantity}"
            is OperationData.UpdateSessionItem -> "${itemName(data.itemId)}: cantitate ${data.quantity}"
            is OperationData.DeleteSessionItem -> "${itemName(data.itemId)}: șterge din listă"
            is OperationData.ClearSession -> "Golește lista"
            is OperationData.CompleteSession -> "Finalizează lista"
            is OperationData.CreateSession -> "Creează lista „${data.name}”"
            is OperationData.CreateProduct -> "Produs nou: ${data.name}"
            is OperationData.UpdateProduct -> "Modifică produsul ${data.name}"
            is OperationData.DeleteProduct -> "Șterge ${productName(data.productId)}"
            is OperationData.CreateDistributor -> "Distribuitor nou: ${data.distributorName}"
            is OperationData.UpdateDistributor -> "Modifică distribuitorul ${data.distributorName}"
            is OperationData.DeleteDistributor -> "Șterge un distribuitor"
            is OperationData.AddInventoryItem -> "Inventar: ${data.name}"
            is OperationData.UpdateInventoryItem ->
                "Inventar: ${database.inventoryItemDao().getByUuid(data.itemUuid)?.name ?: "rând modificat"}"
            is OperationData.DeleteInventoryItem -> "Inventar: șterge un rând"
            is OperationData.ClearInventory -> "Inventar: listă nouă"
            null -> "Modificare salvată"
        }
    }

    private suspend fun productName(id: Long): String =
        database.productDao().getById(id)?.name ?: "Produs"

    private suspend fun itemName(itemId: Long): String =
        database.sessionItemDao().getById(itemId)?.let { productName(it.productId) } ?: "Produs"

    private fun messageFor(outcome: ReviewOutcome): String = when (outcome) {
        ReviewOutcome.RESOLVED -> "Rezolvat"
        ReviewOutcome.QUEUED -> "Fără conexiune. Se va trimite automat."
        ReviewOutcome.ITEM_GONE -> "Altcineva a șters acest produs din listă."
        ReviewOutcome.STILL_CONFLICTED -> "Lista se schimbă chiar acum. Încearcă din nou peste câteva secunde."
        ReviewOutcome.NEEDS_REVIEW -> "Serverul a refuzat modificarea."
    }

    private companion object {
        const val ONLINE_POLL_MILLIS = 5_000L
    }
}
