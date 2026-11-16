package com.darius.listmanager.util

import com.darius.listmanager.benchmark.VoiceBenchmark
import com.darius.listmanager.data.local.entity.ProductEntity
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Latency guard for the on-device ranker. The 500 ms budget is the one the instrumented
 * Milestone3IntegrationTest sets for a full resolution; here it bounds a single rank() call
 * over a 1,000-product catalog built from the benchmark corpus, measured at the 95th
 * percentile after warm-up so one GC pause cannot fail the build.
 */
class ProductRankerLatencyTest {
    private val budgetMs = 500L
    private val catalogSize = 1_000

    @Test
    fun rankingEveryCorpusUtteranceStaysWithinBudget() {
        val corpus = VoiceBenchmark.load()
        val catalog = (0 until catalogSize).map { i ->
            val source = corpus.products[i % corpus.products.size]
            val copy = i / corpus.products.size
            ProductEntity(
                id = i + 1L,
                name = if (copy == 0) source.name else "${source.name} v$copy",
                distributorId = 1,
                aliases = source.aliases,
            )
        }
        val utterances = corpus.cases.map { it.utterance }

        repeat(3) { utterances.forEach { ProductRanker.rank(it, catalog) } }

        val timingsMs = utterances.map { utterance ->
            val start = System.nanoTime()
            ProductRanker.rank(utterance, catalog)
            (System.nanoTime() - start) / 1_000_000
        }.sorted()
        val p95 = timingsMs[((timingsMs.size - 1) * 0.95).toInt()]
        val median = timingsMs[timingsMs.size / 2]

        println("ProductRanker latency over ${catalog.size} products, ${utterances.size} utterances: median=${median}ms p95=${p95}ms max=${timingsMs.last()}ms")
        assertTrue("p95 ${p95}ms exceeds the ${budgetMs}ms budget", p95 < budgetMs)
    }
}
