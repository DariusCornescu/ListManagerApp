package com.darius.listmanager.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "distributors",
    indices = [Index(value = ["distributorName"], unique = true)]
)
data class DistributorEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val distributorName: String
)
