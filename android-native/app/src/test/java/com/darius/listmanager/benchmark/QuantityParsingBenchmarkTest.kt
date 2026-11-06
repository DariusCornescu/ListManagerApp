package com.darius.listmanager.benchmark

import com.darius.listmanager.util.InventoryLineParser
import com.darius.listmanager.util.SpokenQuantity
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EX-42: how often quantity and price parse correctly, on a small hand-labelled slice.
 *
 * The phrases are written by hand from how the app's Romanian dictation reads, including
 * the one real S25 transcript. They are not recorded speech, so this measures the parsers,
 * not the recognizer. Run with: gradlew testDebugUnitTest --tests "*QuantityParsingBenchmarkTest*"
 */
class QuantityParsingBenchmarkTest {
    private data class SessionCase(val text: String, val quantity: Int)

    private data class InventoryCase(
        val text: String,
        val name: String,
        val quantity: Double?,
        val priceBani: Long?,
    )

    private val sessionCases = listOf(
        // Single words and digits: what the recognizer usually returns.
        SessionCase("lapte zuzu", 1),
        SessionCase("două lapte Zuzu", 2), // Real S25 transcript.
        SessionCase("doua lapte zuzu", 2),
        SessionCase("doi saci ciment", 2),
        SessionCase("un ulei floarea soarelui", 1),
        SessionCase("o pâine albă", 1),
        SessionCase("trei pâini", 3),
        SessionCase("patru cutii", 4),
        SessionCase("cinci kilograme de cartofi", 5),
        SessionCase("șase apă plată", 6),
        SessionCase("șapte sucuri", 7),
        SessionCase("opt mere", 8),
        SessionCase("nouă banane", 9),
        SessionCase("zece ouă", 10),
        SessionCase("3 lapte zuzu", 3),
        SessionCase("3x lapte", 3),
        SessionCase("11 sticle apă", 11),
        SessionCase("25 cutii lapte", 25),
        // Above ten, in words.
        SessionCase("unsprezece pungi", 11),
        SessionCase("doisprezece saci", 12),
        SessionCase("douăsprezece ouă", 12),
        SessionCase("cincisprezece cutii", 15),
        SessionCase("șaisprezece cutii", 16),
        SessionCase("douăzeci de ouă", 20),
        SessionCase("douăzeci și cinci de cutii", 25),
        SessionCase("treizeci de ouă", 30),
        SessionCase("cincizeci de pungi", 50),
        SessionCase("o sută de pungi", 100),
        SessionCase("două sute de pungi", 200),
        // Sizes and numbers that belong to the product must not become quantities.
        SessionCase("ulei 2 litri", 1),
        SessionCase("lapte 1.5%", 1),
        SessionCase("apă 2 l", 1),
        SessionCase("pepsi 2 litri", 1),
        SessionCase("bere 0.5", 1),
        SessionCase("șurub 6 pe 40", 1),
        SessionCase("cuie de 5", 1),
    )

    private val inventoryCases = listOf(
        InventoryCase("lapte zuzu 5 4 lei 50", "lapte zuzu", 5.0, 450),
        InventoryCase("pâine 3 2 lei", "pâine", 3.0, 200),
        InventoryCase("zahăr 10 3 lei 20 bani", "zahăr", 10.0, 320),
        InventoryCase("făină 4", "făină", 4.0, null),
        InventoryCase("apă 6 lei", "apă", null, 600),
        InventoryCase("gumă 50 bani", "gumă", null, 50),
        InventoryCase("ulei 2 4 virgulă 50", "ulei", 2.0, 450),
        InventoryCase("ouă 30 buc 1 leu", "ouă", 30.0, 100),
        InventoryCase("lapte de vacă 2 7 lei", "lapte de vacă", 2.0, 700),
        InventoryCase("apă plată", "apă plată", null, null),
        // Number words, which is how the recognizer returned the one real transcript.
        InventoryCase("lapte cinci 4 lei", "lapte", 5.0, 400),
        InventoryCase("pâine două 2 lei 50", "pâine", 2.0, 250),
        InventoryCase("apă șase lei", "apă", null, 600),
        InventoryCase("zahăr trei cinci lei", "zahăr", 3.0, 500),
        InventoryCase("lapte cinci lei 50", "lapte", null, 550),
        InventoryCase("ouă douăzeci 1 leu", "ouă", 20.0, 100),
        // "nouă" also means "new", so a trailing one must stay in the name.
        InventoryCase("pâine nouă", "pâine nouă", null, null),
    )

    @Test fun reportsParseAccuracy() {
        val sessionMisses = sessionCases.filter { SpokenQuantity.from(it.text) != it.quantity }
        val inventoryMisses = inventoryCases.filter {
            val parsed = InventoryLineParser.parse(it.text)
            parsed?.nameText != it.name || parsed.quantity != it.quantity || parsed.priceBani != it.priceBani
        }

        sessionMisses.forEach {
            println("VOICE_QUANTITY_MISS session '${it.text}' expected=${it.quantity} got=${SpokenQuantity.from(it.text)}")
        }
        inventoryMisses.forEach {
            println("VOICE_QUANTITY_MISS inventory '${it.text}' expected=$it got=${InventoryLineParser.parse(it.text)}")
        }
        println(
            "VOICE_QUANTITY session=${sessionCases.size - sessionMisses.size}/${sessionCases.size} " +
                "inventory=${inventoryCases.size - inventoryMisses.size}/${inventoryCases.size}"
        )

        assertTrue("Session quantity parsing regressed: $sessionMisses", sessionMisses.size <= MAX_SESSION_MISSES)
        assertTrue("Inventory parsing regressed: $inventoryMisses", inventoryMisses.size <= MAX_INVENTORY_MISSES)
    }

    private companion object {
        // The whole slice parses today, so any regression fails the suite.
        const val MAX_SESSION_MISSES = 0
        const val MAX_INVENTORY_MISSES = 0
    }
}
