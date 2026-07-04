package com.darius.listmanager.data.repository

/**
 * Typed result for repository write operations (insert/update/delete).
 *
 * - [Success]: server accepted the change (HTTP 2xx). Room was updated from the response.
 * - [Forbidden]: server rejected the change with 401/403. Nothing was written locally and
 *   nothing was enqueued for sync.
 * - [QueuedOffline]: no connectivity (IOException). The change was written to Room locally and a
 *   pending operation was enqueued for later sync.
 * - [Error]: any other non-2xx response. Nothing was written locally and nothing was enqueued.
 */
sealed class RepoResult {
    /** HTTP 2xx. [resourceId] carries the server id for insert operations when available. */
    data class Success(val resourceId: Long? = null) : RepoResult()
    /** HTTP 401/403 — caller is not authorized to perform this mutation. */
    object Forbidden : RepoResult()
    /** Connectivity failure — change saved locally and queued for later sync. */
    data class QueuedOffline(val resourceId: Long? = null) : RepoResult()
    /** Any other failure (non-2xx that is not 401/403). */
    data class Error(val message: String) : RepoResult()
}
