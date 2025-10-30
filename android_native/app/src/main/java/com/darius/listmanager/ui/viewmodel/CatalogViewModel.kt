package com.darius.listmanager.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.repository.DistributorRepository
import com.darius.listmanager.data.repository.ProductRepository
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
    val deleteMessage: String? = null
)

data class DistributorWithProducts(
    val distributor: DistributorEntity,
    val products: List<ProductEntity>
)

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val distributorRepository = DistributorRepository(database.distributorDao())
    private val productRepository = ProductRepository(database.productDao())

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    init {
        loadCatalog()
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)

                val distributors = distributorRepository.getAll()
                android.util.Log.d("CatalogViewModel", "Loaded ${distributors.size} distributors")

                val distributorsWithProducts = distributors.map { distributor ->
                    val products = productRepository.getByDistributor(distributor.id)
                    android.util.Log.d("CatalogViewModel", "${distributor.distributorName}: ${products.size} products")
                    DistributorWithProducts(distributor, products)
                }

                _uiState.value = _uiState.value.copy(
                    distributors = distributorsWithProducts,
                    isLoading = false
                )

                android.util.Log.d("CatalogViewModel", "Total products: ${distributorsWithProducts.sumOf { it.products.size }}")
            } catch (e: Exception) {
                android.util.Log.e("CatalogViewModel", "Error loading catalog", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
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

    fun refresh() {
        loadCatalog()
    }

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
                val count = selectedIds.size

                // Delete each product
                selectedIds.forEach { productId ->
                    val product = productRepository.getById(productId)
                    product?.let {
                        productRepository.delete(it)
                    }
                }

                // Show success message
                _uiState.value = _uiState.value.copy(
                    deleteMessage = "Deleted $count product${if (count != 1) "s" else ""}"
                )

                // Exit selection mode
                exitSelectionMode()

                // Reload catalog
                loadCatalog()

            } catch (e: Exception) {
                android.util.Log.e("CatalogViewModel", "Error deleting products", e)
                _uiState.value = _uiState.value.copy(
                    deleteMessage = "Error: ${e.message}"
                )
            }
        }
    }

    fun clearDeleteMessage() {
        _uiState.value = _uiState.value.copy(deleteMessage = null)
    }
}