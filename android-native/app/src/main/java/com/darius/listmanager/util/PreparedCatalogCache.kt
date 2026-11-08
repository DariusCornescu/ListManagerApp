package com.darius.listmanager.util

import com.darius.listmanager.data.local.entity.ProductEntity

/**
 * Keeps the last prepared catalog for the whole process, so dictation does not normalize
 * every product again on each phrase. It is rebuilt only when the products differ from the
 * ones it was built from, which a list comparison checks in a few milliseconds.
 */
object PreparedCatalogCache {
    private class Entry(val source: List<ProductEntity>, val catalog: PreparedCatalog)

    @Volatile
    private var entry: Entry? = null

    fun get(products: List<ProductEntity>): PreparedCatalog {
        entry?.let { if (it.source == products) return it.catalog }
        // Dictation segments against this catalog through its word index.
        val catalog = ProductRanker.prepare(products).also { it.indexWords() }
        entry = Entry(products, catalog)
        return catalog
    }

    /** Forget the cached catalog. For tests. */
    internal fun clear() {
        entry = null
    }
}
