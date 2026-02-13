package com.darius.listmanager.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.sync.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val username: String? = null,
    val error: String? = null,
    val registrationSuccess: Boolean = false
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    
    private val syncService = SyncService(application)
    
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()
    
    companion object {
        private const val TAG = "AuthViewModel"
        private const val PREF_USERNAME = "saved_username"
    }
    
    init {
        checkLoginStatus()
    }
    
    private fun checkLoginStatus() {
        val token = syncService.getAuthToken()
        if (token != null) {
            RetrofitClient.setAuthToken(token)
            _uiState.value = _uiState.value.copy(
                isLoggedIn = true,
                username = getSavedUsername()
            )
        }
    }
    
    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(
                error = "Completați toate câmpurile"
            )
            return
        }
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            try {
                val response = RetrofitClient.api.login(
                    com.darius.listmanager.network.LoginRequest(username, password)
                )
                
                if (response.isSuccessful && response.body() != null) {
                    val loginResponse = response.body()!!
                    
                    // Save token
                    syncService.saveAuthToken(loginResponse.access_token)
                    saveUsername(username)
                    
                    Log.d(TAG, "Login successful for: $username")
                    
                    _uiState.value = _uiState.value.copy(
                        isLoggedIn = true,
                        isLoading = false,
                        username = username,
                        error = null
                    )
                } else {
                    val errorMsg = when (response.code()) {
                        401 -> "Utilizator sau parolă incorectă"
                        404 -> "Utilizatorul nu există"
                        else -> "Eroare la autentificare (${response.code()})"
                    }
                    Log.e(TAG, "Login failed: ${response.code()}")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = errorMsg
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login exception", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Nu se poate conecta la server. Verificați conexiunea."
                )
            }
        }
    }
    
    fun register(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(
                error = "Completați toate câmpurile"
            )
            return
        }
        
        if (password.length < 6) {
            _uiState.value = _uiState.value.copy(
                error = "Parola trebuie să aibă cel puțin 6 caractere"
            )
            return
        }
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, registrationSuccess = false)
            
            try {
                // Generate email from username
                val email = "${username.lowercase()}@listmanager.local"
                val response = RetrofitClient.api.register(
                    com.darius.listmanager.network.RegisterRequest(username, email, password)
                )
                
                if (response.isSuccessful && response.body() != null) {
                    Log.d(TAG, "Registration successful for: $username")
                    
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = null,
                        registrationSuccess = true
                    )
                } else {
                    val errorMsg = when (response.code()) {
                        400 -> "Numele de utilizator există deja"
                        422 -> "Date invalide"
                        else -> "Eroare la înregistrare (${response.code()})"
                    }
                    Log.e(TAG, "Registration failed: ${response.code()}")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = errorMsg
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Registration exception", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Nu se poate conecta la server. Verificați conexiunea."
                )
            }
        }
    }
    
    fun logout() {
        syncService.clearAuthToken()
        _uiState.value = AuthUiState()
        Log.d(TAG, "User logged out")
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null, registrationSuccess = false)
    }
    
    private fun saveUsername(username: String) {
        getApplication<Application>().getSharedPreferences("auth", 0)
            .edit()
            .putString(PREF_USERNAME, username)
            .apply()
    }
    
    private fun getSavedUsername(): String? {
        return getApplication<Application>().getSharedPreferences("auth", 0)
            .getString(PREF_USERNAME, null)
    }
}
