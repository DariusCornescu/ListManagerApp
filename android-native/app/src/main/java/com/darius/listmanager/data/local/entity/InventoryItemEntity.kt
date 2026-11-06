package com.darius.listmanager.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row of a person's or team's shared inventory. Quantity and unit price may be null
 * when the spoken line was incomplete: the UI shows the blank cell highlighted for manual
 * entry. Price is in bani (integer minor units). productId is set when the spoken name
 * matched the catalog. itemUuid is the row's identity on every phone and on the server.
 */
@Entity(
    tableName = "inventory_items",
    indices = [Index(value = ["itemUuid"], unique = true)],
)
data class InventoryItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val productId: Long? = null,
    val quantity: Double? = null,
    val priceBani: Long? = null,
    val createdAt: Long,
    val itemUuid: String,
    /** The server's optimistic-lock version; the next edit must quote it. */
    @ColumnInfo(defaultValue = "1")
    val version: Int = 1,
    /** [UNCLAIMED] for rows from before sharing, until an account adopts them. */
    @ColumnInfo(defaultValue = "-1")
    val accountUserId: Long = UNCLAIMED,
    val teamId: Long? = null,
) {
    companion object {
        const val UNCLAIMED = -1L
    }
}
