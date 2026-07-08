package com.darius.listmanager

import com.darius.listmanager.network.*
import retrofit2.Response
import java.io.IOException

/**
 * Test double for [ListManagerApi] used by instrumented tests.
 *
 * Every endpoint fails as if the network were unavailable. Repositories catch this
 * and fall back to their local Room cache (see
 * [com.darius.listmanager.data.repository.ProductRepository.getAll]), which keeps
 * instrumented tests hermetic: they exercise the seeded in-memory database instead of
 * a real backend, regardless of the device's actual network state.
 *
 * NOTE: this throws [IOException] (a checked [Exception]) rather than
 * [NotImplementedError] — the latter is an [Error], which the repositories'
 * `catch (e: Exception)` blocks do NOT catch, so it would crash the tests instead of
 * triggering the offline fallback.
 */
class FakeListManagerApi : ListManagerApi {
    override suspend fun reportCrash(crash: CrashReportRequest): Response<Unit> =
        Response.success(Unit)
    override suspend fun getPresence(): Response<List<PresenceUserDTO>> =
        Response.success(emptyList())


    private fun offline(): Nothing = throw IOException("FakeListManagerApi: no backend in tests")

    // ===== Products =====
    override suspend fun getProducts(search: String?): Response<List<ProductDTO>> = offline()
    override suspend fun createProduct(request: ProductCreate): Response<ProductDTO> = offline()
    override suspend fun updateProduct(productId: Long, request: ProductCreate): Response<ProductDTO> = offline()
    override suspend fun deleteProduct(productId: Long): Response<Unit> = offline()

    // ===== Distributors =====
    override suspend fun getDistributors(): Response<List<DistributorDTO>> = offline()
    override suspend fun createDistributor(request: DistributorCreate): Response<DistributorDTO> = offline()
    override suspend fun updateDistributor(distributorId: Long, request: DistributorCreate): Response<DistributorDTO> = offline()
    override suspend fun deleteDistributor(distributorId: Long): Response<Unit> = offline()

    // ===== Authentication =====
    override suspend fun login(request: LoginRequest): Response<LoginResponse> = offline()
    override suspend fun register(request: RegisterRequest): Response<RegisterResponse> = offline()
    override suspend fun getCurrentUser(): Response<UserDTO> = offline()
    override suspend fun updateCurrentUser(request: UpdateUserRequest): Response<UserDTO> = offline()

    // ===== Session =====
    override suspend fun getActiveSession(teamId: Long?): Response<GlobalSessionDTO> = offline()
    override suspend fun createSession(request: CreateSessionRequest): Response<GlobalSessionDTO> = offline()
    override suspend fun completeSession(sessionId: Long): Response<CompleteSessionResponse> = offline()
    override suspend fun getSessionItems(sessionId: Long): Response<List<GlobalSessionItemDTO>> = offline()
    override suspend fun addSessionItem(sessionId: Long, request: AddItemRequest): Response<GlobalSessionItemDTO> = offline()
    override suspend fun updateSessionItem(itemId: Long, request: UpdateItemRequest): Response<GlobalSessionItemDTO> = offline()
    override suspend fun deleteSessionItem(itemId: Long): Response<Unit> = offline()
    override suspend fun clearSession(sessionId: Long): Response<Unit> = offline()

    // ===== Stats =====
    override suspend fun getStats(): Response<StatsDTO> = offline()

    // ===== Teams =====
    override suspend fun createTeam(request: TeamCreateRequest): Response<TeamDTO> = offline()
    override suspend fun getMyTeams(): Response<List<TeamDTO>> = offline()
    override suspend fun createInvite(teamId: Long, request: InviteCreateRequest): Response<InviteDTO> = offline()
    override suspend fun acceptInvite(code: String): Response<TeamMemberDTO> = offline()
    override suspend fun getTeamMembers(teamId: Long): Response<List<TeamMemberDTO>> = offline()
    override suspend fun removeTeamMember(teamId: Long, userId: Long): Response<Unit> = offline()
}
