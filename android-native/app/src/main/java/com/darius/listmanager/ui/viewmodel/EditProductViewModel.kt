package com.darius.listmanager.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.repository.*
import com.darius.listmanager.data.usecase.EditProductUseCase
import com.darius.listmanager.network.RetrofitClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EditProductUiState(
    val productId: Long = 0L,
    val productName: String = "",
    val distributorName: String = "",
    val aliases: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val hasChanges: Boolean = false,
    val operationSuccess: Boolean = false,
    val isAdmin: Boolean = false // Only ADMINs may edit/delete
)

class EditProductViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val pendingOperationRepo = PendingOperationRepository(database.pendingOperationDao())
    private val productRepository = ProductRepository( database.productDao(), pendingOperationRepo, application.applicationContext, RetrofitClient.api )
    private val distributorRepository = DistributorRepository( database.distributorDao(), RetrofitClient.api, pendingOperationRepo, application.applicationContext )
    private val editProductUseCase = EditProductUseCase(productRepository)
    private val _uiState = MutableStateFlow(EditProductUiState())
    val uiState: StateFlow<EditProductUiState> = _uiState.asStateFlow()
    private var originalName: String = ""
    private var originalAliases: String = ""

    init { observeRole() }

    private fun observeRole() {
        viewModelScope.launch {
            AuthState.role.collect { role ->
                _uiState.value = _uiState.value.copy(isAdmin = role == "ADMIN")
            }
        }
    }

    fun loadProduct(productId: Long) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy( productId = productId, isLoading = true, error = null )

                val product = productRepository.getById(productId)
                if (product == null) {
                    _uiState.value = _uiState.value.copy( isLoading = false, error = "Product not found" )
                    return@launch
                }

                val distributor = distributorRepository.getById(product.distributorId)
                originalName = product.name
                originalAliases = product.aliases ?: ""
                _uiState.value = EditProductUiState( productId = productId, productName = product.name, distributorName = distributor?.distributorName ?: "Unknown", aliases = product.aliases ?: "", isLoading = false, isAdmin = _uiState.value.isAdmin)

            } catch (e: Exception) {
                android.util.Log.e("EditProductViewModel", "Error loading product", e)
                _uiState.value = _uiState.value.copy( isLoading = false, error = e.message )
            }
        }
    }

    fun updateProductName(name: String) {
        _uiState.value = _uiState.value.copy( productName = name, hasChanges = hasChanges(name, _uiState.value.aliases) )
    }

    fun updateAliases(aliases: String) {
        _uiState.value = _uiState.value.copy( aliases = aliases, hasChanges = hasChanges(_uiState.value.productName, aliases) )
    }

    private fun hasChanges(name: String, aliases: String): Boolean {
        return name != originalName || aliases != originalAliases
    }

    fun saveProduct() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isSaving = true)
                val result = editProductUseCase.execute( productId = _uiState.value.productId, productName = _uiState.value.productName, aliases = _uiState.value.aliases )
                _uiState.value = when (result) {
                    is RepoResult.Success,
                    is RepoResult.QueuedOffline ->
                        _uiState.value.copy( isSaving = false, message = "Product updated successfully", hasChanges = false, operationSuccess = true )
                    is RepoResult.Forbidden ->
                        _uiState.value.copy( isSaving = false, message = "Doar administratorii pot modifica catalogul" )
                    is RepoResult.Error ->
                        _uiState.value.copy( isSaving = false, message = "Eroare: ${result.message}" )
                }
            } catch (e: Exception) {
                android.util.Log.e("EditProductViewModel", "Error saving product", e)
                _uiState.value = _uiState.value.copy( isSaving = false, message = "Eroare: ${e.message}"
                )
            }
        }
    }

    fun deleteProduct() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isSaving = true)
                val product = productRepository.getById(_uiState.value.productId)
                if (product == null) {
                    _uiState.value = _uiState.value.copy( isSaving = false, message = "Product not found" )
                    return@launch
                }
                _uiState.value = when (val result = productRepository.delete(product)) {
                    is RepoResult.Success,
                    is RepoResult.QueuedOffline ->
                        _uiState.value.copy( isSaving = false, message = "Product deleted", operationSuccess = true )
                    is RepoResult.Forbidden ->
                        _uiState.value.copy( isSaving = false, message = "Doar administratorii pot modifica catalogul" )
                    is RepoResult.Error ->
                        _uiState.value.copy( isSaving = false, message = "Eroare: ${result.message}" )
                }
            } catch (e: Exception) {
                android.util.Log.e("EditProductViewModel", "Error deleting product", e)
                _uiState.value = _uiState.value.copy( isSaving = false, message = "Eroare: ${e.message}" )
            }
        }
    }

    fun clearMessage() { _uiState.value = _uiState.value.copy(message = null) }
}