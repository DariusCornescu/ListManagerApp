package com.darius.listmanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "unknown_products")
data class UnknownProductEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val spokenText: String,
)
