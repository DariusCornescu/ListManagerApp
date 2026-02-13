package com.darius.listmanager.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = ProductEntity::class)
@Entity(tableName = "products_fts")
data class ProductFts(
    val name: String,
    val aliases : String
)
