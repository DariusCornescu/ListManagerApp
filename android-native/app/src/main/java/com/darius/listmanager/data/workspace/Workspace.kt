package com.darius.listmanager.data.workspace

/**
 * The context the whole app operates in. [Personal] sessions belong to the
 * logged-in user; [Team] sessions are shared with all members of that team.
 */
sealed class Workspace {
    object Personal : Workspace()
    data class Team(val id: Long, val name: String) : Workspace()

    /** `team_id` to send to the backend; null means personal. */
    val teamIdOrNull: Long?
        get() = (this as? Team)?.id

    val displayName: String
        get() = when (this) {
            is Personal -> "Personal"
            is Team -> name
        }
}
