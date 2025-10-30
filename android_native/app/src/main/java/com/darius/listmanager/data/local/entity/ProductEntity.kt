package com.darius.listmanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "products",
    foreignKeys = [
        ForeignKey(
            entity = DistributorEntity::class,
            parentColumns = ["id"],
            childColumns = ["distributorId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("distributorId")]
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val distributorId: Long,
    val aliases : String? = ""
)
