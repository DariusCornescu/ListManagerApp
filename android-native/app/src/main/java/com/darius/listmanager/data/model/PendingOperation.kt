package com.darius.listmanager.data.model
import kotlinx.serialization.Serializable

enum class OperationType {
    CREATE_DISTRIBUTOR,
    UPDATE_DISTRIBUTOR,
    DELETE_DISTRIBUTOR,
    
    CREATE_PRODUCT,
    UPDATE_PRODUCT,
    DELETE_PRODUCT,
    
    CREATE_SESSION,
    COMPLETE_SESSION,
    
    ADD_SESSION_ITEM,
    UPDATE_SESSION_ITEM,
    DELETE_SESSION_ITEM,
    CLEAR_SESSION
}

enum class ResourceType {
    DISTRIBUTOR,
    PRODUCT,
    SESSION,
    SESSION_ITEM
}

enum class OperationStatus {
    PENDING,      // Waiting to be synced
    IN_PROGRESS,  // Currently being synced
    FAILED,       // Failed to sync (will retry)
    COMPLETED     // Successfully synced
}

enum class ConflictStrategy {
    LATEST_WINS,  // Local changes overwrite server
    SERVER_WINS,  // Server changes overwrite local
    MANUAL        // User must resolve manually
}

@Serializable  
sealed class OperationData {

    @Serializable  
    data class CreateDistributor( val distributorName: String ) : OperationData()
    @Serializable 
    data class UpdateDistributor( val distributorId: Long, val distributorName: String ) : OperationData()
    @Serializable  
    data class DeleteDistributor( val distributorId: Long ) : OperationData()
    

    @Serializable  
    data class CreateProduct( val name: String, val distributorId: Long, val aliases: String? ) : OperationData()
    @Serializable 
    data class UpdateProduct( val productId: Long, val name: String, val distributorId: Long, val aliases: String? ) : OperationData()
    @Serializable  
    data class DeleteProduct( val productId: Long ) : OperationData()
    

    @Serializable  
    data class AddSessionItem( val sessionId: Long, val productId: Long, val quantity: Int ) : OperationData()
    @Serializable 
    data class UpdateSessionItem( val itemId: Long, val quantity: Int, val version: Int ) : OperationData()
    @Serializable 
    data class DeleteSessionItem( val itemId: Long ) : OperationData()  
    @Serializable  
    data class ClearSession( val sessionId: Long ) : OperationData()
}

sealed class SyncResult {
    data class Success(val resourceId: Long? = null) : SyncResult()
    data class Conflict(val message: String, val serverData: Any? = null) : SyncResult()
    data class Error(val message: String, val retryable: Boolean = true) : SyncResult()
}