package com.darius.listmanager.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.darius.listmanager.network.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object NetworkHelper {
    
    private const val TAG = "NetworkHelper"

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                   capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            return networkInfo != null && networkInfo.isConnected
        }
    }
    

    // This makes a real HTTP request to the health endpoint, to check server reachability
    suspend fun isServerReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("${ApiConfig.BASE_URL}health")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 1500 
            connection.readTimeout = 1500
            connection.requestMethod = "GET"
            connection.doInput = true
            
            try {
                val responseCode = connection.responseCode
                val isReachable = responseCode == 200
                Log.d(TAG, "Server health check: ${if (isReachable) " OK" else " Failed"} (code: $responseCode)")
                return@withContext isReachable
            } finally {
                try {
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Error disconnecting: ${e.message}")
                }
            }
        } catch (e: java.net.ConnectException) {
            Log.d(TAG, "Server unreachable: Connection refused")
            return@withContext false
        } catch (e: java.net.SocketTimeoutException) {
            Log.d(TAG, "Server unreachable: Timeout")
            return@withContext false
        } catch (e: java.net.UnknownHostException) {
            Log.d(TAG, "Server unreachable: Unknown host")
            return@withContext false
        } catch (e: Exception) {
            Log.w(TAG, "Server unreachable: ${e.javaClass.simpleName} - ${e.message}")
            return@withContext false
        }
    }
    
    // device has internet AND server is reachable
    suspend fun isOnlineAndServerReachable(context: Context): Boolean {
        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "No network available")
            return false
        }
        return isServerReachable()
    }
    

    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            return networkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
    }

    fun isMobileDataConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            return networkInfo?.type == ConnectivityManager.TYPE_MOBILE
        }
    }
}