package com.darius.listmanager.data.websocket
sealed class WebSocketMessage {
    
    // ==================== CATALOG UPDATES ====================
    
    data class DistributorCreated( val distributorId: Long, val distributorName: String ) : WebSocketMessage()
    data class DistributorUpdated( val distributorId: Long, val distributorName: String ) : WebSocketMessage()
    data class DistributorDeleted( val distributorId: Long ) : WebSocketMessage()
    
    data class ProductCreated( val productId: Long, val productName: String, val distributorId: Long, val aliases: String? ) : WebSocketMessage()
    data class ProductUpdated( val productId: Long, val productName: String, val distributorId: Long, val aliases: String? ) : WebSocketMessage()
    data class ProductDeleted( val productId: Long ) : WebSocketMessage()
    
    // ==================== SESSION UPDATES ====================

    data class SessionCreated( val sessionId: Long, val sessionName: String, val userId: Long, val username: String ) : WebSocketMessage()
    data class SessionCompleted( val sessionId: Long, val totalItems: Int, val distributorCount: Int, val pdfGenerated: Boolean ) : WebSocketMessage()
    data class SessionCleared( val sessionId: Long ) : WebSocketMessage()
    
    data class SessionItemAdded( val sessionId: Long, val itemId: Long, val productId: Long, val productName: String, val quantity: Int, val userId: Long, val username: String, val version: Int = 1 ) : WebSocketMessage()
    data class SessionItemUpdated( val sessionId: Long, val itemId: Long, val productId: Long, val productName: String, val oldQuantity: Int, val newQuantity: Int, val userId: Long, val username: String, val version: Int = 1 ) : WebSocketMessage()
    data class SessionItemDeleted( val sessionId: Long, val itemId: Long, val productId: Long, val productName: String, val userId: Long, val username: String ) : WebSocketMessage()
    
    // ==================== PDF GENERATION ====================
    
    data class PdfGenerationStarted( val sessionId: Long, val distributorCount: Int ) : WebSocketMessage()
    data class PdfGenerationCompleted( val sessionId: Long, val pdfCount: Int, val distributors: List<String> ) : WebSocketMessage()
    data class PdfGenerationFailed( val sessionId: Long, val error: String ) : WebSocketMessage()
    
    // ==================== CONNECTION & SYSTEM ====================
    
    data class ConnectionStatus(val message: String, val userId: Long? = null, val username: String? = null ) : WebSocketMessage()
    object Pong : WebSocketMessage()
    data class ServerError( val errorType: String, val message: String ) : WebSocketMessage()
    
    // ==================== SYNC STATUS ====================
    
    data class SyncCompleted( val productsCount: Int, val distributorsCount: Int, val timestamp: Long ) : WebSocketMessage()
    
    // ==================== COLLABORATIVE FEATURES  ====================
    
    // For multi-user collaboration
    data class UserJoinedSession( val sessionId: Long, val userId: Long, val username: String ) : WebSocketMessage()
    data class UserLeftSession( val sessionId: Long, val userId: Long, val username: String ) : WebSocketMessage()
    
    // Typing indicators for collaborative editing ( "editing_product", "adding_item", etc.)
    data class UserTyping( val userId: Long, val username: String, val context: String ) : WebSocketMessage()
}