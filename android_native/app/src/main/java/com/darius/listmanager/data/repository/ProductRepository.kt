package com.darius.listmanager.data.repository

import com.darius.listmanager.data.local.dao.ProductDao
import com.darius.listmanager.data.local.entity.ProductEntity
import kotlinx.coroutines.flow.Flow

class ProductRepository(private val dao: ProductDao) {

    fun getAllFlow(): Flow<List<ProductEntity>> = dao.getAllFlow()

    suspend fun getAll(): List<ProductEntity> = dao.getAll()

    suspend fun getById(id: Long): ProductEntity? = dao.getById(id)

    suspend fun getByDistributor(distributorId: Long): List<ProductEntity> { return dao.getByDistributor(distributorId)  }

    suspend fun insert(product: ProductEntity): Long = dao.insert(product)

    suspend fun insertAll(products: List<ProductEntity>) = dao.insertAll(products)

    suspend fun update(product: ProductEntity) = dao.update(product)

    suspend fun delete(product: ProductEntity) = dao.delete(product)

    suspend fun searchFts(query: String): List<ProductEntity> {  return dao.searchFts(query)  }

    suspend fun searchFtsRaw(query: String): List<ProductEntity> { return dao.searchFtsRaw(query) }
}