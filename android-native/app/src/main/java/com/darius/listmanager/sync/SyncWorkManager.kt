package com.darius.listmanager.sync

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.lifecycle.Observer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
object SyncWorkManager {

    fun initialize(context: Context) {
        android.util.Log.d("SyncWorkManager", "Initialized - manual sync only")
    }

    fun syncNow(context: Context) { SyncWorker.scheduleImmediateSync(context) }
    fun stop(context: Context) { SyncWorker.cancelSync(context) }
    fun observeSyncStatus(context: Context): Flow<WorkSyncStatus> {
        
    return callbackFlow {
        val workManager = WorkManager.getInstance(context)
        val observer = Observer<List<WorkInfo>> { workInfos ->
            val workInfo = workInfos.firstOrNull()
            
            val status = when (workInfo?.state) {
                WorkInfo.State.RUNNING -> WorkSyncStatus.Running
                WorkInfo.State.ENQUEUED -> WorkSyncStatus.Scheduled
                WorkInfo.State.SUCCEEDED -> WorkSyncStatus.Success
                WorkInfo.State.FAILED -> WorkSyncStatus.Failed(workInfo.outputData.getString("error"))
                WorkInfo.State.CANCELLED -> WorkSyncStatus.Cancelled
                else -> WorkSyncStatus.Idle
            }
            
            trySend(status)
        }
        
        val liveData = workManager.getWorkInfosForUniqueWorkLiveData(SyncWorker.WORK_NAME)
        liveData.observeForever(observer)
        
        awaitClose {
            liveData.removeObserver(observer)
        }
    }
}

    suspend fun getPendingCount(context: Context): Int {
        val syncService = SyncService(context)
        return syncService.syncStatus.value.pendingCount
    }
}
sealed class WorkSyncStatus {
    object Idle : WorkSyncStatus()
    object Scheduled : WorkSyncStatus()
    object Running : WorkSyncStatus()
    object Success : WorkSyncStatus()
    data class Failed(val error: String?) : WorkSyncStatus()
    object Cancelled : WorkSyncStatus()
}