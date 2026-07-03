package com.darius.listmanager.util

/** Pure brute-force nearest-neighbor search over cached product vectors. */
object EmbeddingSearch {

    data class Scored(val productId: Long, val score: Float)

    fun topK(query: FloatArray, candidates: List<Pair<Long, FloatArray>>, k: Int): List<Scored> {
        return candidates
            .map { (id, vec) -> Scored(id, VectorMath.cosineSimilarity(query, vec)) }
            .sortedByDescending { it.score }
            .take(k)
    }
}
