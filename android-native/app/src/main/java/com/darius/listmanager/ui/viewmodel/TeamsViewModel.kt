package com.darius.listmanager.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.repository.TeamRepository
import com.darius.listmanager.data.repository.TeamResult
import com.darius.listmanager.data.workspace.Workspace
import com.darius.listmanager.data.workspace.WorkspaceManager
import com.darius.listmanager.network.TeamDTO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TeamsUiState(
    val teams: List<TeamDTO> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    /** Set after a successful join/create so the UI can offer switching. */
    val justJoinedTeam: TeamDTO? = null,
)

class TeamsViewModel(application: Application) : AndroidViewModel(application) {

    private val teamRepository = TeamRepository()
    private val workspaceManager = WorkspaceManager.getInstance(application)

    private val _uiState = MutableStateFlow(TeamsUiState())
    val uiState: StateFlow<TeamsUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = teamRepository.getMyTeams()) {
                is TeamResult.Success ->
                    _uiState.value = _uiState.value.copy(teams = result.data, isLoading = false)
                is TeamResult.Offline ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false, error = "Team management requires a connection"
                    )
                is TeamResult.Failure ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
            }
        }
    }

    fun createTeam(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            when (val result = teamRepository.createTeam(name.trim())) {
                is TeamResult.Success -> {
                    _uiState.value = _uiState.value.copy(justJoinedTeam = result.data)
                    refresh()
                }
                is TeamResult.Offline ->
                    _uiState.value = _uiState.value.copy(error = "Team management requires a connection")
                is TeamResult.Failure ->
                    _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    fun joinTeam(code: String) {
        if (code.isBlank()) return
        viewModelScope.launch {
            when (val result = teamRepository.acceptInvite(code)) {
                is TeamResult.Success -> {
                    // Membership gives team_id; resolve the full team from the refreshed list.
                    when (val teams = teamRepository.getMyTeams()) {
                        is TeamResult.Success -> {
                            val joined = teams.data.find { it.id == result.data.team_id }
                            _uiState.value = _uiState.value.copy(
                                teams = teams.data, isLoading = false, justJoinedTeam = joined
                            )
                        }
                        else -> refresh()
                    }
                }
                is TeamResult.Offline ->
                    _uiState.value = _uiState.value.copy(error = "Team management requires a connection")
                is TeamResult.Failure ->
                    _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    fun switchToTeam(team: TeamDTO) {
        workspaceManager.switchTo(Workspace.Team(team.id, team.name))
        consumeJustJoined()
    }

    fun consumeJustJoined() {
        _uiState.value = _uiState.value.copy(justJoinedTeam = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
