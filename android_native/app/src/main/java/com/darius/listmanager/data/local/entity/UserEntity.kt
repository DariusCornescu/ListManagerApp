package com.darius.listmanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
class UserEntity (
    @PrimaryKey
    val id: Long = 1, // always 1 - only one local user

    val username: String,
    val email: String,

    val jwtToken: String? = null,
    val tokenExpiresAt: Long? = null,

    val lastSyncedAt: Long? = null,
    val isLoggedIn: Boolean = false,

    val createdAt: Long = System.currentTimeMillis()
)