package com.darius.listmanager.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.model.OperationData
import com.darius.listmanager.data.repository.toEntity
import com.darius.listmanager.network.ListManagerApi
import com.darius.listmanager.network.RetrofitClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** The last catalog versions this phone holds, so an unchanged catalog costs one small request. */
interface CatalogEtagStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class InMemoryCatalogEtagStore : CatalogEtagStore {
    private val values = ConcurrentHashMap<String, String>()
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private class PrefsCatalogEtagStore(context: Context) : CatalogEtagStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("catalog_sync", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }
}

object CatalogEtags {
    @Volatile
    var store: CatalogEtagStore = InMemoryCatalogEtagStore()
        private set

    fun initialize(context: Context) {
        store = PrefsCatalogEtagStore(context)
    }
}

/**
 * Brings the local catalog up to date with the server, without touching open work.
 *
 * Replaces nothing wholesale: only rows that differ are written, because every upsert also
 * rewrites its search index entry and re-emits flows that join products. Rows with unfinished
 * offline work are skipped and retried later, so a refresh can't undo them.
 */
class CatalogRefresher(
    private val database: AppDatabase,
    private val api: ListManagerApi = RetrofitClient.api,
    private val etags: CatalogEtagStore = CatalogEtags.store,
) {
    sealed class Result {
        object UpToDate : Result()
        data class Updated(val changedRows: Int, val skippedRows: Int) : Result()
        object Failed : Result()
    }

    private data class Fetched<T>(val rows: List<T>?, val etag: String?)
    private class FetchFailed : Exception()

    suspend fun refresh(): Result = lock.withLock {
        try {
            // An empty cache means the database was wiped, so a saved version proves nothing.
            val hasCatalog = database.productDao().count() > 0
            val knownProducts = if (hasCatalog) etags.get(KEY_PRODUCTS) else null
            val knownDistributors = if (hasCatalog) etags.get(KEY_DISTRIBUTORS) else null

            // Products first: imports only add parents, so every distributor these rows
            // need exists by the time the second request runs.
            val products = fetch(knownProducts) { api.getProductsIfChanged(it) }
            val distributors = fetch(knownDistributors) { api.getDistributorsIfChanged(it) }
            if (products.rows == null && distributors.rows == null) return@withLock Result.UpToDate

            val outcome = database.withTransaction {
                val open = openWork()
                val unusableParents = mutableSetOf<Long>()
                var changed = 0
                var skipped = 0
                distributors.rows?.let {
                    val written = applyDistributors(it, open, unusableParents)
                    changed += written.first
                    skipped += written.second
                }
                products.rows?.let {
                    val written = applyProducts(it, open, unusableParents)
                    changed += written.first
                    skipped += written.second
                }
                Result.Updated(changed, skipped)
            }

            // Skipped rows must be looked at again, so don't record this version as held.
            if (outcome.skippedRows == 0) {
                products.etag?.let { etags.put(KEY_PRODUCTS, it) }
                distributors.etag?.let { etags.put(KEY_DISTRIBUTORS, it) }
            } else {
                forgetVersions()
            }
            if (outcome.changedRows == 0 && outcome.skippedRows == 0) Result.UpToDate else outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "catalog refresh unavailable")
            Result.Failed
        } catch (e: FetchFailed) {
            Log.w(TAG, "catalog refresh rejected by the server")
            Result.Failed
        } catch (e: Exception) {
            // The transaction rolled back. Fetch everything next time.
            Log.e(TAG, "catalog refresh failed: ${e.javaClass.simpleName}")
            forgetVersions()
            Result.Failed
        }
    }

    private fun forgetVersions() {
        etags.put(KEY_PRODUCTS, null)
        etags.put(KEY_DISTRIBUTORS, null)
    }

    private suspend fun <T> fetch(
        known: String?,
        call: suspend (String?) -> retrofit2.Response<List<T>>,
    ): Fetched<T> {
        val response = call(known)
        if (response.code() == NOT_MODIFIED) return Fetched(null, known)
        val rows = response.body()
        if (!response.isSuccessful || rows == null) throw FetchFailed()
        return Fetched(rows, response.headers()["ETag"])
    }

    /**
     * Returns rows changed and rows skipped. [unusableParents] gets the server distributors
     * whose products can't be attached yet. A pending edit only holds back the distributor's
     * own row; its products are still fine.
     */
    private suspend fun applyDistributors(
        rows: List<com.darius.listmanager.network.DistributorDTO>,
        open: OpenWork,
        unusableParents: MutableSet<Long>,
    ): Pair<Int, Int> {
        val dao = database.distributorDao()
        val serverIds = rows.map { it.id }.toSet()
        var changed = 0
        var skipped = 0
        for (dto in rows) {
            val wanted: DistributorEntity = dto.toEntity()
            if (wanted.id in open.distributorIds) {
                skipped++
                // That id may be a different, locally created distributor.
                if (wanted.id in open.createdDistributorIds || dao.getById(wanted.id) == null) {
                    unusableParents += wanted.id
                }
                continue
            }
            if (dao.getById(wanted.id) == wanted) continue

            // The name is unique, so a row holding it under another id would block this one
            // on every future refresh. Clear the way, then move its products across.
            val holder = dao.getByName(wanted.distributorName)?.takeIf { it.id != wanted.id }
            if (holder != null && holder.id in open.distributorIds) {
                skipped++
                unusableParents += wanted.id
                continue
            }
            if (holder != null) {
                dao.update(holder.copy(distributorName = "${holder.distributorName}#${holder.id}"))
            }
            dao.upsert(wanted)
            if (holder != null && holder.id !in serverIds) {
                database.productDao().moveToDistributor(holder.id, wanted.id)
                dao.deleteById(holder.id)
            }
            changed++
        }
        return changed to skipped
    }

    /** Returns rows changed and rows skipped. */
    private suspend fun applyProducts(
        rows: List<com.darius.listmanager.network.ProductDTO>,
        open: OpenWork,
        unusableParents: Set<Long>,
    ): Pair<Int, Int> {
        val dao = database.productDao()
        val local = dao.getAll().associateBy { it.id }
        val wanted: List<ProductEntity> = rows.map { it.toEntity() }

        val blocked = wanted.filter { it.id in open.productIds || it.distributorId in unusableParents }
        val blockedIds = blocked.map { it.id }.toSet()
        val changed = wanted.filter { it.id !in blockedIds && local[it.id] != it }
        if (changed.isNotEmpty()) dao.insertAll(changed)

        // An empty list is far likelier a server fault than a shop with no products.
        var removed = 0
        if (wanted.isNotEmpty() && open.decoded) {
            val keep = wanted.map { it.id }.toSet() + open.productIds +
                database.sessionItemDao().getReferencedProductIds() +
                database.inventoryItemDao().getReferencedProductIds()
            for (id in local.keys - keep) {
                dao.deleteById(id)
                removed++
            }
        }
        return (changed.size + removed) to blocked.size
    }

    /** Everything unfinished offline work still refers to. */
    private class OpenWork(
        val productIds: Set<Long>,
        val distributorIds: Set<Long>,
        val createdDistributorIds: Set<Long>,
        val decoded: Boolean,
    )

    private suspend fun openWork(): OpenWork {
        val productIds = mutableSetOf<Long>()
        val distributorIds = mutableSetOf<Long>()
        val createdDistributorIds = mutableSetOf<Long>()
        var decoded = true
        for (operation in database.pendingOperationDao().getUnfinished()) {
            val data = runCatching { decodeJson.decodeFromString<OperationData>(operation.operationData) }
                .getOrNull()
            if (data == null) {
                decoded = false
                continue
            }
            collect(data, operation, productIds, distributorIds, createdDistributorIds)
        }
        return OpenWork(productIds, distributorIds, createdDistributorIds, decoded)
    }

    private fun collect(
        data: OperationData,
        operation: PendingOperationEntity,
        productIds: MutableSet<Long>,
        distributorIds: MutableSet<Long>,
        createdDistributorIds: MutableSet<Long>,
    ) {
        when (data) {
            is OperationData.CreateProduct -> operation.resourceId?.let { productIds += it }
            is OperationData.UpdateProduct -> productIds += data.productId
            is OperationData.DeleteProduct -> productIds += data.productId
            is OperationData.CreateDistributor -> operation.resourceId?.let {
                distributorIds += it
                createdDistributorIds += it
            }
            is OperationData.UpdateDistributor -> distributorIds += data.distributorId
            is OperationData.DeleteDistributor -> distributorIds += data.distributorId
            is OperationData.AddSessionItem -> productIds += data.productId
            is OperationData.AddInventoryItem -> data.productId?.let { productIds += it }
            else -> Unit
        }
    }

    companion object {
        private const val TAG = "CatalogRefresher"
        private const val NOT_MODIFIED = 304
        private const val KEY_PRODUCTS = "products_etag"
        private const val KEY_DISTRIBUTORS = "distributors_etag"
        private val decodeJson = Json { ignoreUnknownKeys = true }

        // One at a time across the process: the worker and the session screen both refresh.
        private val lock = Mutex()
    }
}
