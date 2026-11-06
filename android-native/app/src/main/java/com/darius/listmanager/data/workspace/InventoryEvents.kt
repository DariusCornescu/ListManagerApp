package com.darius.listmanager.data.workspace

import kotlinx.coroutines.flow.MutableSharedFlow

/** A one-shot signal that the shared inventory changed and should be re-read. */
object InventoryEvents {
    val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun requestRefresh() {
        refreshRequests.tryEmit(Unit)
    }
}
