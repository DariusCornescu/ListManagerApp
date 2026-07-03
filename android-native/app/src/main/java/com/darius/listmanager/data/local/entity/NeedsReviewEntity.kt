package com.darius.listmanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A spoken utterance that matched the catalog with only medium confidence
 * (0.60–0.82) during a live listening session. Persisted so the user can
 * resolve it later without breaking the hands-free flow. Kept separate from
 * `unknown_products`: here a good candidate exists and the user picks it, vs.
 * unknown where a brand-new product must be named.
 */
@Entity(tableName = "needs_review")
data class NeedsReviewEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val spokenText: String,
    val sessionId: Long,
    val createdAt: Long
)
