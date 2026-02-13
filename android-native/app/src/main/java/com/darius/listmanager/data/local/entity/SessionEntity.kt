package com.darius.listmanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "Session",
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
)
