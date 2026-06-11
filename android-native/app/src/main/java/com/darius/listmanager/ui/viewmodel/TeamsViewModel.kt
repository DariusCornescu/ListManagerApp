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
    /** True while a create/join call is in flight (double-submit guard). */
    val isSubmitting: Boolean = false,
    /** True when the last refresh() failed — UI shows an inline retry instead of "no teams". */
    val loadFailed: Boolean = false,
)

class TeamsViewModel(application: Application) : AndroidViewModel(application) {

    private val teamRepository = TeamRepository()
    private val workspaceManager = WorkspaceManager.getInstance(application)

    private val _uiState = MutableStateFlow(TeamsUiState())
    val uiState: StateFlow<TeamsUiState> = _uiState.asStateFlow()

    /** Prevents stacked refreshes (init + ON_RESUME both fire on first entry). */
    private var refreshInFlight = false

    init { refresh() }

    fun refresh() {
        if (refreshInFlight) return
        refreshInFlight = true
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isLoading = true, error = null, loadFailed = false
                )
                when (val result = teamRepository.getMyTeams()) {
                    is TeamResult.Success ->
                        _uiState.value = _uiState.value.copy(teams = result.data, isLoading = false)
                    is TeamResult.Offline ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            loadFailed = true,
                            error = "Team management requires a connection"
                        )
                    is TeamResult.Failure ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false, loadFailed = true, error = result.message
                        )
                }
            } finally {
                refreshInFlight = false
            }
        }
    }

    fun createTeam(name: String) {
        if (name.isBlank()) return
        if (_uiState.value.isSubmitting) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true)
            when (val result = teamRepository.createTeam(name.trim())) {
                is TeamResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        justJoinedTeam = result.data, isSubmitting = false
                    )
                    refresh()
                }
                is TeamResult.Offline ->
                    _uiState.value = _uiState.value.copy(
                        error = "Team management requires a connection", isSubmitting = false
                    )
                is TeamResult.Failure ->
                    _uiState.value = _uiState.value.copy(
                        error = result.message, isSubmitting = false
                    )
            }
        }
    }

    fun joinTeam(code: String) {
        if (code.isBlank()) return
        if (_uiState.value.isSubmitting) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true)
            when (val result = teamRepository.acceptInvite(code)) {
                is TeamResult.Success -> {
                    // Membership gives team_id; resolve the full team from the refreshed list.
                    when (val teams = teamRepository.getMyTeams()) {
                        is TeamResult.Success -> {
                            val joined = teams.data.find { it.id == result.data.team_id }
                            _uiState.value = _uiState.value.copy(
                                teams = teams.data,
                                isLoading = false,
                                justJoinedTeam = joined,
                                isSubmitting = false
                            )
                        }
                        else -> {
                            _uiState.value = _uiState.value.copy(isSubmitting = false)
                            refresh()
                        }
                    }
                }
                is TeamResult.Offline ->
                    _uiState.value = _uiState.value.copy(
                        error = "Team management requires a connection", isSubmitting = false
                    )
                is TeamResult.Failure ->
                    _uiState.value = _uiState.value.copy(
                        error = result.message, isSubmitting = false
                    )
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
