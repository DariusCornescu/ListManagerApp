package com.darius.listmanager.sync

import com.darius.listmanager.data.local.entity.PendingOperationEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveStateTest {
    private fun queued(id: Long = 1) = PendingOperationEntity(
        id = id,
        operationType = "ADD_SESSION_ITEM",
        resourceType = "SESSION_ITEM",
        resourceId = null,
        operationData = "{}",
        status = "PENDING",
        scheduledFor = 0L,
    )

    private fun held(id: Long = 2) = queued(id).copy(status = "FAILED", scheduledFor = Long.MAX_VALUE)

    /** Backoff is not a hold: the operation still retries on its own. */
    private fun backingOff(id: Long = 3) = queued(id).copy(status = "FAILED", scheduledFor = 1_000L)

    @Test fun nothingQueued_online_isSaved() {
        assertEquals(SaveState.Saved, saveStateOf(emptyList(), online = true))
    }

    @Test fun nothingQueued_offline_isStillSaved_butSaysOffline() {
        assertEquals(SaveState.SavedOffline, saveStateOf(emptyList(), online = false))
    }

    @Test fun queued_online_isSaving() {
        assertEquals(SaveState.Saving(2), saveStateOf(listOf(queued(1), queued(2)), online = true))
    }

    @Test fun queued_offline_isStoredOnThePhone() {
        assertEquals(SaveState.StoredOnPhone(1), saveStateOf(listOf(queued()), online = false))
    }

    @Test fun aHold_outranksEverything_onlineOrOffline() {
        val operations = listOf(queued(1), held(2))
        assertEquals(SaveState.NeedsAttention(1), saveStateOf(operations, online = true))
        assertEquals(SaveState.NeedsAttention(1), saveStateOf(operations, online = false))
    }

    @Test fun backoff_isNotPresentedAsNeedingADecision() {
        assertEquals(SaveState.Saving(1), saveStateOf(listOf(backingOff()), online = true))
    }
}
