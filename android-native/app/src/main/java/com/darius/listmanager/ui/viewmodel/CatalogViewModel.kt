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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val pendingOperationRepository = PendingOperationRepository(database.pendingOperationDao())
    private val distributorRepository = DistributorRepository( database.distributorDao(), RetrofitClient.api, pendingOperationRepository, application.applicationContext )
    private val productRepository = ProductRepository( database.productDao(), pendingOperationRepository, application.applicationContext, RetrofitClient.api)

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    init {
        loadCatalog()
        observeRole()
    }

    private fun observeRole() {
        viewModelScope.launch {
            AuthState.role.collect { role ->
                _uiState.value = _uiState.value.copy(isAdmin = role == "ADMIN")
            }
        }
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)

                // Check if we're online
                val isOnline = com.darius.listmanager.util.NetworkHelper.isNetworkAvailable(getApplication())

                // Load distributors and their products for offline mode
                val distributors = distributorRepository.getAll()
                val allProducts = productRepository.getAll()
                android.util.Log.d("CatalogViewModel", "Loaded ${distributors.size} distributors, ${allProducts.size} products (Online: $isOnline)")

                val distributorsWithProducts = distributors
                    .sortedBy { it.distributorName.lowercase() }
                    .map { distributor ->
                        val products = productRepository.getByDistributor(distributor.id)
                        android.util.Log.d("CatalogViewModel", "${distributor.distributorName}: ${products.size} products")
                        DistributorWithProducts(distributor, products)
                    }

                _uiState.value = _uiState.value.copy(
                    distributors = distributorsWithProducts,
                    isLoading = false,
                    isOffline = !isOnline
                )

                android.util.Log.d("CatalogViewModel", "Total products: ${distributorsWithProducts.sumOf { it.products.size }}")
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

    fun getFilteredCatalog(): List<DistributorWithProducts> {
        val query = _uiState.value.searchQuery.lowercase()
        if (query.isBlank()) return _uiState.value.distributors

        return _uiState.value.distributors.mapNotNull { distributorWithProducts ->
            val filteredProducts = distributorWithProducts.products.filter { product ->
                product.name.lowercase().contains(query) ||
                        product.aliases?.lowercase()?.contains(query) == true
            }
            // Show distributor if either products match OR distributor name matches
            if (filteredProducts.isNotEmpty() ||
                distributorWithProducts.distributor.distributorName.lowercase().contains(query)) {
                distributorWithProducts.copy(products = filteredProducts)
            } else null
        }
    }

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
                    errorMsg != null -> "Eroare: $errorMsg"
                    else -> "Deleted $successCount product${if (successCount != 1) "s" else ""}"
                }
                _uiState.value = _uiState.value.copy(deleteMessage = message)

                // Exit selection mode
                exitSelectionMode()

                // Reload catalog
                loadCatalog()

            } catch (e: Exception) {
                android.util.Log.e("CatalogViewModel", "Error deleting products", e)
                _uiState.value = _uiState.value.copy(
                    deleteMessage = "Eroare: ${e.message}"
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
                throw Exception("Failed to create distributor")
            }
        } catch (e: Exception) {
            android.util.Log.e("CatalogViewModel", "Error adding product", e)
            throw e
        }
    }
}