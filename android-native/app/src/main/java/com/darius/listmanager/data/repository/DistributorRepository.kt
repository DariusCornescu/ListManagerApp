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
import java.io.IOException

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
    
    /**
     * Returnează id-ul distribuitorului existent sau al celui nou creat.
     * Aruncă o excepție dacă serverul refuză (Forbidden/Error) — apelantul (catalog add)
     * tratează excepția și o afișează ca eroare.
     */
    suspend fun upsertByName(name: String, contactInfo: String? = null): Long = withContext(Dispatchers.IO) {
        val existing = dao.getByName(name)
        if (existing != null) { return@withContext existing.id }

        val result = insert(DistributorEntity(id = 0, distributorName = name))
        return@withContext when (result) {
            is RepoResult.Success -> result.resourceId ?: throw IllegalStateException("Missing distributor id")
            is RepoResult.QueuedOffline -> result.resourceId ?: throw IllegalStateException("Missing distributor id")
            is RepoResult.Forbidden -> throw SecurityException("Doar administratorii pot modifica catalogul")
            is RepoResult.Error -> throw IllegalStateException(result.message)
        }
    }
    
    /**
     * ONLINE: POST pe server → Salvează în local
     * OFFLINE: Salvează local + Creează Pending Operation
     */
    suspend fun insert(distributor: DistributorEntity): RepoResult = withContext(Dispatchers.IO) {

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
                    return@withContext RepoResult.Success(serverDistributor.id)
                } else if (response.code() == 401 || response.code() == 403) {
                    Log.w(TAG, "Forbidden creating distributor: ${response.code()}")
                    return@withContext RepoResult.Forbidden
                } else {
                    val errorMsg = "Server error: ${response.code()}"
                    Log.e(TAG, "ERROR: $errorMsg")
                    return@withContext RepoResult.Error(errorMsg)
                }
            } catch (e: IOException) {
                Log.e(TAG, "ERROR: Connectivity error: ${e.message}, falling back to offline mode", e)
                // fall through to offline branch below
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
            return@withContext RepoResult.QueuedOffline(localId)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save locally: ${e.message}", e)
            return@withContext RepoResult.Error(e.message ?: "Local save failed")
        }
    }

    /**
     * ONLINE: PUT pe server → Update în local
     * OFFLINE: Update local + Pending Operation
     */
    suspend fun update(distributor: DistributorEntity): RepoResult = withContext(Dispatchers.IO) {

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
                    return@withContext RepoResult.Success(updatedDistributor.id)
                } else if (response.code() == 401 || response.code() == 403) {
                    Log.w(TAG, "Forbidden updating distributor ${distributor.id}: ${response.code()}")
                    return@withContext RepoResult.Forbidden
                } else {
                    val errorMsg = "Server error: ${response.code()}"
                    Log.e(TAG, "Error: $errorMsg")
                    return@withContext RepoResult.Error(errorMsg)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Connectivity error: ${e.message}, falling back to offline mode", e)
                // fall through to offline branch below
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
        return@withContext RepoResult.QueuedOffline(distributor.id)
    }
    
    /**
     * ONLINE: DELETE pe server → Delete din local
     * OFFLINE: Delete local + Pending Operation
     */
    suspend fun delete(distributor: DistributorEntity): RepoResult = withContext(Dispatchers.IO) {

        val isOnline = NetworkHelper.isNetworkAvailable(context) && NetworkHelper.isServerReachable()
        if (isOnline) {
            try {
                Log.d(TAG, "ONLINE: Deleting distributor ${distributor.id} on server")

                val response = api.deleteDistributor(distributor.id)

                if (response.isSuccessful) {
                    // Șterge din local cache
                    dao.delete(distributor)

                    Log.d(TAG, "Deleted distributor ${distributor.id} from server")
                    return@withContext RepoResult.Success(distributor.id)
                } else if (response.code() == 401 || response.code() == 403) {
                    Log.w(TAG, "Forbidden deleting distributor ${distributor.id}: ${response.code()}")
                    return@withContext RepoResult.Forbidden
                } else {
                    val errorMsg = "Server error: ${response.code()}"
                    Log.e(TAG, "Error: $errorMsg")
                    return@withContext RepoResult.Error(errorMsg)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Connectivity error: ${e.message}, falling back to offline mode", e)
                // fall through to offline branch below
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
        return@withContext RepoResult.QueuedOffline(distributor.id)
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
