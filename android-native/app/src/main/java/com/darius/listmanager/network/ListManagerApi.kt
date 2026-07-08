package com.darius.listmanager.network

import retrofit2.Response
import retrofit2.http.*

interface ListManagerApi {
    
    // ===== Products =====
    @GET("/api/catalog/products")
    suspend fun getProducts(@Query("search") search: String? = null): Response<List<ProductDTO>>

    @POST("/api/catalog/products")
    suspend fun createProduct(@Body request: ProductCreate): Response<ProductDTO>
    
    @PUT("/api/catalog/products/{product_id}")
    suspend fun updateProduct(
        @Path("product_id") productId: Long,
        @Body request: ProductCreate
    ): Response<ProductDTO>
    
    @DELETE("/api/catalog/products/{product_id}")
    suspend fun deleteProduct(@Path("product_id") productId: Long): Response<Unit>
    
    // ===== Distributors =====

    @GET("/api/catalog/distributors")
    suspend fun getDistributors(): Response<List<DistributorDTO>>
    
    @POST("/api/catalog/distributors")
    suspend fun createDistributor(@Body request: DistributorCreate): Response<DistributorDTO>

    @PUT("/api/catalog/distributors/{distributor_id}")
    suspend fun updateDistributor(
        @Path("distributor_id") distributorId: Long,
        @Body request: DistributorCreate
    ): Response<DistributorDTO>
    
    @DELETE("/api/catalog/distributors/{distributor_id}")
    suspend fun deleteDistributor(@Path("distributor_id") distributorId: Long): Response<Unit>
        
  
    // ===== Crash reporting =====
    @POST("/api/crashes")
    suspend fun reportCrash(@Body crash: CrashReportRequest): Response<Unit>

    // ===== Authentication =====
    @POST("/api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>
    
    @POST("/api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>
    
    @GET("/api/auth/me")
    suspend fun getCurrentUser(): Response<UserDTO>
    
    @PUT("/api/auth/me")
    suspend fun updateCurrentUser(@Body request: UpdateUserRequest): Response<UserDTO>

    // ===== Session - Management =====
    @GET("/api/session/active")
    suspend fun getActiveSession(@Query("team_id") teamId: Long? = null): Response<GlobalSessionDTO>
    
    @POST("/api/session/create")
    suspend fun createSession(@Body request: CreateSessionRequest): Response<GlobalSessionDTO>
    
    @POST("/api/session/{session_id}/complete")
    suspend fun completeSession(@Path("session_id") sessionId: Long): Response<CompleteSessionResponse>
    
    // ===== Session - Items Operations =====
    @GET("/api/session/{session_id}/items")
    suspend fun getSessionItems(@Path("session_id") sessionId: Long): Response<List<GlobalSessionItemDTO>>
    
    @POST("/api/session/{session_id}/items")
    suspend fun addSessionItem(
        @Path("session_id") sessionId: Long,
        @Body request: AddItemRequest
    ): Response<GlobalSessionItemDTO>
    
    @PUT("/api/session/items/{item_id}")
    suspend fun updateSessionItem(
        @Path("item_id") itemId: Long,
        @Body request: UpdateItemRequest
    ): Response<GlobalSessionItemDTO>
    
    @DELETE("/api/session/items/{item_id}")
    suspend fun deleteSessionItem(@Path("item_id") itemId: Long): Response<Unit>
    
    @DELETE("/api/session/{session_id}/items")
    suspend fun clearSession(@Path("session_id") sessionId: Long): Response<Unit>
    
    // ===== Stats =====
    @GET("/api/stats")
    suspend fun getStats(): Response<StatsDTO>

    // ===== Teams =====
    @POST("/api/teams")
    suspend fun createTeam(@Body request: TeamCreateRequest): Response<TeamDTO>

    @GET("/api/teams")
    suspend fun getMyTeams(): Response<List<TeamDTO>>

    @POST("/api/teams/{team_id}/invites")
    suspend fun createInvite(
        @Path("team_id") teamId: Long,
        @Body request: InviteCreateRequest = InviteCreateRequest()
    ): Response<InviteDTO>

    @POST("/api/teams/invites/{code}/accept")
    suspend fun acceptInvite(@Path("code") code: String): Response<TeamMemberDTO>

    @GET("/api/teams/{team_id}/members")
    suspend fun getTeamMembers(@Path("team_id") teamId: Long): Response<List<TeamMemberDTO>>

    @DELETE("/api/teams/{team_id}/members/{user_id}")
    suspend fun removeTeamMember(
        @Path("team_id") teamId: Long,
        @Path("user_id") userId: Long
    ): Response<Unit>
}