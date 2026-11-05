package com.darius.listmanager.network

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

/** Where the rotating token pair lives. A saved refresh token must survive process death. */
interface AuthTokenStore {
    fun accessToken(): String?
    fun refreshToken(): String?
    fun saveTokens(accessToken: String, refreshToken: String)
}

sealed interface RefreshResult {
    data class Success(val accessToken: String, val refreshToken: String) : RefreshResult

    /** The server refused the refresh token, so only a new login can help. */
    data object Rejected : RefreshResult

    /** No verdict (no network, server error). The login must be kept. */
    data object Unavailable : RefreshResult
}

/**
 * Answers a 401 by refreshing once and retrying with the new access token.
 *
 * A rejected refresh returns null, so the 401 reaches the caller and the app logs out.
 * An unavailable server throws [IOException] instead: the request fails like any network
 * error and queued work stays queued, rather than a dead zone in the shop ending the login.
 */
class TokenRefreshAuthenticator(
    private val store: AuthTokenStore,
    private val refresh: (String) -> RefreshResult,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val sentHeader = response.request.header(AUTHORIZATION) ?: return null
        if (response.priorResponse != null) return null

        synchronized(lock) {
            // Another request may have refreshed while this one waited for the lock.
            // Refreshing again would needlessly rotate the token it just saved.
            val current = store.accessToken()
            if (current != null && bearer(current) != sentHeader) {
                return retryWith(response, current)
            }

            val refreshToken = store.refreshToken() ?: return null
            return when (val result = refresh(refreshToken)) {
                is RefreshResult.Success -> {
                    store.saveTokens(result.accessToken, result.refreshToken)
                    retryWith(response, result.accessToken)
                }
                RefreshResult.Rejected -> null
                RefreshResult.Unavailable -> throw IOException("Could not refresh the session")
            }
        }
    }

    private fun retryWith(response: Response, accessToken: String): Request =
        response.request.newBuilder().header(AUTHORIZATION, bearer(accessToken)).build()

    private fun bearer(token: String) = "Bearer $token"

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}
