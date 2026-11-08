package com.darius.listmanager.benchmark

import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.usecase.ResolveSpokenProductUseCase
import com.darius.listmanager.util.ProductRanker
import com.darius.listmanager.util.TextNorm
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Segmentation against a real catalog kept outside the repository, which is public.
 * Skipped unless `LISTMANAGER_CATALOG_JSON` names a JSON array of products
 * (`id`, `name`, `distributorId`, `aliases`), for example exported from the app's database.
 *
 * Prints how often scoring only the candidates of each word window cuts an utterance
 * differently from scoring the whole catalog, and how long each takes.
 */
class LocalCatalogSegmentationBenchmarkTest {
    @Serializable
    private data class Row(val id: Long, val name: String, val distributorId: Long, val aliases: String? = null)

    private fun garble(word: String, random: Random): String {
        if (word.length < 4 || random.nextInt(3) != 0) return word
        val at = random.nextInt(word.length - 1)
        return when (random.nextInt(3)) {
            0 -> word.removeRange(at, at + 1)
            1 -> word.substring(0, at) + word[at + 1] + word[at] + word.substring(at + 2)
            else -> word.substring(0, at) + "e" + word.substring(at + 1)
        }
    }

    @Test
    fun candidateScoringAgainstTheWholeCatalog() {
        val path = System.getenv("LISTMANAGER_CATALOG_JSON")
        assumeTrue("LISTMANAGER_CATALOG_JSON is not set", path != null && File(path).exists())
        val products = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<Row>>(File(path!!).readText())
            .map { ProductEntity(it.id, it.name, it.distributorId, it.aliases) }
        val catalog = ProductRanker.prepare(products).also { it.indexWords() }

        // Two to four products, each said as the first one to three words of its name.
        val random = Random(SEED)
        val utterances = List(COUNT) {
            List(2 + random.nextInt(3)) {
                TextNorm.normalize(products.random(random).name).split(" ")
                    .filter { word -> word.length > 1 && word.any(Char::isLetter) }
                    .take(1 + random.nextInt(3))
                    .joinToString(" ") { garble(it, random) }
            }.joinToString(" ")
        }

        var started = System.nanoTime()
        val whole = utterances.map { ResolveSpokenProductUseCase.segment(it, catalog, candidatesOnly = false) }
        val wholeMs = (System.nanoTime() - started) / 1_000_000
        started = System.nanoTime()
        val candidates = utterances.map { ResolveSpokenProductUseCase.segment(it, catalog, candidatesOnly = true) }
        val candidatesMs = (System.nanoTime() - started) / 1_000_000

        val different = utterances.indices.filter { whole[it] != candidates[it] }
        println(
            "VOICE_SEGMENT_PREFILTER products=${products.size} utterances=${utterances.size} " +
                "same=${utterances.size - different.size} wholeCatalogMs=$wholeMs candidatesMs=$candidatesMs",
        )
        // Printed only locally: these are the real catalog's product names.
        different.forEach { println("VOICE_SEGMENT_PREFILTER_DIFF ${whole[it]} vs ${candidates[it]}") }
    }

    private companion object {
        const val SEED = 7
        const val COUNT = 400
    }
}
