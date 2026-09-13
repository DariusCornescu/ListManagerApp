package com.darius.listmanager.data.local.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "Session",
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    /** Owning team, or null for the user's personal workspace. */
    val teamId: Long? = null,
    /** Personal-cache owner. Team rows use 0; legacy rows use -1 (quarantined). */
    @ColumnInfo(defaultValue = "-1")
    val accountUserId: Long = LEGACY_ACCOUNT_ID,
) {
    companion object {
        const val LEGACY_ACCOUNT_ID = -1L
        const val GUEST_ACCOUNT_ID = 0L
        const val TEAM_SHARED_ACCOUNT_ID = 0L
    }
}
