package com.darius.listmanager.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.darius.listmanager.data.model.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker( context: Context, params: WorkerParameters ) : CoroutineWorker(context, params) {

    private val syncService = SyncService(context)

    companion object {
        const val TAG = "SyncWorker"
        const val WORK_NAME = "sync_pending_operations"
        
        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) 
            .setRequiresBatteryNotLow(true)
            .build() 
        fun schedulePeriodicSync(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                repeatInterval = 15,
                repeatIntervalTimeUnit = TimeUnit.MINUTES,
                flexTimeInterval = 5,
                flexTimeIntervalUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, 
                workRequest
            )

            Log.d(TAG, "Periodic sync scheduled")
        }

        fun scheduleImmediateSync(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_immediate",
                ExistingWorkPolicy.REPLACE, // Replace existing immediate work
                workRequest
            )

            Log.d(TAG, "Immediate sync scheduled")
        }

        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Sync work cancelled")
        }

        @Suppress("RestrictedApi")
        suspend fun isSyncScheduled(context: Context): Boolean {
            val workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME)
                .get()  // Use get() instead of await() to avoid RestrictedApi lint error
            
            return workInfos.any { it.state != WorkInfo.State.CANCELLED }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "Starting sync work...")
        Log.d(TAG, "Attempt: $runAttemptCount")

        // Note: Catalog endpoints are now public, so we can sync without login
        // Session endpoints still require auth

        try {
            // Sync pending operations
            Log.d(TAG, "Calling syncPendingOperations()...")
            val result = syncService.syncPendingOperations()
            Log.d(TAG, "Sync result: $result")

            when (result) {
                is SyncResult.Success -> {
                    Log.d(TAG, "Sync completed successfully")
                    Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Result.success()
                }
                is SyncResult.Error -> {
                    if (result.retryable) {
                        Log.w(TAG, "Sync failed (retryable): ${result.message}")
                        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        Result.retry()
                    } else {
                        Log.e(TAG, "Sync failed (non-retryable): ${result.message}")
                        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        Result.failure()
                    }
                }
                is SyncResult.Conflict -> {
                    Log.w(TAG, "Sync conflict: ${result.message}")
                    Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Result.retry()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception during sync: ${e.javaClass.simpleName}", e)
            Log.e(TAG, "Message: ${e.message}")
            Log.e(TAG, "Stack trace: ", e)
            
            // Retry on exception
            if (runAttemptCount < 3) {
                Log.d(TAG, "Retrying... (attempt ${runAttemptCount + 1})")
                Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Result.retry()
            } else {
                Log.e(TAG, "Max retry attempts reached")
                Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Result.failure()
            }
        }
    }
}