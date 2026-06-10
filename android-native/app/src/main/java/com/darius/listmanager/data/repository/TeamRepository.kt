package com.darius.listmanager.data.repository

import com.darius.listmanager.network.*
import retrofit2.Response
import java.io.IOException

/**
 * Team management is ONLINE-ONLY by design (rare admin actions — no offline
 * queue). Every call maps to [TeamResult]; connectivity failures surface as
 * [TeamResult.Offline] so the UI can show "requires connection".
 */
sealed class TeamResult<out T> {
    data class Success<T>(val data: T) : TeamResult<T>()
    data class Failure(val message: String) : TeamResult<Nothing>()
    object Offline : TeamResult<Nothing>()
}

class TeamRepository(private val api: ListManagerApi = RetrofitClient.api) {

    suspend fun createTeam(name: String): TeamResult<TeamDTO> =
        call { api.createTeam(TeamCreateRequest(name)) }

    suspend fun getMyTeams(): TeamResult<List<TeamDTO>> =
        call { api.getMyTeams() }

    suspend fun createInvite(teamId: Long): TeamResult<InviteDTO> =
        call { api.createInvite(teamId) }

    suspend fun acceptInvite(code: String): TeamResult<TeamMemberDTO> =
        call(badRequestMessage = "Invite is invalid, expired, or already used") {
            api.acceptInvite(code.trim())
        }

    suspend fun getMembers(teamId: Long): TeamResult<List<TeamMemberDTO>> =
        call { api.getTeamMembers(teamId) }

    /** Works for both "remove member" (admin) and "leave team" (self). */
    suspend fun removeMember(teamId: Long, userId: Long): TeamResult<Unit> =
        call { api.removeTeamMember(teamId, userId) }

    private suspend fun <T> call(
        badRequestMessage: String? = null,
        block: suspend () -> Response<T>,
    ): TeamResult<T> {
        return try {
            val response = block()
            when {
                response.isSuccessful ->
                    @Suppress("UNCHECKED_CAST")
                    TeamResult.Success(response.body() ?: Unit as T)
                response.code() == 400 || response.code() == 404 ->
                    TeamResult.Failure(badRequestMessage ?: "Request rejected (${response.code()})")
                response.code() == 403 ->
                    TeamResult.Failure("You don't have permission for this action")
                response.code() == 409 ->
                    TeamResult.Failure("You are already a member of this team")
                else ->
                    TeamResult.Failure("Server error (${response.code()})")
            }
        } catch (e: IOException) {
            TeamResult.Offline
        }
    }
}
