package com.darius.listmanager.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.repository.TeamRepository
import com.darius.listmanager.data.repository.TeamResult
import com.darius.listmanager.data.workspace.Workspace
import com.darius.listmanager.data.workspace.WorkspaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Personal comes first and is always there. The current team stays in the list even if the team
 * list can't load, for example offline, so the screen always shows where you're working.
 */
internal fun workspaceOptions(current: Workspace, teams: List<Workspace.Team>): List<Workspace> =
    (listOf<Workspace>(Workspace.Personal) + listOfNotNull(current as? Workspace.Team) + teams).distinct()

/** Backs the workspace section of the account screen. It lives only as long as that screen. */
class WorkspaceSwitcherViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = WorkspaceManager.getInstance(application)
    private val repository = TeamRepository()
    private val _teams = MutableStateFlow<List<Workspace.Team>>(emptyList())

    val current: StateFlow<Workspace> = manager.currentWorkspace
    val teams: StateFlow<List<Workspace.Team>> = _teams.asStateFlow()

    init {
        viewModelScope.launch {
            val result = repository.getMyTeams()
            if (result is TeamResult.Success) {
                _teams.value = result.data.map { Workspace.Team(it.id, it.name) }
            }
        }
    }

    fun switchTo(workspace: Workspace) = manager.switchTo(workspace)
}
