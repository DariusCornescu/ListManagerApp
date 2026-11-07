package com.darius.listmanager.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightSweepTest {
    @Test
    fun generatesCandidatesForEveryScoringWeight() {
        val labels = WeightSweep.oneAtATimeCandidates().map { it.label }

        assertEquals(19, labels.size)
        listOf(
            "levenshtein",
            "jaccard",
            "token",
            "phonetic",
            "prefix",
            "contains",
            "numberSeq",
            "embedding",
            "size",
        ).forEach { field ->
            assertEquals(2, labels.count { it.startsWith("$field:") })
        }
    }

    @Test
    fun evaluatesAndRanksCandidatesByTop1ThenMrr() {
        val results = WeightSweep.run(VoiceBenchmark.load())

        results.take(5).forEachIndexed { index, result ->
            println(
                "VOICE_WEIGHT_SWEEP rank=${index + 1} " +
                    "candidate=${result.candidate.label} " +
                    "top1=${"%.4f".format(result.metrics.top1)} " +
                    "top3=${"%.4f".format(result.metrics.top3)} " +
                    "mrr=${"%.4f".format(result.metrics.mrr)}"
            )
        }
        assertEquals(19, results.size)
        assertTrue(
            results.zipWithNext().all { (left, right) ->
                left.metrics.top1 > right.metrics.top1 ||
                    (left.metrics.top1 == right.metrics.top1 && left.metrics.mrr >= right.metrics.mrr)
            }
        )
    }
}
