package com.darius.listmanager.network

import com.darius.listmanager.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.IOException
import java.util.concurrent.TimeUnit

object RetrofitClient {

    @Volatile
    private var authToken: String? = null
    fun setAuthToken(token: String?) { authToken = token }

    /** Set once at process start, before any authenticated request can fail. */
    @Volatile
    var tokenStore: AuthTokenStore? = null

    /** Called with the new access token after a successful refresh. */
    @Volatile
    var onTokenRefreshed: ((String) -> Unit)? = null

    /**
     * Invoked once when an authenticated request comes back 401 (token expired / revoked).
     * Set by the app (MainActivity) to clear the token, disconnect the WebSocket and route to login.
     */
    @Volatile
    var onUnauthorized: (() -> Unit)? = null

    /** Bodies carry passwords, tokens and dictated text, so they are never logged. */
    internal fun httpLogLevel(debug: Boolean): HttpLoggingInterceptor.Level =
        if (debug) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = httpLogLevel(BuildConfig.DEBUG)
    }

    private interface RefreshApi {
        @POST("/api/auth/refresh")
        fun refresh(@Body request: RefreshTokenRequest): Call<LoginResponse>
    }

    // Its own bare client: a refresh must not pass through the interceptors or
    // authenticator of the client that is waiting on it.
    private val refreshApi: RefreshApi by lazy {
        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RefreshApi::class.java)
    }

    private fun requestRefresh(refreshToken: String): RefreshResult = try {
        val response = refreshApi.refresh(RefreshTokenRequest(refreshToken)).execute()
        val body = response.body()
        when {
            response.isSuccessful && body?.refresh_token != null ->
                RefreshResult.Success(body.access_token, body.refresh_token)
            response.code() == 401 || response.code() == 403 -> RefreshResult.Rejected
            else -> RefreshResult.Unavailable
        }
    } catch (e: IOException) {
        RefreshResult.Unavailable
    }

    /** Applies a refreshed pair to the store, this client and any listener. */
    private val liveTokenStore = object : AuthTokenStore {
        override fun accessToken() = authToken
        override fun refreshToken() = tokenStore?.refreshToken()
        override fun saveTokens(accessToken: String, refreshToken: String) {
            tokenStore?.saveTokens(accessToken, refreshToken)
            authToken = accessToken
            onTokenRefreshed?.invoke(accessToken)
        }
    }

    private val authInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()

        val newRequest = if (authToken != null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $authToken")
                .build()
        } else {
            originalRequest
        }

        chain.proceed(newRequest)
    }

    /**
     * Detects a login that cannot be recovered: this sees a 401 only after
     * [TokenRefreshAuthenticator] could not fix it. Clear the token and notify the app exactly once. We avoid auth loops by
     * only firing for requests that actually had a token — unauthenticated requests (e.g. login)
     * legitimately return 401 and must not trigger logout.
     */
    private val unauthorizedInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code == 401 && request.header("Authorization") != null) {
            // Clear the in-memory token so subsequent requests are unauthenticated (no retry loop).
            authToken = null
            onUnauthorized?.invoke()
        }
        response
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(unauthorizedInterceptor)
        .authenticator(TokenRefreshAuthenticator(liveTokenStore, ::requestRefresh))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(ApiConfig.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    val api: ListManagerApi = retrofit.create(ListManagerApi::class.java)
}