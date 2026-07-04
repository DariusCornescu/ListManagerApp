package com.darius.listmanager.data.workspace

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Minimal persistence seam so WorkspaceManager is unit-testable on the JVM. */
interface WorkspaceStore {
    fun read(): String?
    fun write(value: String?)
}

private class PrefsWorkspaceStore(context: Context) : WorkspaceStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("workspace_prefs", Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(value: String?) {
        prefs.edit().apply {
            if (value == null) remove(KEY) else putString(KEY, value)
        }.apply()
    }

    private companion object { const val KEY = "current_workspace" }
}

/**
 * Process-wide observable workspace state (same singleton pattern as
 * [com.darius.listmanager.data.repository.AuthState]). Persisted format:
 * null = Personal, "<teamId>:<teamName>" = team (name may contain ':').
 */
class WorkspaceManager(private val store: WorkspaceStore) {

    private val _currentWorkspace = MutableStateFlow(restore())
    val currentWorkspace: StateFlow<Workspace> = _currentWorkspace.asStateFlow()

    fun switchTo(workspace: Workspace) {
        _currentWorkspace.value = workspace
        store.write(
            when (workspace) {
                is Workspace.Personal -> null
                is Workspace.Team -> "${workspace.id}:${workspace.name}"
            }
        )
    }

    /** Used when the server says we lost access to the current team (404/403). */
    fun fallbackToPersonal() = switchTo(Workspace.Personal)

    private fun restore(): Workspace {
        val raw = store.read() ?: return Workspace.Personal
        val sep = raw.indexOf(':')
        if (sep <= 0) return Workspace.Personal
        val id = raw.substring(0, sep).toLongOrNull() ?: return Workspace.Personal
        return Workspace.Team(id, raw.substring(sep + 1))
    }

    companion object {
        @Volatile
        private var INSTANCE: WorkspaceManager? = null

        fun getInstance(context: Context): WorkspaceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WorkspaceManager(PrefsWorkspaceStore(context)).also { INSTANCE = it }
            }
        }
    }
}
