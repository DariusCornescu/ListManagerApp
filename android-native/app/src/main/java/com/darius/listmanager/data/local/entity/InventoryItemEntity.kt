package com.darius.listmanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row of the single active inventory list (local-only, v1). Quantity and
 * unit price may be null when the spoken line was incomplete — the UI shows
 * the blank cell highlighted for manual entry. Price is in bani (integer
 * minor units). productId is set when the spoken name matched the catalog.
 */
@Entity(tableName = "inventory_items")
data class InventoryItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val productId: Long? = null,
    val quantity: Double? = null,
    val priceBani: Long? = null,
    val createdAt: Long
)
