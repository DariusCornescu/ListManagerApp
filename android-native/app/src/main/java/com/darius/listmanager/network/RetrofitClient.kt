package com.darius.listmanager.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private var authToken: String? = null
    fun setAuthToken(token: String?) { authToken = token }

    /**
     * Invoked once when an authenticated request comes back 401 (token expired / revoked).
     * Set by the app (MainActivity) to clear the token, disconnect the WebSocket and route to login.
     */
    @Volatile
    var onUnauthorized: (() -> Unit)? = null

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
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
     * Detects token expiry: when an authenticated request (one that carried an Authorization
     * header) returns 401, clear the token and notify the app exactly once. We avoid auth loops by
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