package com.darius.listmanager.data.workspace

import android.util.Log
import androidx.room.withTransaction
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.ProductEntity
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

    /** Refresh only when no local session intent would be overwritten. */
    private suspend fun pullItems(sessionId: Long) {
        try {
            if (!refreshCatalog()) {
                Log.w(TAG, "catalog refresh failed; keeping cached session items")
                return
            }
            SessionSnapshotStore(database).refresh(sessionId) { api.getSessionItems(sessionId) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "item pull failed; keeping cached items")
        } catch (e: Exception) {
            Log.e(TAG, "pullItems failed: ${e.javaClass.simpleName}")
        }
    }

    /** Session items reference products, which reference distributors. A fresh
     * install must cache those parents before Room can accept the item rows. */
    private suspend fun refreshCatalog(): Boolean {
        val distributorResponse = api.getDistributors()
        val productResponse = api.getProducts()
        val distributors = distributorResponse.body()
        val products = productResponse.body()
        if (!distributorResponse.isSuccessful || distributors == null ||
            !productResponse.isSuccessful || products == null
        ) {
            return false
        }
        database.withTransaction {
            database.distributorDao().insertAll(distributors.map {
                DistributorEntity(id = it.id, distributorName = it.distributor_name)
            })
            database.productDao().insertAll(products.map {
                ProductEntity(
                    id = it.id,
                    name = it.name,
                    distributorId = it.distributor_id,
                    aliases = it.aliases,
                )
            })
        }
        return true
    }

    private companion object { const val TAG = "WorkspaceSessionResolver" }
}
