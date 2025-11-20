package com.darius.listmanager.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.UserEntity
import com.darius.listmanager.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val message: String? = null,
    val currentUser: UserEntity? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val userRepository = UserRepository(database.userDao())

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        checkAuthStatus()
    }

    private fun checkAuthStatus() {
        viewModelScope.launch {
            userRepository.getCurrentUserFlow().collect { user ->
                _uiState.value = _uiState.value.copy(
                    currentUser = user,
                    isAuthenticated = user?.isLoggedIn == true
                )
            }
        }
    }

    /**
     * Login (currently mock - will connect to server later)
     */
    fun login(username: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // TODO: Replace with actual API call
                // For now, just save user locally

                // Simulate network delay
                kotlinx.coroutines.delay(1000)

                // Mock success - save user
                val user = UserEntity(
                    id = 1,
                    username = username,
                    email = "$username@example.com",
                    jwtToken = "mock_token_${System.currentTimeMillis()}",
                    tokenExpiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000), // 24h
                    isLoggedIn = true
                )

                userRepository.saveUser(user)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "Login successful! Welcome back, $username",
                    isAuthenticated = true
                )

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "Login failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Register (currently mock - will connect to server later)
     */
    fun register(username: String, email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // TODO: Replace with actual API call

                // Simulate network delay
                kotlinx.coroutines.delay(1000)

                // Mock success - save user
                val user = UserEntity(
                    id = 1,
                    username = username,
                    email = email,
                    jwtToken = "mock_token_${System.currentTimeMillis()}",
                    tokenExpiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000),
                    isLoggedIn = true
                )

                userRepository.saveUser(user)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "Account created! Welcome, $username",
                    isAuthenticated = true
                )

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "Registration failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Logout
     */
    fun logout() {
        viewModelScope.launch {
            try {
                userRepository.logout()
                _uiState.value = _uiState.value.copy(
                    message = "Logged out successfully",
                    isAuthenticated = false,
                    currentUser = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    message = "Logout failed: ${e.message}"
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}