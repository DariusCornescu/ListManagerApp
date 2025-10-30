package com.darius.listmanager.util

import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.ProductEntity

object DatabaseSeeder {

    suspend fun seed(database: AppDatabase) {
        val distributorDao = database.distributorDao()
        val productDao = database.productDao()

        if (distributorDao.getAll().isNotEmpty()) {
            // Database has been seeded already
            return
        }
    }
}