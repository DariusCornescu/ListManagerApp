package com.darius.listmanager.data.repository

import android.content.Context
import android.util.Log
import com.darius.listmanager.data.local.dao.DistributorDao
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.model.*
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.network.*
import com.darius.listmanager.util.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DistributorRepository ( private val dao: DistributorDao, private val api: ListManagerApi, private val pendingOperationRepository: PendingOperationRepository, private val context: Context )   
{ 
    companion object { const val TAG = "DistributorRepository" }

    fun getAllFlow(): Flow<List<DistributorEntity>> { return dao.getAllFlow() }
    
    /**
     * ONLINE: Fetch de pe server + cache local
     * OFFLINE: Returnează din cache local
     */
    suspend fun getAll(): List<DistributorEntity> = withContext(Dispatchers.IO) {
        // withContext(Dispatchers.IO) mută operația pe thread de background
        
        if (NetworkHelper.isNetworkAvailable(context)) {
            try {
                Log.d(TAG, "ONLINE: Fetching distributors from server...")
                val response = api.getDistributors()
                
                if (response.isSuccessful && response.body() != null) {
                    val distributors = response.body()!!.map { it.toEntity() }
                    
                    // Salvează în cache local pentru offline
                    dao.deleteAll()
                    dao.insertAll(distributors)
                    
                    Log.d(TAG, "Fetched ${distributors.size} distributors from server")
                    return@withContext distributors
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
    
 
    suspend fun getById(id: Long): DistributorEntity? = withContext(Dispatchers.IO) { return@withContext dao.getById(id) }
    suspend fun getByName(name: String): DistributorEntity? = withContext(Dispatchers.IO) { return@withContext dao.getByName(name) }
    
    suspend fun upsertByName(name: String, contactInfo: String? = null): Long = withContext(Dispatchers.IO) {
        val existing = dao.getByName(name)
        if (existing != null) { return@withContext existing.id }
        
        return@withContext insert(DistributorEntity(
            id = 0,
            distributorName = name
        ))
    }
    
    /**
     * ONLINE: POST pe server → Salvează în local
     * OFFLINE: Salvează local + Creează Pending Operation
     */
    suspend fun insert(distributor: DistributorEntity): Long = withContext(Dispatchers.IO) {
        
        val isOnline = NetworkHelper.isNetworkAvailable(context) && NetworkHelper.isServerReachable()
        if (isOnline) {
            try {
                Log.d(TAG, "ONLINE: Creating distributor on server: ${distributor.distributorName}")
                
                val request = DistributorCreate(distributor_name = distributor.distributorName)
                val response = api.createDistributor(request)
                
                if (response.isSuccessful && response.body() != null) {
                    val serverDistributor = response.body()!!.toEntity()
                    
                    // Salvează în local cache
                    dao.insert(serverDistributor)
                    
                    Log.d(TAG, "Created distributor: ${distributor.distributorName} (ID: ${serverDistributor.id})")
                    return@withContext serverDistributor.id
                } else {
                    val errorMsg = "Server error: ${response.code()}"
                    Log.e(TAG, "ERROR: $errorMsg")
                }
            } catch (e: Exception) {
                Log.e(TAG, "ERROR: Network error: ${e.message}, falling back to offline mode", e)
            }
        }
        
        Log.d(TAG, "OFFLINE: Saving distributor locally and creating pending operation")
        
        try {
            val localId = dao.insert(distributor)
            
            val operationData = OperationData.CreateDistributor( distributorName = distributor.distributorName )
            pendingOperationRepository.queueOperation(
                operationType = OperationType.CREATE_DISTRIBUTOR,
                resourceType = ResourceType.DISTRIBUTOR,
                resourceId = localId,
                operationData = operationData,
                conflictStrategy = ConflictStrategy.LATEST_WINS
            )
            
            Log.d(TAG, "Saved distributor locally (ID: $localId) + Queued for sync")
            return@withContext localId
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save locally: ${e.message}", e)
            throw e
        }
    }

    /**
     * ONLINE: PUT pe server → Update în local
     * OFFLINE: Update local + Pending Operation
     */
    suspend fun update(distributor: DistributorEntity) = withContext(Dispatchers.IO) {
        
        val isOnline = NetworkHelper.isNetworkAvailable(context) && NetworkHelper.isServerReachable()
        if (isOnline) {
            try {
                Log.d(TAG, "ONLINE: Updating distributor ${distributor.id} on server")
                
                val request = DistributorCreate(distributor_name = distributor.distributorName)
                val response = api.updateDistributor(distributor.id, request)
                
                if (response.isSuccessful && response.body() != null) {
                    val updatedDistributor = response.body()!!.toEntity()
                    
                    // Update în local cache
                    dao.update(updatedDistributor)
                    
                    Log.d(TAG, "Updated distributor ${distributor.id} on server")
                    return@withContext
                } else {
                    val errorMsg = "Server error: ${response.code()}"
                    Log.e(TAG, "Error: $errorMsg")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error: ${e.message}, falling back to offline mode", e)
            }
        }
        
        Log.d(TAG, "OFFLINE: Updating distributor ${distributor.id} locally and queuing for sync")
        
        dao.update(distributor)
        val operationData = OperationData.UpdateDistributor(
            distributorId = distributor.id,
            distributorName = distributor.distributorName
        )
        
        pendingOperationRepository.queueOperation(
            operationType = OperationType.UPDATE_DISTRIBUTOR,
            resourceType = ResourceType.DISTRIBUTOR,
            resourceId = distributor.id,
            operationData = operationData,
            conflictStrategy = ConflictStrategy.LATEST_WINS
        )
        
        Log.d(TAG, "Updated distributor ${distributor.id} locally + Queued for sync")
    }
    
    /**
     * ONLINE: DELETE pe server → Delete din local
     * OFFLINE: Delete local + Pending Operation
     */
    suspend fun delete(distributor: DistributorEntity) = withContext(Dispatchers.IO) {
        
        val isOnline = NetworkHelper.isNetworkAvailable(context) && NetworkHelper.isServerReachable()
        if (isOnline) {
            try {
                Log.d(TAG, "ONLINE: Deleting distributor ${distributor.id} on server")
                
                val response = api.deleteDistributor(distributor.id)
                
                if (response.isSuccessful) {
                    // Șterge din local cache
                    dao.delete(distributor)
                    
                    Log.d(TAG, "Deleted distributor ${distributor.id} from server")
                    return@withContext
                } else {
                    val errorMsg = "Server error: ${response.code()}"
                    Log.e(TAG, "Error: $errorMsg")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error: ${e.message}, falling back to offline mode", e)
            }
        }
        
        Log.d(TAG, "OFFLINE: Marking distributor ${distributor.id} for deletion and queuing for sync")
        
        dao.delete(distributor)
        val operationData = OperationData.DeleteDistributor( distributorId = distributor.id )
        pendingOperationRepository.queueOperation(
            operationType = OperationType.DELETE_DISTRIBUTOR,
            resourceType = ResourceType.DISTRIBUTOR,
            resourceId = distributor.id,
            operationData = operationData,
            conflictStrategy = ConflictStrategy.LATEST_WINS
        )
        
        Log.d(TAG, "Deleted distributor ${distributor.id} locally + Queued for sync")
    }
}

/**
 * Extension function pentru a converti DTO în Entity
 */
fun DistributorDTO.toEntity(): DistributorEntity {
    return DistributorEntity(
        id = this.id,
        distributorName = this.distributor_name
    )
}
