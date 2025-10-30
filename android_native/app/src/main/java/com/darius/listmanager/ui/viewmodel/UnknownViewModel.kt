package com.darius.listmanager.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.UnknownProductEntity
import com.darius.listmanager.data.repository.DistributorRepository
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.data.repository.UnknownRepository
import com.darius.listmanager.data.usecase.SaveProductToCatalogUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


data class UnknownUiState(
    val unknownProducts: List<UnknownProductEntity> = emptyList(),
    val distributors: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val saveMessage: String? = null
)

class UnknownViewModel(application: Application): AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val unknownRepository = UnknownRepository(database.unknownDao())
    private val productRepository = ProductRepository(database.productDao())
    private val distributorRepository = DistributorRepository(database.distributorDao())

    private val saveProductUseCase = SaveProductToCatalogUseCase(
        productRepository,
        distributorRepository,
        unknownRepository
    )

    private val _uiState = MutableStateFlow(UnknownUiState())
    val uiState: StateFlow<UnknownUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                // Load distributors
                val distributors = distributorRepository.getAll().map { it.distributorName }

                // Load unknown products
                unknownRepository.getAllFlow().collect { products ->
                    _uiState.value = UnknownUiState(
                        unknownProducts = products,
                        distributors = distributors,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.value = UnknownUiState(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    fun saveProduct(
        unknownProductId: Long,
        productName: String,
        distributorName: String,
        aliases: String
    ) {
        viewModelScope.launch {
            try {
                saveProductUseCase.execute(
                    productName = productName,
                    distributorName = distributorName,
                    aliases = aliases,
                    unknownProductId = unknownProductId
                )

                // Reload distributors in case a new one was added
                val updatedDistributors = distributorRepository.getAll().map { it.distributorName }
                _uiState.value = _uiState.value.copy(
                    distributors = updatedDistributors,
                    saveMessage = "Product '$productName' saved to catalog!"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to save product: ${e.message}"
                )
            }
        }
    }

    fun deleteUnknownProduct(id: Long) {
        viewModelScope.launch {
            unknownRepository.deleteById(id)
        }
    }

    fun clearSaveMessage() {
        _uiState.value = _uiState.value.copy(saveMessage = null)
    }
}