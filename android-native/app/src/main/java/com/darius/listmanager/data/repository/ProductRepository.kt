package com.darius.listmanager.data.repository

import android.content.Context
import android.util.Log
import com.darius.listmanager.data.local.dao.ProductDao
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.model.*
import com.darius.listmanager.network.*
import com.darius.listmanager.util.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ProductRepository(
    private val dao: ProductDao,
    private val pendingOperationRepository: PendingOperationRepository,
    private val context: Context,
    private val api: ListManagerApi
)   {

    companion object { const val TAG = "ProductRepository" }
    fun getAllFlow(): Flow<List<ProductEntity>> { return dao.getAllFlow() }
    
    /**
     * ONLINE: Fetch de pe server + cache local
     * OFFLINE: Returnează din cache local
     */
    suspend fun getAll(): List<ProductEntity> = withContext(Dispatchers.IO) {
        if (NetworkHelper.isNetworkAvailable(context)) {
            try {
                Log.d(TAG, "ONLINE: Fetching products from server...")
                val response = api.getProducts()
                
                if (response.isSuccessful && response.body() != null) {
                    val products = response.body()!!.map { it.toEntity() }
                    
                    // Salvează în cache local
                    dao.deleteAll()
                    dao.insertAll(products)
                    
                    Log.d(TAG, "Fetched ${products.size} products from server")
                    return@withContext products
                } else {
                    Log.w(TAG, "Server error ${response.code()}, using local cache")
                    return@withContext dao.getAll()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error: ${e.message}, falling back to local cache")
                return@withContext dao.getAll()
            }
        } else {
            Log.d(TAG, "OFFLINE: Using local cache")
            return@withContext dao.getAll()
        }
    }
    

    suspend fun getById(id: Long): ProductEntity? = withContext(Dispatchers.IO) { return@withContext dao.getById(id) }
    suspend fun getByDistributor(distributorId: Long): List<ProductEntity> = withContext(Dispatchers.IO) { return@withContext dao.getByDistributor(distributorId) }

    suspend fun searchFts(query: String): List<ProductEntity> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Searching products: $query")
        return@withContext dao.searchFts(query)
    }
    
    suspend fun searchFtsRaw(query: String): List<ProductEntity> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Searching products (raw): $query")
        return@withContext dao.searchFtsRaw(query)
    }
        
    /**
     * ONLINE: POST pe server → Salvează în local
     * OFFLINE: Salvează local + Creează Pending Operation
     */
    suspend fun insert(product: ProductEntity): Long = withContext(Dispatchers.IO) {
        
        if (NetworkHelper.isNetworkAvailable(context)) {
            try {
                Log.d(TAG, "ONLINE: Creating product on server: ${product.name}")
                
                val request = ProductCreate(
                    name = product.name,
                    distributor_id = product.distributorId,
                    aliases = product.aliases
                )
                val response = api.createProduct(request)
                
                if (response.isSuccessful && response.body() != null) {
                    val serverProduct = response.body()!!.toEntity()
                    
                    // Salvează în local database cu ID-ul de pe server
                    dao.insert(serverProduct)
                    
                    Log.d(TAG, "Created product: ${product.name} (ID: ${serverProduct.id})")
                    return@withContext serverProduct.id
                } else {
                    val errorMsg = "Server error: ${response.code()}"
                    Log.e(TAG, "$errorMsg")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error: ${e.message}, falling back to offline mode", e)
            }
        }
        
        Log.d(TAG, "OFFLINE: Saving product locally and creating pending operation")
        
        try {
            val localId = dao.insert(product)
            
            val operationData = OperationData.CreateProduct(
                name = product.name,
                distributorId = product.distributorId,
                aliases = product.aliases
            )
            
            pendingOperationRepository.queueOperation(
                operationType = OperationType.CREATE_PRODUCT,
                resourceType = ResourceType.PRODUCT,
                resourceId = localId,
                operationData = operationData,
                conflictStrategy = ConflictStrategy.LATEST_WINS
            )
            
            Log.d(TAG, "Saved product locally (ID: $localId) + Queued for sync")
            return@withContext localId
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save locally: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * ONLINE: POST pe server pentru fiecare → Salvează în local
     * OFFLINE: Salvează local + Creează Pending Operations
     */
    suspend fun insertAll(products: List<ProductEntity>) = withContext(Dispatchers.IO) {
        
        if (NetworkHelper.isNetworkAvailable(context)) {
            try {
                Log.d(TAG, "ONLINE: Creating ${products.size} products on server")
                
                val serverProducts = mutableListOf<ProductEntity>()
                val failedProducts = mutableListOf<ProductEntity>()
                
                // Încearcăm să creăm fiecare produs pe server
                products.forEach { product ->
                    try {
                        val request = ProductCreate(
                            name = product.name,
                            distributor_id = product.distributorId,
                            aliases = product.aliases
                        )
                        val response = api.createProduct(request)
                        
                        if (response.isSuccessful && response.body() != null) {
                            serverProducts.add(response.body()!!.toEntity())
                        } else {
                            failedProducts.add(product)
                        }
                    } catch (e: Exception) {
                        failedProducts.add(product)
                    }
                }
                
                // Salvam produsele create cu succes
                if (serverProducts.isNotEmpty()) {
                    dao.insertAll(serverProducts)
                    Log.d(TAG, "Created ${serverProducts.size} products on server")
                }
                
                // Pentru cele eșuate, folosește offline mode
                if (failedProducts.isNotEmpty()) {
                    Log.w(TAG, "${failedProducts.size} products failed, saving offline")
                    failedProducts.forEach { product ->
                        val localId = dao.insert(product)
                        
                        pendingOperationRepository.queueOperation(
                            operationType = OperationType.CREATE_PRODUCT,
                            resourceType = ResourceType.PRODUCT,
                            resourceId = localId,
                            operationData = OperationData.CreateProduct(
                                name = product.name,
                                distributorId = product.distributorId,
                                aliases = product.aliases
                            )
                        )
                    }
                }
                
                return@withContext
                
            } catch (e: Exception) {
                Log.e(TAG, "Network error during batch insert, falling back to offline mode", e)
            }
        }
        
        Log.d(TAG, "OFFLINE: Saving ${products.size} products locally")
        
        dao.insertAll(products)
        
        // Generam pending operations pentru toate
        products.forEach { product ->
            pendingOperationRepository.queueOperation(
                operationType = OperationType.CREATE_PRODUCT,
                resourceType = ResourceType.PRODUCT,
                resourceId = product.id,
                operationData = OperationData.CreateProduct(
                    name = product.name,
                    distributorId = product.distributorId,
                    aliases = product.aliases
                )
            )
        }
        
        Log.d(TAG, "Saved ${products.size} products locally + Queued for sync")
    }
     
    /**
     * ONLINE: PUT pe server → Update în local
     * OFFLINE: Update local + Pending Operation
     */
    suspend fun update(product: ProductEntity) = withContext(Dispatchers.IO) {
        
        if (NetworkHelper.isNetworkAvailable(context)) {
            try {
                Log.d(TAG, "ONLINE: Updating product ${product.id} on server")
                
                val request = ProductCreate(
                    name = product.name,
                    distributor_id = product.distributorId,
                    aliases = product.aliases
                )
                val response = api.updateProduct(product.id, request)
                
                if (response.isSuccessful && response.body() != null) {
                    val updatedProduct = response.body()!!.toEntity()
                    
                    // Update în local cache
                    dao.update(updatedProduct)
                    
                    Log.d(TAG, "Updated product ${product.id} on server")
                    return@withContext
                } else {
                    val errorMsg = "Server error: ${response.code()}"
                    Log.e(TAG, "$errorMsg")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error: ${e.message}, falling back to offline mode", e)
            }
        }
        
        Log.d(TAG, "OFFLINE: Updating product ${product.id} locally and queuing for sync")
        
        dao.update(product)
        val operationData = OperationData.UpdateProduct(
            productId = product.id,
            name = product.name,
            distributorId = product.distributorId,
            aliases = product.aliases
        )
        
        pendingOperationRepository.queueOperation(
            operationType = OperationType.UPDATE_PRODUCT,
            resourceType = ResourceType.PRODUCT,
            resourceId = product.id,
            operationData = operationData,
            conflictStrategy = ConflictStrategy.LATEST_WINS
        )
        
        Log.d(TAG, "Updated product ${product.id} locally + Queued for sync")
    }
    
    /**
     * ONLINE: DELETE pe server → Delete din local
     * OFFLINE: Delete local + Pending Operation
     */
    suspend fun delete(product: ProductEntity) = withContext(Dispatchers.IO) {
        
        if (NetworkHelper.isNetworkAvailable(context)) {
            try {
                Log.d(TAG, "ONLINE: Deleting product ${product.id} on server")
                
                val response = api.deleteProduct(product.id)
                if (response.isSuccessful) {
                    // Șterge din local cache
                    dao.delete(product)
                    
                    Log.d(TAG, "Deleted product ${product.id} from server")
                    return@withContext
                } else {
                    val errorMsg = "Server error: ${response.code()}"
                    Log.e(TAG, "$errorMsg")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error: ${e.message}, falling back to offline mode", e)
            }
        }
        
        Log.d(TAG, "OFFLINE: Marking product ${product.id} for deletion and queuing for sync")
        dao.delete(product)
        val operationData = OperationData.DeleteProduct(
            productId = product.id
        )
        
        pendingOperationRepository.queueOperation(
            operationType = OperationType.DELETE_PRODUCT,
            resourceType = ResourceType.PRODUCT,
            resourceId = product.id,
            operationData = operationData,
            conflictStrategy = ConflictStrategy.LATEST_WINS
        )
        
        Log.d(TAG, "Deleted product ${product.id} locally + Queued for sync")
    }
}

fun ProductDTO.toEntity(): ProductEntity {
    return ProductEntity( id = this.id, name = this.name, distributorId = this.distributor_id, aliases = this.aliases )
}