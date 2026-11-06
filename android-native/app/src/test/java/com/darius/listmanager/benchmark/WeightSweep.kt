package com.darius.listmanager.benchmark

import com.darius.listmanager.util.ScoringWeights

data class WeightCandidate(
    val label: String,
    val weights: ScoringWeights,
)

data class WeightSweepResult(
    val candidate: WeightCandidate,
    val metrics: VoiceBenchmarkMetrics,
)

object WeightSweep {
    private val ranking =
        compareByDescending<WeightSweepResult> { it.metrics.top1 }
            .thenByDescending { it.metrics.mrr }
            .thenByDescending { it.metrics.top3 }
            .thenBy { it.candidate.label }

    fun oneAtATimeCandidates(
        baseline: ScoringWeights = ScoringWeights(),
        factors: List<Double> = listOf(0.75, 1.25),
    ): List<WeightCandidate> {
        require(factors.isNotEmpty()) { "At least one sweep factor is required" }
        require(factors.all { it > 0.0 }) { "Sweep factors must be positive" }

        val candidates = mutableListOf(WeightCandidate("baseline", baseline))
        factors.forEach { factor ->
            candidates += WeightCandidate(
                "levenshtein:$factor",
                baseline.copy(levenshtein = baseline.levenshtein * factor),
            )
            candidates += WeightCandidate(
                "jaccard:$factor",
                baseline.copy(jaccard = baseline.jaccard * factor),
            )
            candidates += WeightCandidate(
                "token:$factor",
                baseline.copy(token = baseline.token * factor),
            )
            candidates += WeightCandidate(
                "phonetic:$factor",
                baseline.copy(phonetic = baseline.phonetic * factor),
            )
            candidates += WeightCandidate(
                "prefix:$factor",
                baseline.copy(prefix = baseline.prefix * factor),
            )
            candidates += WeightCandidate(
                "contains:$factor",
                baseline.copy(containsBonus = baseline.containsBonus * factor),
            )
            candidates += WeightCandidate(
                "numberSeq:$factor",
                baseline.copy(numberSeq = baseline.numberSeq * factor),
            )
            candidates += WeightCandidate(
                "embedding:$factor",
                baseline.copy(embedding = baseline.embedding * factor),
            )
        }
        return candidates.distinctBy { it.weights }
    }

    fun run(
        corpus: VoiceBenchmarkCorpus,
        candidates: List<WeightCandidate> = oneAtATimeCandidates(),
    ): List<WeightSweepResult> {
        require(candidates.isNotEmpty()) { "At least one weight candidate is required" }
        return candidates
            .map { candidate ->
                WeightSweepResult(candidate, VoiceBenchmark.evaluate(corpus, candidate.weights))
            }
            .sortedWith(ranking)
    }
}
