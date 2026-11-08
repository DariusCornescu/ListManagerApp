package com.darius.listmanager.data.usecase

import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.util.ProductRanker
import com.darius.listmanager.util.TextNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Segmentation scores each word window only against the products that share a word, a stem
 * or a first syllable with it, and against the whole catalog when none does. That must cut
 * an utterance exactly where scoring the whole catalog for every window cuts it.
 *
 * All product names are invented.
 */
class SegmentationPrefilterTest {
    private val names = listOf(
        "Perie sârmă inox" to "perie sarma inox",
        "Săpun vase lichid 1L" to "sapun vase lichid",
        "Săpun mâini lavandă 500ml" to "sapun maini lavanda",
        "Roată zenit 26*1.75" to "roata zenit",
        "Roată zenit 28*1.75" to "roata zenit",
        "Cui zincat 3.5*35" to "cui zincat",
        "Cui zincat 4*40" to "cui zincat",
        "Cuie tâmplărie 2*30" to "cuie tamplarie",
        "Brânză olimpia 400g" to "branza olimpia",
        "Franzelă neagră 500g" to "franzela neagra",
        "Vopsea neagră mată 0.75L" to "vopsea neagra mata",
        "Suc mere natural 1L" to "suc mere natural",
        "Suc portocale 2L" to "suc portocale",
        "Bandă adezivă albastră 50m" to "banda adeziva albastra",
        "Clește sertizare RJ45" to "cleste sertizare",
        "Ciocan dulgher 600g" to "ciocan dulgher",
        "Furtun grădină 1/2 25m" to "furtun gradina",
        "Mănuși latex M 100buc" to "manusi latex m",
        "Lanternă LED reîncărcabilă" to "lanterna led reincarcabila",
        "Baterii AA alcaline 4buc" to "baterii aa alcaline",
        "Hârtie copiator A4 500 coli" to "hartie copiator coli",
        "Marker permanent negru" to "marker permanent negru",
        "Burghiu beton 8*120" to "burghiu beton",
        "Diblu plastic 6*30" to "diblu plastic",
        "Silicon sanitar transparent" to "silicon sanitar transparent",
        "Spumă poliuretanică 750ml" to "spuma poliuretanica",
        "Pensulă lată 50mm" to "pensula lata",
        "Trafalet burete 10cm" to "trafalet burete",
        "Găleată plastic 10L" to "galeata plastic",
        "Mop bumbac rezervă" to "mop bumbac rezerva",
    ).mapIndexed { i, (name, aliases) -> ProductEntity(i + 1L, name, 1, aliases) }

    private val catalog = ProductRanker.prepare(names).also { it.indexWords() }

    private fun garble(word: String, random: Random): String {
        if (word.length < 4 || random.nextInt(3) != 0) return word
        val at = random.nextInt(word.length - 1)
        return when (random.nextInt(3)) {
            0 -> word.removeRange(at, at + 1) // includes losing the first letter
            1 -> word.substring(0, at) + word[at + 1] + word[at] + word.substring(at + 2)
            else -> word.substring(0, at) + "e" + word.substring(at + 1)
        }
    }

    /** Two to four products said the way a shopper says them, some words misheard. */
    private fun utterances(seed: Int, count: Int): List<String> {
        val random = Random(seed)
        val fillers = listOf("", "", "și", "pe 26 pe 175", "3,5 pe 35", "x", "natural")
        return List(count) {
            List(2 + random.nextInt(3)) {
                val words = TextNorm.normalize(names.random(random).name).split(" ")
                    .filter { word -> word.any(Char::isLetter) }
                (words.take(1 + random.nextInt(words.size)).map { garble(it, random) } +
                    fillers.random(random)).joinToString(" ")
            }.joinToString(" ").trim()
        }
    }

    @Test
    fun candidateScoringCutsUtterancesLikeScoringTheWholeCatalog() {
        val cases = utterances(seed = 20261108, count = 400)
        val different = cases.filter { utterance ->
            ResolveSpokenProductUseCase.segment(utterance, catalog, candidatesOnly = true) !=
                ResolveSpokenProductUseCase.segment(utterance, catalog, candidatesOnly = false)
        }
        assertEquals("Cut differently: $different", emptyList<String>(), different)
    }

    @Test
    fun aWindowWithNoCandidateStillScoresAgainstTheWholeCatalog() {
        // "rafalet" shares no word, stem or first letters with any product: only the full
        // catalog finds "Trafalet burete 10cm".
        val full = ProductRanker.bestMatch("rafalet", catalog)
        val prefiltered = ProductRanker.bestMatch("rafalet", catalog, candidatesOnly = true)
        assertTrue(full != null && full.score > 0.0)
        assertEquals(full!!.product.product.id, prefiltered!!.product.product.id)
        assertEquals(full.score, prefiltered.score, 0.0)
    }

    @Test
    fun theCasesFromThePhoneAreCutRightWithRealScoring() {
        assertEquals(
            listOf("perie sârmă", "săpun vase"),
            ResolveSpokenProductUseCase.segment("perie sârmă săpun vase", catalog),
        )
        assertEquals(
            listOf("roată zenit 26x175", "cui 3.5x35"),
            ResolveSpokenProductUseCase.segment("roată zenit pe 26 pe 175 cui 3,5 pe 35", catalog),
        )
    }
}
