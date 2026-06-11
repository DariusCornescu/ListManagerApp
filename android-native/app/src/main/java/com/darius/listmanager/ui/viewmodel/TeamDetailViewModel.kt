package com.darius.listmanager.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.repository.TeamRepository
import com.darius.listmanager.data.repository.TeamResult
import com.darius.listmanager.data.workspace.Workspace
import com.darius.listmanager.data.workspace.WorkspaceManager
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.network.TeamMemberDTO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TeamDetailUiState(
    val members: List<TeamMemberDTO> = emptyList(),
    val myUserId: Long? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    /** Set when an invite code was generated; UI opens the share sheet. */
    val inviteCode: String? = null,
    /** Set true after leaving so the UI can navigate back. */
    val leftTeam: Boolean = false,
) {
    val myRole: String?
        get() = members.find { it.user_id == myUserId }?.role
    val amAdmin: Boolean get() = myRole == "admin"
}

class TeamDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val teamRepository = TeamRepository()
    private val workspaceManager = WorkspaceManager.getInstance(application)

    private val _uiState = MutableStateFlow(TeamDetailUiState())
    val uiState: StateFlow<TeamDetailUiState> = _uiState.asStateFlow()

    private var teamId: Long = -1

    fun load(teamId: Long) {
        this.teamId = teamId
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            // user id needed to distinguish "me" in the member list
            val me = try {
                RetrofitClient.api.getCurrentUser().body()
            } catch (e: Exception) { null }
            when (val result = teamRepository.getMembers(teamId)) {
                is TeamResult.Success -> _uiState.value = _uiState.value.copy(
                    members = result.data, myUserId = me?.id, isLoading = false
                )
                is TeamResult.Offline -> _uiState.value = _uiState.value.copy(
                    isLoading = false, error = "Team management requires a connection"
                )
                is TeamResult.Failure -> _uiState.value = _uiState.value.copy(
                    isLoading = false, error = result.message
                )
            }
        }
    }

    fun generateInvite() {
        viewModelScope.launch {
            when (val result = teamRepository.createInvite(teamId)) {
                is TeamResult.Success ->
                    _uiState.value = _uiState.value.copy(inviteCode = result.data.code)
                is TeamResult.Offline ->
                    _uiState.value = _uiState.value.copy(error = "Team management requires a connection")
                is TeamResult.Failure ->
                    _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    fun consumeInviteCode() {
        _uiState.value = _uiState.value.copy(inviteCode = null)
    }

    fun removeMember(userId: Long) {
        viewModelScope.launch {
            when (val result = teamRepository.removeMember(teamId, userId)) {
                is TeamResult.Success -> {
                    if (userId == _uiState.value.myUserId) {
                        // We left: if the app is in this team's workspace, fall back.
                        val current = workspaceManager.currentWorkspace.value
                        if ((current as? Workspace.Team)?.id == teamId) {
                            workspaceManager.fallbackToPersonal()
                        }
                        _uiState.value = _uiState.value.copy(leftTeam = true)
                    } else {
                        load(teamId)
                    }
                }
                is TeamResult.Offline ->
                    _uiState.value = _uiState.value.copy(error = "Team management requires a connection")
                is TeamResult.Failure ->
                    _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
