package com.darius.listmanager.sync

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWorkRequestTest {

    /** Why this class exists: this exact combination made every immediate sync throw. */
    @Test fun expeditedWithBatteryConstraint_isRejectedByWorkManager() {
        assertThrows(IllegalArgumentException::class.java) {
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        }
    }

    @Test fun immediateRequest_canBeBuilt_andIsExpedited() {
        val request = SyncWorker.immediateSyncRequest()
        assertTrue(request.workSpec.expedited)
    }

    @Test fun immediateRequest_waitsForNetworkOnly() {
        val constraints = SyncWorker.immediateSyncRequest().workSpec.constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresBatteryNotLow())
    }
}
