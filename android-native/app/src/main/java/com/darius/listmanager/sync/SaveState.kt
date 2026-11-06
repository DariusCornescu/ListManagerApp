package com.darius.listmanager.sync

import com.darius.listmanager.data.local.entity.PendingOperationEntity

/** The one answer to "is my last change saved?". Every state is safe on the phone. */
sealed interface SaveState {
    /** Everything has reached the server. */
    data object Saved : SaveState

    /** Nothing is waiting, and there is no connection right now. */
    data object SavedOffline : SaveState

    /** Connected and on its way to the server. */
    data class Saving(val count: Int) : SaveState

    /** No connection; kept on the phone and sent automatically later. */
    data class StoredOnPhone(val count: Int) : SaveState

    /** Waiting for the user: nothing further syncs until this is resolved. */
    data class NeedsAttention(val count: Int) : SaveState
}

/** A hold outranks everything, because it blocks the whole queue. */
fun saveStateOf(unfinished: List<PendingOperationEntity>, online: Boolean): SaveState {
    val held = unfinished.count { it.isHeldForReview }
    return when {
        held > 0 -> SaveState.NeedsAttention(held)
        unfinished.isEmpty() -> if (online) SaveState.Saved else SaveState.SavedOffline
        online -> SaveState.Saving(unfinished.size)
        else -> SaveState.StoredOnPhone(unfinished.size)
    }
}
