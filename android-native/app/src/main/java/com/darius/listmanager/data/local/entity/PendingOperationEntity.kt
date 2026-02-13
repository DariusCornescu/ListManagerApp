package com.darius.listmanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for storing operations that need to be synchronized with the server.
 *
 * - operationType: Type of operation to perform
 *   Possible values: "CREATE_PRODUCT", "UPDATE_PRODUCT", "DELETE_PRODUCT", "CREATE_DISTRIBUTOR", "UPDATE_DISTRIBUTOR", "DELETE_DISTRIBUTOR", "CREATE_SESSION_ITEM", "UPDATE_SESSION_ITEM", "DELETE_SESSION_ITEM"
 *
 * - resourceType: Type of resource being modified
 *   Possible values: "PRODUCT", "DISTRIBUTOR", "SESSION_ITEM"
 *
 * - resourceId: Local database ID of the resource
 *   - null for CREATE operations (resource doesn't exist yet)
 *   - Non-null Long for UPDATE and DELETE operations
 *
 * - operationData: JSON string containing the full operation payload
 *   Structure depends on operationType and resourceType
 *   Example for CREATE_PRODUCT: {"name":"Product Name","price":10.5,"distributorId":1}
 *
 * - status: Current state of the operation
 *   Possible values:
 *   - "PENDING": Waiting to be processed
 *   - "IN_PROGRESS": Currently being executed
 *   - "FAILED": Execution failed, will retry based on retryCount
 *   - "COMPLETED": Successfully executed and synchronized
 *
 * - retryCount: Number of times the operation has been attempted
 *   Range: 0 to max retry limit (typically 3-5)
 *
 * - lastError: Error message from the most recent failed attempt
 *   - null if no errors occurred or operation succeeded
 *   - Contains HTTP error, network error, or exception message on failure
 *
 * - conflictStrategy: How to resolve conflicts with server data
 *   Possible values:
 *   - "LATEST_WINS": Most recent change (by timestamp) takes precedence
 *   - "SERVER_WINS": Server data always overrides local changes
 *   - "MANUAL": Requires user intervention to resolve conflict
 *
 * - userId: ID of the user who initiated the operation
 *   - null for operations created before user authentication
 *   - Non-null Long for operations tied to a specific user account
 */

@Entity(tableName = "pending_operations")
data class PendingOperationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val operationType: String, 
    val resourceType: String, 
    val resourceId: Long?, 
    
    val operationData: String, 
    
    val status: String,        
    val retryCount: Int = 0,   
    val lastError: String? = null, 
    
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val scheduledFor: Long = System.currentTimeMillis(), 
    
    val conflictStrategy: String = "LATEST_WINS", 
    val userId: Long? = null 
)