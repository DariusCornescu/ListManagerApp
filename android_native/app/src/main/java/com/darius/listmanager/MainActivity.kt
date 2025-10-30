package com.darius.listmanager

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.repository.DistributorRepository
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.data.repository.SessionRepository
import com.darius.listmanager.ui.theme.AppTheme
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize the database instance
        val database = AppDatabase.getInstance(applicationContext)

        lifecycleScope.launch {
            val distributorRepo = DistributorRepository(database.distributorDao())
            val productRepo = ProductRepository(database.productDao())

            val distributors = distributorRepo.getAll()
            val products = productRepo.getAll()

            Log.d("DATABASE_TEST", "Distributors: ${distributors.size}")
            Log.d("DATABASE_TEST", "Products: ${products.size}")

            distributors.forEach {
                Log.d("DATABASE_TEST", "Distributor: ${it.distributorName}")
            }
            products.take(5).forEach {
                Log.d("DATABASE_TEST", "Product: ${it.name} (${it.aliases})")
            }
        }

        setContent {
            AppTheme {
                ListManagerApp()
            }
        }
    }
}