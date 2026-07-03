package com.darius.listmanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Cached embedding vector for a product. One row per product per model version. */
@Entity(
    tableName = "product_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ProductEmbeddingEntity(
    @PrimaryKey
    val productId: Long,
    val vector: ByteArray,
    val modelVersion: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProductEmbeddingEntity) return false
        return productId == other.productId &&
            vector.contentEquals(other.vector) &&
            modelVersion == other.modelVersion
    }

    override fun hashCode(): Int {
        var result = productId.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + modelVersion.hashCode()
        return result
    }
}
