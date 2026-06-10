package com.darius.listmanager.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide observable authentication state.
 *
 * Multiple [com.darius.listmanager.ui.viewmodel.AuthViewModel] instances are created across the
 * app (one per screen via `viewModel()`), and [com.darius.listmanager.AppContent] needs to react
 * to login/logout without polling disk. This singleton holds the single source of truth so every
 * observer (nav graph, viewmodels, RetrofitClient 401 handler) stays in sync.
 */
object AuthState {

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _username = MutableStateFlow<String?>(null)
    val username: StateFlow<String?> = _username.asStateFlow()

    /** Current user's role ("USER" | "ADMIN"), or null until loaded from the server. */
    private val _role = MutableStateFlow<String?>(null)
    val role: StateFlow<String?> = _role.asStateFlow()

    /**
     * Emits a value each time the session is forcibly expired by the server (HTTP 401) so the UI
     * can route back to the login screen and show "Sesiune expirată". Incremented per event.
     */
    private val _sessionExpiredEvent = MutableStateFlow(0)
    val sessionExpiredEvent: StateFlow<Int> = _sessionExpiredEvent.asStateFlow()

    val isAdmin: Boolean get() = _role.value == "ADMIN"

    fun setLoggedIn(loggedIn: Boolean, username: String? = _username.value) {
        _isLoggedIn.value = loggedIn
        _username.value = if (loggedIn) username else null
        if (!loggedIn) _role.value = null
    }

    fun setRole(role: String?) {
        _role.value = role
    }

    fun setUsername(username: String?) {
        _username.value = username
    }

    /** Called by the network layer when a 401 indicates the token is no longer valid. */
    fun notifySessionExpired() {
        _isLoggedIn.value = false
        _role.value = null
        _sessionExpiredEvent.value = _sessionExpiredEvent.value + 1
    }

    fun clear() {
        _isLoggedIn.value = false
        _username.value = null
        _role.value = null
    }
}
