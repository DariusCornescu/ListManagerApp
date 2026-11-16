package com.darius.listmanager.benchmark

import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.util.ProductRanker
import com.darius.listmanager.util.ScoringWeights
import com.darius.listmanager.util.TextNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * EX-39 and EX-40 on a seeded synthetic ASR-noise corpus built from the seed catalog.
 *
 * Synthetic: the noise is a model of recognizer mistakes, not recorded mistakes, so these
 * numbers can show that a change is harmful or pointless, but cannot justify shipping new
 * weights. Re-run against the real corpus once EX-19 has collected it.
 */
class RankerExperimentsTest {
    private val catalog = listOf(
        "Lapte Zuzu 1L" to "lapte, zuzu",
        "Pâine albă 500g" to "paine, franzela",
        "Unt President 200g" to "unt",
        "Ouă M 10buc" to "oua, ou",
        "Cașcaval Hochland 250g" to "cascaval",
        "Apă Borsec 2L" to "apa, borsec",
        "Coca Cola 2L" to "cola, coca",
        "Suc Fanta 1.5L" to "fanta, suc portocale",
        "Bere Ursus 0.5L" to "bere, ursus",
        "Vin Cotnari 0.75L" to "vin, cotnari",
        "Șuruburi 4x40mm 100buc" to "suruburi, surub",
        "Cheie reglabilă 200mm" to "cheie, scule",
        "Bandă izolantă neagră" to "banda, izolanta",
        "Ciocan 500g" to "ciocan",
        "Șurubelniță PH2" to "surubelnita",
        "Pungi plastic 100buc" to "pungi, plastic",
        "Folie stretch 500m" to "folie, stretch",
        "Cutii carton 40x30x20" to "cutii, carton",
        "Lapte Napolact 1L" to "lapte napolact",
        "Lapte Pilos 1.5%" to "pilos",
        "Apă Dorna 2L" to "dorna",
        "Apă Bucovina 0.5L" to "bucovina",
        "Ulei Floriol 1L" to "ulei, floriol",
        "Zahăr Mărgăritar 1kg" to "zahar",
        "Făină Băneasa 1kg" to "faina",
    ).mapIndexed { index, (name, aliases) ->
        ProductEntity(id = index + 1L, name = name, distributorId = 1, aliases = aliases)
    }

    /** Spoken forms a shopper would actually say: the brand and type, not the pack size. */
    private fun spokenForm(product: ProductEntity): String =
        TextNorm.normalize(product.name)
            .split(' ')
            .filterNot { token -> token.any(Char::isDigit) }
            .joinToString(" ")
            .ifBlank { TextNorm.normalize(product.name) }

    private val confusions = listOf(
        "ch" to "c", "ce" to "che", "ci" to "chi", "ge" to "ghe", "gi" to "ghi",
        "ea" to "a", "oa" to "o", "ia" to "a", "z" to "s", "v" to "f",
        "t" to "d", "b" to "p", "ll" to "l", "nn" to "n",
    )

    private fun garble(text: String, random: Random, strength: Int): String {
        var result = text
        repeat(strength) {
            result = when (random.nextInt(5)) {
                0 -> {
                    val applicable = confusions.filter { (from, _) -> from in result }
                    if (applicable.isEmpty()) result else {
                        val (from, to) = applicable.random(random)
                        result.replaceFirst(from, to)
                    }
                }
                1 -> if (result.length > 3) result.dropLast(1) else result
                2 -> {
                    val at = random.nextInt(result.length)
                    result.substring(0, at + 1) + result[at] + result.substring(at + 1)
                }
                3 -> {
                    val spaceless = result.indices.filter { it in 2 until result.length - 2 && result[it] != ' ' && result[it - 1] != ' ' }
                    if (spaceless.isEmpty()) result else {
                        val at = spaceless.random(random)
                        result.substring(0, at) + " " + result.substring(at)
                    }
                }
                else -> {
                    if (result.length < 3) result else {
                        val at = random.nextInt(result.length - 1)
                        result.substring(0, at) + result[at + 1] + result[at] + result.substring(at + 2)
                    }
                }
            }
        }
        return result
    }

    private data class Case(val hypotheses: List<String>, val expectedId: Long?)

