package com.darius.listmanager.data.workspace

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.room.withTransaction
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.SessionItemEntity
import com.darius.listmanager.network.CreateSessionRequest
import com.darius.listmanager.network.ListManagerApi
import com.darius.listmanager.network.RetrofitClient
import java.io.IOException

/**
 * Resolves the SERVER active session for a workspace and mirrors it into Room
 * so every device in the team operates on the same session id.
 *
 * Online:  GET /api/session/active?team_id → (404 → POST /api/session/create)
 *          → activate locally under the server id → pull items.
 * Offline: no-op; the UI keeps using the cached local session
 *          (getOrCreateActiveSession) and ops queue as usual.
 */
class WorkspaceSessionResolver(
    private val database: AppDatabase,
    private val api: ListManagerApi = RetrofitClient.api,
) {
    sealed class ResolveResult {
        data class Resolved(val sessionId: Long) : ResolveResult()
        object Offline : ResolveResult()
        /** Server says we can't see this workspace anymore (removed from team). */
        object AccessLost : ResolveResult()
    }

    suspend fun resolve(workspace: Workspace): ResolveResult {
        val teamId = workspace.teamIdOrNull
        return try {
            var response = api.getActiveSession(teamId)

            if (response.code() == 404) {
                // No active session in this workspace yet — create one.
                response = api.createSession(
                    CreateSessionRequest(name = "Current Session", team_id = teamId)
                )
            }

            when {
                response.isSuccessful -> {
                    val dto = response.body() ?: run {
                        Log.e(TAG, "resolve: 2xx but null body")
                        return ResolveResult.Offline
                    }
                    database.sessionDao().activateServerSession(
                        serverId = dto.id, name = dto.name, teamId = teamId
                    )
                    pullItems(dto.id)
                    ResolveResult.Resolved(dto.id)
                }
                response.code() == 403 || response.code() == 404 -> ResolveResult.AccessLost
                else -> {
                    Log.e(TAG, "resolve failed: HTTP ${response.code()}")
                    ResolveResult.Offline // treat as transient; keep cached state
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            ResolveResult.Offline
        } catch (e: Exception) {
            Log.e(TAG, "resolve failed: ${e.javaClass.simpleName}")
            ResolveResult.Offline
        }
    }

    /** Replace local items with the server's (server is source of truth when online). */
    private suspend fun pullItems(sessionId: Long) {
        try {
            val response = api.getSessionItems(sessionId)
            if (response.isSuccessful) {
                val dao = database.sessionItemDao()
                database.withTransaction {
                    dao.deleteAllInSession(sessionId)
                    response.body().orEmpty().forEach { item ->
                        try {
                            dao.insert(
                                SessionItemEntity(
                                    id = item.id,
                                    sessionId = sessionId,
                                    productId = item.product_id,
                                    quantity = item.quantity,
                                )
                            )
                        } catch (e: SQLiteConstraintException) {
                            // Product not yet in local catalog — skip and keep going.
                            Log.w(TAG, "skipping item ${item.id}: product ${item.product_id} not in local catalog")
                        }
                    }
                }
            } else {
                Log.w(TAG, "getSessionItems returned HTTP ${response.code()}; keeping cached items")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "item pull failed; keeping cached items")
        } catch (e: Exception) {
            Log.e(TAG, "pullItems failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object { const val TAG = "WorkspaceSessionResolver" }
}
