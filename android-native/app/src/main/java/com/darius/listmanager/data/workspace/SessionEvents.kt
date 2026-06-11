package com.darius.listmanager.data.workspace

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Process-wide one-shot signals that the active session changed server-side
 * (completed or replaced) and the session screen should re-resolve.
 */
object SessionEvents {
    val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun requestRefresh() {
        refreshRequests.tryEmit(Unit)
    }
}
