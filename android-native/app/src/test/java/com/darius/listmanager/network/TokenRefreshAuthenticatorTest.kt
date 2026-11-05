package com.darius.listmanager.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class TokenRefreshAuthenticatorTest {

    private class FakeStore(
        var access: String? = "old-access",
        var refresh: String? = "old-refresh",
    ) : AuthTokenStore {
        override fun accessToken() = access
        override fun refreshToken() = refresh
        override fun saveTokens(accessToken: String, refreshToken: String) {
            access = accessToken
            refresh = refreshToken
        }
    }

    private fun unauthorized(
        header: String? = "Bearer old-access",
        prior: Response? = null,
    ): Response {
        val request = Request.Builder()
            .url("https://example.test/api/session/active".toHttpUrl())
            .apply { if (header != null) header("Authorization", header) }
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .priorResponse(prior)
            .build()
    }

    @Test
    fun `refreshes once, saves the pair and retries with the new token`() {
        val store = FakeStore()
        val presented = mutableListOf<String>()
        val authenticator = TokenRefreshAuthenticator(store) { token ->
            presented += token
            RefreshResult.Success("new-access", "new-refresh")
        }

        val retry = authenticator.authenticate(null, unauthorized())

        assertEquals("Bearer new-access", retry?.header("Authorization"))
        assertEquals(listOf("old-refresh"), presented)
        assertEquals("new-access", store.access)
        assertEquals("new-refresh", store.refresh)
    }

    @Test
    fun `does not refresh again when another request already did`() {
        val store = FakeStore(access = "fresh-access", refresh = "fresh-refresh")
        var calls = 0
        val authenticator = TokenRefreshAuthenticator(store) {
            calls++
            RefreshResult.Rejected
        }

        val retry = authenticator.authenticate(null, unauthorized("Bearer stale-access"))

        assertEquals("Bearer fresh-access", retry?.header("Authorization"))
        assertEquals(0, calls)
    }

    @Test
    fun `rejected refresh lets the 401 through so the app logs out`() {
        val authenticator = TokenRefreshAuthenticator(FakeStore()) { RefreshResult.Rejected }
        assertNull(authenticator.authenticate(null, unauthorized()))
    }

    @Test(expected = IOException::class)
    fun `unavailable server fails like a network error and keeps the login`() {
        val authenticator = TokenRefreshAuthenticator(FakeStore()) { RefreshResult.Unavailable }
        authenticator.authenticate(null, unauthorized())
    }

    @Test
    fun `unavailable server does not touch stored tokens`() {
        val store = FakeStore()
        val authenticator = TokenRefreshAuthenticator(store) { RefreshResult.Unavailable }
        runCatching { authenticator.authenticate(null, unauthorized()) }
        assertEquals("old-access", store.access)
        assertEquals("old-refresh", store.refresh)
    }

    @Test
    fun `gives up after one retry so a revoked token cannot loop`() {
        val authenticator = TokenRefreshAuthenticator(FakeStore()) {
            RefreshResult.Success("a", "b")
        }
        assertNull(authenticator.authenticate(null, unauthorized(prior = unauthorized())))
    }

    @Test
    fun `ignores requests that carried no token, such as a failed login`() {
        var calls = 0
        val authenticator = TokenRefreshAuthenticator(FakeStore()) {
            calls++
            RefreshResult.Rejected
        }
        assertNull(authenticator.authenticate(null, unauthorized(header = null)))
        assertEquals(0, calls)
    }

    @Test
    fun `login from before refresh existed has no refresh token and logs out as before`() {
        val authenticator = TokenRefreshAuthenticator(FakeStore(refresh = null)) {
            RefreshResult.Success("a", "b")
        }
        assertNull(authenticator.authenticate(null, unauthorized()))
    }

    @Test
    fun `http logging never includes bodies and is off in release`() {
        assertEquals(HttpLoggingInterceptor.Level.NONE, RetrofitClient.httpLogLevel(debug = false))
        assertEquals(HttpLoggingInterceptor.Level.BASIC, RetrofitClient.httpLogLevel(debug = true))
    }
}
