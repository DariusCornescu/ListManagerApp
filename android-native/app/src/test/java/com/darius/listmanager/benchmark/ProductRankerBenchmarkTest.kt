package com.darius.listmanager.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductRankerBenchmarkTest {
    @Test
    fun corpusFormatLoadsAndValidates() {
        val corpus = VoiceBenchmark.load()

        assertEquals(1, corpus.schemaVersion)
        assertEquals("synthetic-text-only", corpus.privacyMode)
        assertEquals(4, corpus.products.size)
        assertEquals(5, corpus.cases.size)
        assertTrue(corpus.cases.all { it.locale == "ro-RO" })
        assertEquals(setOf("clean", "background"), corpus.cases.map { it.noiseCondition }.toSet())
    }

    @Test
    fun reportsTop1Top3AndMeanReciprocalRank() {
        val metrics = VoiceBenchmark.evaluate(VoiceBenchmark.load())

        println(
            "VOICE_BENCHMARK total=${metrics.total} " +
                "top1=${"%.4f".format(metrics.top1)} " +
                "top3=${"%.4f".format(metrics.top3)} " +
                "mrr=${"%.4f".format(metrics.mrr)}"
        )
        assertEquals(5, metrics.total)
        assertTrue(metrics.top1 in 0.0..1.0)
        assertTrue(metrics.top3 in metrics.top1..1.0)
        assertTrue(metrics.mrr in 0.0..1.0)
    }
}
