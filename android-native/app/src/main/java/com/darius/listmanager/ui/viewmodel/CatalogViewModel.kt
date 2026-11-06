package com.darius.listmanager.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.repository.DistributorRepository
import com.darius.listmanager.data.repository.*
import com.darius.listmanager.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

data class CatalogUiState(
    val distributors: List<DistributorWithProducts> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val searchQuery: String = "",
    val isSelectionMode: Boolean = false,
    val selectedProductIds: Set<Long> = emptySet(),
    val deleteMessage: String? = null,
    val isOffline: Boolean = false, // Track offline mode
    val isAdmin: Boolean = false // Only ADMINs may mutate the catalog
)

data class DistributorWithProducts(
    val distributor: DistributorEntity,
    val products: List<ProductEntity>
)

private val combiningMarks = Regex("\\p{Mn}+")

/** Lets "paine" find "Pâine". Building these once per load keeps typing fast on a large catalog. */
internal fun catalogSearchKey(text: String): String =
    combiningMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

internal fun productSearchKey(product: ProductEntity): String =
    catalogSearchKey("${product.name} ${product.aliases.orEmpty()}")

/**
 * A distributor whose own name matches keeps all its products. Otherwise it keeps only the
 * products that match, and disappears if none do.
 */
internal fun filterCatalog(
    catalog: List<DistributorWithProducts>,
    query: String,
    productKeys: Map<Long, String>,
): List<DistributorWithProducts> {
    val wanted = catalogSearchKey(query.trim())
    if (wanted.isEmpty()) return catalog
    return catalog.mapNotNull { group ->
        val matching = group.products.filter { productKeys[it.id]?.contains(wanted) == true }
        when {
            catalogSearchKey(group.distributor.distributorName).contains(wanted) -> group
            matching.isNotEmpty() -> group.copy(products = matching)
            else -> null
        }
    }
}

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val pendingOperationRepository = PendingOperationRepository(database.pendingOperationDao())
    private val distributorRepository = DistributorRepository( database.distributorDao(), RetrofitClient.api, pendingOperationRepository, application.applicationContext )
    private val productRepository = ProductRepository( database.productDao(), pendingOperationRepository, application.applicationContext, RetrofitClient.api)

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    private var productKeys: Map<Long, String> = emptyMap()
    private var loadJob: Job? = null

    // The screen asks for the first load when it resumes, so there's no load here as well.
    init { observeRole() }

    private fun observeRole() {
        viewModelScope.launch {
            AuthState.role.collect { role ->
                _uiState.value = _uiState.value.copy(isAdmin = role == "ADMIN")
            }
        }
    }

    private fun loadCatalog() {
        // A newer request replaces one still running, so they can't overwrite each other.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                // Keep what's on screen while refreshing. Only a first load shows placeholders.
                _uiState.value = _uiState.value.copy(
                    isLoading = _uiState.value.distributors.isEmpty(),
                    error = null,
                )

                val isOnline = com.darius.listmanager.util.NetworkHelper.isNetworkAvailable(getApplication())
                val distributors = distributorRepository.getAll()
                val allProducts = productRepository.getAll()

                val (grouped, keys) = withContext(Dispatchers.Default) {
                    val byDistributor = allProducts.groupBy { it.distributorId }
                    val groups = distributors
                        .sortedBy { it.distributorName.lowercase() }
                        .map { distributor ->
                            DistributorWithProducts(
                                distributor,
                                byDistributor[distributor.id].orEmpty().sortedBy { it.name.lowercase() },
                            )
                        }
                    groups to allProducts.associate { it.id to productSearchKey(it) }
                }

                productKeys = keys
                _uiState.value = _uiState.value.copy(
                    distributors = grouped,
                    isLoading = false,
                    isOffline = !isOnline
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("CatalogViewModel", "Error loading catalog", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isOffline = !com.darius.listmanager.util.NetworkHelper.isNetworkAvailable(getApplication()),
                    error = e.message
                )
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun getFilteredCatalog(): List<DistributorWithProducts> =
        filterCatalog(_uiState.value.distributors, _uiState.value.searchQuery, productKeys)

    fun refresh() { loadCatalog() }

    // Selection mode functions

    fun enterSelectionMode() {
        _uiState.value = _uiState.value.copy(isSelectionMode = true)
    }

    fun exitSelectionMode() {
        _uiState.value = _uiState.value.copy(
            isSelectionMode = false,
            selectedProductIds = emptySet()
        )
    }

    fun onProductLongPress(productId: Long) {
        // Non-admins cannot mutate the catalog, so selection mode (which leads to delete/edit)
        // must not be reachable via long-press for them.
        if (!_uiState.value.isAdmin) return
        if (!_uiState.value.isSelectionMode) {
            // Enter selection mode and select this product
            _uiState.value = _uiState.value.copy(
                isSelectionMode = true,
                selectedProductIds = setOf(productId)
            )
        }
    }

    fun onProductClick(productId: Long) {
        if (_uiState.value.isSelectionMode) {
            // Toggle selection
            val currentSelection = _uiState.value.selectedProductIds
            val newSelection = if (currentSelection.contains(productId)) {
                currentSelection - productId
            } else {
                currentSelection + productId
            }
            _uiState.value = _uiState.value.copy(selectedProductIds = newSelection)
        }
        // If not in selection mode, do nothing (could navigate to details if implemented)
    }

    fun selectAll() {
        val allProductIds = _uiState.value.distributors
            .flatMap { it.products }
            .map { it.id }
            .toSet()

        _uiState.value = _uiState.value.copy(selectedProductIds = allProductIds)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedProductIds = emptySet())
    }

    fun deleteSelectedProducts() {
        val selectedIds = _uiState.value.selectedProductIds
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            try {
                var successCount = 0
                var forbidden = false
                var errorMsg: String? = null

                // Delete each product, tracking the typed outcome
                selectedIds.forEach { productId ->
                    val product = productRepository.getById(productId)
                    if (product != null) {
                        when (val result = productRepository.delete(product)) {
                            is RepoResult.Success -> successCount++
                            is RepoResult.QueuedOffline -> successCount++
                            is RepoResult.Forbidden -> forbidden = true
                            is RepoResult.Error -> errorMsg = result.message
                        }
                    }
                }

                val message = when {
                    forbidden -> "Doar administratorii pot modifica catalogul"
                    errorMsg != null -> "Nu am putut șterge toate produsele. Încearcă din nou."
                    successCount == 1 -> "Produs șters"
                    else -> "$successCount produse șterse"
                }
                _uiState.value = _uiState.value.copy(deleteMessage = message)

                // Exit selection mode
                exitSelectionMode()

                // Reload catalog
                loadCatalog()

            } catch (e: Exception) {
                android.util.Log.e("CatalogViewModel", "Error deleting products", e)
                _uiState.value = _uiState.value.copy(
                    deleteMessage = "Nu am putut șterge produsele. Încearcă din nou."
                )
            }
        }
    }

    fun clearDeleteMessage() {
        _uiState.value = _uiState.value.copy(deleteMessage = null)
    }
    
    // Add product manually
    suspend fun addProduct(productName: String, distributorName: String, aliases: String) {
        try {
            android.util.Log.d("CatalogViewModel", "Adding product: $productName for distributor: $distributorName")
            
            // Find or create distributor
            var distributor = distributorRepository.getByName(distributorName)
            
            if (distributor == null) {
                // Create new distributor
                android.util.Log.d("CatalogViewModel", "Distributor not found, creating new one: $distributorName")
                val distributorId = distributorRepository.upsertByName(distributorName)
                distributor = distributorRepository.getById(distributorId)
            }
            
            if (distributor != null) {
                // Create product
                val product = com.darius.listmanager.data.local.entity.ProductEntity(
                    id = 0, // Will be auto-generated
                    name = productName,
                    distributorId = distributor.id,
                    aliases = aliases.ifBlank { null }
                )

                when (val result = productRepository.insert(product)) {
                    is RepoResult.Success,
                    is RepoResult.QueuedOffline -> {
                        android.util.Log.d("CatalogViewModel", "Product added successfully")
                        loadCatalog()
                    }
                    is RepoResult.Forbidden ->
                        throw SecurityException("Doar administratorii pot modifica catalogul")
                    is RepoResult.Error ->
                        throw IllegalStateException(result.message)
                }
            } else {
                throw IllegalStateException("Distributor was not created")
            }
        } catch (e: Exception) {
            android.util.Log.e("CatalogViewModel", "Error adding product", e)
            throw e
        }
    }
}