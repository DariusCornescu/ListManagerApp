package com.darius.listmanager.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Pure vector helpers for embedding similarity and storage. No Android deps. */
object VectorMath {

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return dot / (sqrt(na) * sqrt(nb))
    }

    fun l2Normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        val norm = sqrt(n)
        if (norm == 0f) return v.copyOf()
        return FloatArray(v.size) { v[it] / norm }
    }

    fun floatsToBytes(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) bb.putFloat(x)
        return bb.array()
    }

    fun bytesToFloats(b: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(b.size / 4)
        for (i in out.indices) out[i] = bb.float
        return out
    }
}
