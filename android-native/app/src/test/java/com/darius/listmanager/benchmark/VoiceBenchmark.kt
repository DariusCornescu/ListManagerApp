package com.darius.listmanager.benchmark

import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.util.ProductRanker
import com.darius.listmanager.util.ScoringWeights
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BenchmarkProduct(
    val id: Long,
    val name: String,
    val aliases: String = "",
)

@Serializable
data class BenchmarkCase(
    val id: String,
    val utterance: String,
    @SerialName("expected_product_id") val expectedProductId: Long,
    val locale: String,
    @SerialName("noise_condition") val noiseCondition: String,
)

@Serializable
data class VoiceBenchmarkCorpus(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("privacy_mode") val privacyMode: String,
    val products: List<BenchmarkProduct>,
    val cases: List<BenchmarkCase>,
) {
    fun validate() {
        require(schemaVersion == 1) { "Unsupported benchmark schema: $schemaVersion" }
        require(privacyMode.isNotBlank()) { "privacy_mode is required" }
        require(products.isNotEmpty()) { "Benchmark catalog is empty" }
        require(cases.isNotEmpty()) { "Benchmark cases are empty" }
        require(products.map { it.id }.distinct().size == products.size) {
            "Benchmark product IDs must be unique"
        }
        val productIds = products.mapTo(mutableSetOf()) { it.id }
        require(cases.map { it.id }.distinct().size == cases.size) {
            "Benchmark case IDs must be unique"
        }
        cases.forEach { case ->
            require(case.utterance.isNotBlank()) { "${case.id}: utterance is empty" }
            require(case.expectedProductId in productIds) {
                "${case.id}: unknown expected product ${case.expectedProductId}"
            }
            require(case.locale.isNotBlank()) { "${case.id}: locale is empty" }
            require(case.noiseCondition.isNotBlank()) {
                "${case.id}: noise_condition is empty"
            }
        }
    }
}

data class VoiceBenchmarkMetrics(
    val total: Int,
    val top1Hits: Int,
    val top3Hits: Int,
    val reciprocalRankSum: Double,
) {
    val top1: Double get() = top1Hits.toDouble() / total
    val top3: Double get() = top3Hits.toDouble() / total
    val mrr: Double get() = reciprocalRankSum / total
}

object VoiceBenchmark {
    private val json = Json { ignoreUnknownKeys = false }

    fun load(resourcePath: String = "voice-benchmark/corpus.json"): VoiceBenchmarkCorpus {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(resourcePath)) {
            "Benchmark resource not found: $resourcePath"
        }
        val corpus = stream.bufferedReader().use { reader ->
            json.decodeFromString<VoiceBenchmarkCorpus>(reader.readText())
        }
        corpus.validate()
        return corpus
    }

    fun evaluate(
        corpus: VoiceBenchmarkCorpus,
        weights: ScoringWeights = ScoringWeights(),
    ): VoiceBenchmarkMetrics {
        corpus.validate()
        val products = corpus.products.map { product ->
            ProductEntity(
                id = product.id,
                name = product.name,
                distributorId = 1,
                aliases = product.aliases,
            )
        }
        var top1Hits = 0
        var top3Hits = 0
        var reciprocalRankSum = 0.0
        corpus.cases.forEach { case ->
            val rankedIds = ProductRanker.rank(
                spokenText = case.utterance,
                products = products,
                weights = weights,
            ).map { it.product.id }
            val rank = rankedIds.indexOf(case.expectedProductId).takeIf { it >= 0 }?.plus(1)
            if (rank == 1) top1Hits += 1
            if (rank != null && rank <= 3) top3Hits += 1
            if (rank != null) reciprocalRankSum += 1.0 / rank
        }
        return VoiceBenchmarkMetrics(
            total = corpus.cases.size,
            top1Hits = top1Hits,
            top3Hits = top3Hits,
            reciprocalRankSum = reciprocalRankSum,
        )
    }
}
