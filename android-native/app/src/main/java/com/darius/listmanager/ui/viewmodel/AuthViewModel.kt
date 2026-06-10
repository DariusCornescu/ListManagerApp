package com.darius.listmanager.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.repository.AuthState
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.network.UpdateUserRequest
import com.darius.listmanager.sync.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val username: String? = null,
    val email: String? = null,
    val role: String? = null,
    val error: String? = null,
    val registrationSuccess: Boolean = false,
    // ===== Profile (Task 6) =====
    val isProfileLoading: Boolean = false,
    val isSavingProfile: Boolean = false,
    val profileMessage: String? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    
    private val syncService = SyncService(application)
    
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /** Process-wide observable login state (single source of truth, not disk polling). */
    val isLoggedIn: StateFlow<Boolean> = AuthState.isLoggedIn

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
            val savedUsername = getSavedUsername()
            AuthState.setLoggedIn(true, savedUsername)
            _uiState.value = _uiState.value.copy(
                isLoggedIn = true,
                username = savedUsername
            )
            // Refresh the real identity (incl. role) from the server in the background.
            loadCurrentUser()
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

                    AuthState.setLoggedIn(true, username)

                    _uiState.value = _uiState.value.copy(
                        isLoggedIn = true,
                        isLoading = false,
                        username = username,
                        error = null
                    )

                    // Load the real account (incl. role) so role-gating works immediately.
                    loadCurrentUser()
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
        AuthState.clear()
        _uiState.value = AuthUiState()
        Log.d(TAG, "User logged out")
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null, registrationSuccess = false)
    }

    // ===== PROFILE (Task 6) =====

    /** Loads the real account from the server (username/email/role). */
    fun loadCurrentUser() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProfileLoading = true)
            try {
                val response = RetrofitClient.api.getCurrentUser()
                if (response.isSuccessful && response.body() != null) {
                    val user = response.body()!!
                    AuthState.setRole(user.role)
                    AuthState.setUsername(user.username)
                    saveUsername(user.username)
                    _uiState.value = _uiState.value.copy(
                        isProfileLoading = false,
                        username = user.username,
                        email = user.email,
                        role = user.role
                    )
                    Log.d(TAG, "Loaded current user: ${user.username} (role=${user.role})")
                } else {
                    _uiState.value = _uiState.value.copy(isProfileLoading = false)
                    Log.w(TAG, "getCurrentUser failed: ${response.code()}")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProfileLoading = false)
                Log.e(TAG, "getCurrentUser exception", e)
            }
        }
    }

    /**
     * Updates the current account. [email] and/or [newPassword] may be blank to leave unchanged.
     */
    fun updateProfile(email: String, newPassword: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSavingProfile = true, profileMessage = null)
            try {
                val request = UpdateUserRequest(
                    email = email.trim().ifBlank { null },
                    password = newPassword.ifBlank { null }
                )
                val response = RetrofitClient.api.updateCurrentUser(request)
                if (response.isSuccessful && response.body() != null) {
                    val user = response.body()!!
                    AuthState.setRole(user.role)
                    AuthState.setUsername(user.username)
                    _uiState.value = _uiState.value.copy(
                        isSavingProfile = false,
                        username = user.username,
                        email = user.email,
                        role = user.role,
                        profileMessage = "Profil actualizat cu succes"
                    )
                    Log.d(TAG, "Profile updated for ${user.username}")
                } else {
                    val msg = when (response.code()) {
                        400, 422 -> "Date invalide"
                        401, 403 -> "Sesiune expirată"
                        else -> "Eroare la actualizare (${response.code()})"
                    }
                    _uiState.value = _uiState.value.copy(isSavingProfile = false, profileMessage = msg)
                    Log.e(TAG, "updateProfile failed: ${response.code()}")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSavingProfile = false,
                    profileMessage = "Nu se poate conecta la server"
                )
                Log.e(TAG, "updateProfile exception", e)
            }
        }
    }

    fun clearProfileMessage() {
        _uiState.value = _uiState.value.copy(profileMessage = null)
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