    private fun corpus(seed: Int, perProduct: Int = 40): List<Case> {
        val random = Random(seed)
        val known = catalog.flatMap { product ->
            List(perProduct) {
                val base = spokenForm(product)
                // The recognizer's best guess is not always its most accurate one.
                val hypotheses = List(MAX_DEPTH) { garble(base, random, strength = 1 + random.nextInt(4)) }
                Case(hypotheses, product.id)
            }
        }
        // Things said in a shop that are not in the catalog at all.
        val notInCatalog = listOf(
            "mere golden", "detergent ariel", "hartie igienica", "salam sasesc", "cafea jacobs",
            "ciocolata milka", "orez basmati", "sare iodata", "otet alb", "branza telemea",
        ).flatMap { phrase ->
            List(perProduct / 4) {
                Case(List(MAX_DEPTH) { garble(phrase, random, strength = random.nextInt(3)) }, null)
            }
        }
        return known + notInCatalog
    }

    private data class Result(
        val top1: Double,
        val autoAddPrecision: Double,
        val autoAddRate: Double,
        val falseAutoAdds: Int,
        /** Catalog products whose best score fell below the Suggestions band (resolved Unknown). */
        val knownToUnknown: Int,
        /** Phrases not in the catalog that still reached the Suggestions band or above. */
        val falseSuggestions: Int,
    ) {
        override fun toString() =
            "top1=${"%.4f".format(top1)} autoAddPrecision=${"%.4f".format(autoAddPrecision)} " +
                "autoAddRate=${"%.4f".format(autoAddRate)} falseAutoAdds=$falseAutoAdds " +
                "knownToUnknown=$knownToUnknown falseSuggestions=$falseSuggestions"
    }

    private fun evaluate(cases: List<Case>, depth: Int, weights: ScoringWeights): Result {
        var hits = 0
        var known = 0
        var autoAdds = 0
        var correctAutoAdds = 0
        var knownToUnknown = 0
        var falseSuggestions = 0
        cases.forEach { case ->
            val best = ProductRanker
                .rankAcross(case.hypotheses.take(depth), catalog, weights = weights)
                .firstOrNull()
            if (case.expectedId != null) {
                known++
                if (best?.product?.id == case.expectedId) hits++
                if (best == null || best.score < SUGGESTIONS_THRESHOLD) knownToUnknown++
            } else if (best != null && best.score >= SUGGESTIONS_THRESHOLD) {
                falseSuggestions++
            }
            if (best != null && best.score >= AUTO_ADD_THRESHOLD) {
                autoAdds++
                if (best.product.id == case.expectedId) correctAutoAdds++
            }
        }
        return Result(
            top1 = hits.toDouble() / known,
            autoAddPrecision = if (autoAdds == 0) 1.0 else correctAutoAdds.toDouble() / autoAdds,
            autoAddRate = autoAdds.toDouble() / cases.size,
            falseAutoAdds = autoAdds - correctAutoAdds,
            knownToUnknown = knownToUnknown,
            falseSuggestions = falseSuggestions,
        )
    }

    /** EX-40: 5 is what AndroidSpeechProvider asks for today. */
    @Test fun sweepNBestDepth() {
        val cases = corpus(SEED)
        (1..MAX_DEPTH).forEach { depth ->
            println("VOICE_NBEST depth=$depth ${evaluate(cases, depth, ScoringWeights())}")
        }
    }

    /** EX-39: phonetic weight only, everything else at production values. */
    @Test fun sweepPhoneticWeight() {
        val cases = corpus(SEED)
        listOf(0.0, 0.4, 0.8, 1.2, 1.6, 2.4).forEach { phonetic ->
            val result = evaluate(cases, depth = MAX_DEPTH, ScoringWeights(phonetic = phonetic))
            println("VOICE_PHONETIC weight=$phonetic $result")
        }
    }

    /**
     * The part of EX-23's regression gate that needs no real data: production settings must
     * not get worse on this corpus. The floors are today's measured values; a real accuracy
     * target still waits for the real corpus (EX-19, EX-21).
     */
    @Test fun productionSettings_holdTheSyntheticFloor() {
        val result = evaluate(corpus(SEED), depth = MAX_DEPTH, ScoringWeights())
        assertTrue("Top-1 fell: $result", result.top1 >= 0.955)
        assertEquals("A wrong product was auto-added: $result", 0, result.falseAutoAdds)
        // Fuzzy token credit (2026-11-08) took this from 368 to 322 of 1,000.
        assertTrue("More catalog products resolve to Unknown: $result", result.knownToUnknown <= 322)
        assertEquals("A phrase not in the catalog reached Suggestions: $result", 0, result.falseSuggestions)
    }

    @Test fun corpusIsReproducible() {
        assertEquals(corpus(SEED), corpus(SEED))
    }

    private companion object {
        const val SEED = 240513
        const val MAX_DEPTH = 5
        const val AUTO_ADD_THRESHOLD = 0.82 // ResolveSpokenProductUseCase
        const val SUGGESTIONS_THRESHOLD = 0.60 // ResolveSpokenProductUseCase
    }
}
