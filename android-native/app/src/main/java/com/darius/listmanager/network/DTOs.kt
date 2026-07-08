package com.darius.listmanager.network

data class DistributorDTO( val id: Long, val distributor_name: String, val contact_info: String?, val created_at: String )
data class ProductDTO( val id: Long, val name: String, val distributor_id: Long, val aliases: String?, val created_at: String )
data class DistributorCreate( val distributor_name: String )
data class ProductCreate( val name: String, val distributor_id: Long, val aliases: String?)

data class LoginRequest( val username: String, val password: String )
data class LoginResponse( val access_token: String, val token_type: String )
data class UpdateUserRequest( val email: String? = null, val password: String? = null )
data class GlobalSessionDTO( val id: Long, val name: String, val is_active: Boolean, val created_at: String, val completed_at: String?, val version: Int, val owner_user_id: Long?, val team_id: Long? )
data class GlobalSessionItemDTO( val id: Long, val session_id: Long, val product_id: Long, val quantity: Int, val version: Int, val created_at: String, val updated_at: String, val product_name: String?, val distributor_name: String?)
data class UserDTO( val id: Long, val username: String, val email: String, val role: String, val is_active: Boolean )
data class StatsDTO( val distributors_count: Int, val products_count: Int, val sessions_count: Int, val active_sessions_count: Int, val total_session_items: Int, val active_session: ActiveSessionInfo? )

data class CreateSessionRequest( val name: String, val team_id: Long? = null )
data class AddItemRequest( val product_id: Long, val quantity: Int )
data class UpdateItemRequest( val quantity: Int, val version: Int )
data class CompleteSessionResponse( val session: GlobalSessionDTO, val items_by_distributor: Map<String, List<GlobalSessionItemDTO>>, val distributor_count: Int, val total_items: Int )

data class ActiveSessionInfo( val id: Long, val name: String, val items_count: Int, val created_at: String )
data class RegisterRequest( val username: String, val email: String, val password: String )
data class RegisterResponse( val id: Long, val username: String, val email: String, val role: String, val is_active: Boolean )

// ===== Teams =====
data class TeamDTO( val id: Long, val name: String, val created_at: String )
data class TeamMemberDTO( val id: Long, val team_id: Long, val user_id: Long, val role: String )
data class TeamCreateRequest( val name: String )
data class InviteCreateRequest( val role: String = "member" )
data class InviteDTO( val code: String, val expires_at: String, val role: String )

// ===== Crash reporting =====
data class CrashReportRequest(
    val app_version: String?,
    val android_version: String?,
    val device: String?,
    val username: String?,
    val stacktrace: String
)

// ===== Presence =====
data class PresenceUserDTO( val user_id: Long, val username: String )
