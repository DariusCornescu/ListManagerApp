# Inventory Lists (spoken product + qty + price) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new local "Inventar" screen where the operator taps the mic, speaks one line ("lapte zuzu 5 4 lei 50"), and the app appends a table row with product / quantity / price / value (= qty × price) plus a live grand total, exportable to PDF.

**Architecture:** All Android (`android-native/`), no backend changes. A pure `InventoryLineParser` splits the spoken line (trailing-numbers rule), a pure `InventoryMath` does bani-integer money math, a new Room table holds the single active list, `InventoryViewModel` wires tap-to-speak (existing `AndroidSpeechProvider`, stopped on first `Final`), catalog name resolution (existing `ProductRanker`), and PDF export (new inventory layout method on the existing `PdfRepository`).

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Room, Android SpeechRecognizer (existing provider), `android.graphics.pdf.PdfDocument`, JUnit4 JVM tests.

**Working directory for all commands:** `C:/Users/dariu/Documents/GithubRepos/ListManagerApp/.claude/worktrees/inventory-lists/android-native` (PowerShell; run gradle as `.\gradlew <task>`). The gitignored `ApiConfig.kt` is already copied into this worktree.

**Verification gate:** compile + JVM unit tests only (`.\gradlew :app:compileDebugKotlin`, `.\gradlew testDebugUnitTest`, final `.\gradlew assembleDebug`). On-device behavior is verified manually by the user afterwards — do NOT attempt emulator/instrumented tests (unreliable in this environment).

---

## Design notes / deviations from spec (deliberate, keep)

1. **Parser is 100% pure — no resolver callback.** The spec suggested resolving the product first and "consuming its span". Same safety, simpler mechanism: tokens like `2l` / `1.5%` are not *pure* numbers, so they never leave the name; and only the **trailing numeric region** of the line is parsed as qty/price. `"cola 2l 5 4 lei"` → name `"cola 2l"`, qty 5, price 4.00 with zero catalog knowledge. Catalog resolution happens *after* parsing, in the ViewModel (adopt catalog name + productId only at score ≥ 0.82, else keep free text).
2. **One-shot (tap-to-speak) without touching `AndroidSpeechProvider`.** The ViewModel collects `speechState` in `viewModelScope` (which uses `Dispatchers.Main.immediate`); when `Final` arrives the collector runs synchronously at the emission point — calling `stopListening()` there sets `wantListening=false` *before* `onResults` continues to `applyPolicy`, so the policy returns STOP and no restart happens. This is the same mechanism HomeViewModel already relies on. Provider and `SpeechRepository` interface stay unchanged.
3. **Single amount with a money marker = price.** `"lapte 5"` → qty 5, no price; `"lapte 5 lei"` → price 5.00, no qty. Both rows appear with the missing cell blank ("—", highlighted) for manual fill.
4. **Money in bani (Long), quantity as Double.** Line value = `Math.round(quantity * priceBani)` bani; total = sum. Formatting (`"4,50 lei"`) lives in `InventoryMath`, unit-tested; the PDF just draws pre-formatted strings.

## File structure

| File | Responsibility | Action |
|---|---|---|
| `app/src/main/java/com/darius/listmanager/util/InventoryLineParser.kt` | Pure spoken-line → (name, qty, priceBani) | Create |
| `app/src/test/java/com/darius/listmanager/util/InventoryLineParserTest.kt` | Parser tests (risk center) | Create |
| `app/src/main/java/com/darius/listmanager/util/InventoryMath.kt` | Pure money math + formatting | Create |
| `app/src/test/java/com/darius/listmanager/util/InventoryMathTest.kt` | Math/format tests | Create |
| `app/src/main/java/com/darius/listmanager/data/local/entity/InventoryItemEntity.kt` | Room entity | Create |
| `app/src/main/java/com/darius/listmanager/data/local/dao/InventoryItemDao.kt` | Room DAO | Create |
| `app/src/main/java/com/darius/listmanager/data/repository/InventoryRepository.kt` | Thin repo over DAO | Create |
| `app/src/main/java/com/darius/listmanager/data/local/AppDatabase.kt` | Register entity+dao, version 6→7 | Modify |
| `app/src/main/java/com/darius/listmanager/data/repository/PdfRepository.kt` | Add `createInventoryPdf` + `InventoryPdfRow` | Modify |
| `app/src/main/java/com/darius/listmanager/ui/viewmodel/InventoryViewModel.kt` | Speech wiring, parse→resolve→insert, CRUD, export | Create |
| `app/src/main/java/com/darius/listmanager/ui/screens/InventoryScreen.kt` | Table UI + mic + total + export | Create |
| `app/src/main/java/com/darius/listmanager/ui/navigation/NavGraph.kt` | `inventory` route | Modify |
| `app/src/main/java/com/darius/listmanager/ui/components/DrawerContent.kt` | "Liste inventar" item | Modify |
| `app/src/main/java/com/darius/listmanager/ui/screens/HomeScreen.kt` | Entry button + nav param | Modify |

---

### Task 1: `InventoryLineParser` (pure) + tests

**Files:**
- Create: `app/src/main/java/com/darius/listmanager/util/InventoryLineParser.kt`
- Test: `app/src/test/java/com/darius/listmanager/util/InventoryLineParserTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/darius/listmanager/util/InventoryLineParserTest.kt`:

```kotlin
package com.darius.listmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InventoryLineParserTest {

    @Test
    fun fullLine_nameQtyLeiBaniPrice() {
        val p = InventoryLineParser.parse("lapte zuzu 5 4 lei 50")!!
        assertEquals("lapte zuzu", p.nameText)
        assertEquals(5.0, p.quantity!!, 0.0)
        assertEquals(450L, p.priceBani)
    }

    @Test
    fun decimalPriceWithComma() {
        val p = InventoryLineParser.parse("pâine 3 2,50")!!
        assertEquals("pâine", p.nameText)
        assertEquals(3.0, p.quantity!!, 0.0)
        assertEquals(250L, p.priceBani)
    }

    @Test
    fun decimalPriceWithDot() {
        val p = InventoryLineParser.parse("pâine 3 2.5")!!
        assertEquals(250L, p.priceBani)
    }

    @Test
    fun virgulaWordPrice() {
        val p = InventoryLineParser.parse("ouă 10 1 virgulă 20")!!
        assertEquals("ouă", p.nameText)
        assertEquals(10.0, p.quantity!!, 0.0)
        assertEquals(120L, p.priceBani)
    }

    @Test
    fun leiOnlyPrice() {
        val p = InventoryLineParser.parse("apă 6 5 lei")!!
        assertEquals(6.0, p.quantity!!, 0.0)
        assertEquals(500L, p.priceBani)
    }

    @Test
    fun leiAndBaniPrice() {
        val p = InventoryLineParser.parse("cafea 2 12 lei 75 bani")!!
        assertEquals(2.0, p.quantity!!, 0.0)
        assertEquals(1275L, p.priceBani)
    }

    @Test
    fun baniOnlyPrice() {
        val p = InventoryLineParser.parse("chibrituri 3 50 bani")!!
        assertEquals(3.0, p.quantity!!, 0.0)
        assertEquals(50L, p.priceBani)
    }

    @Test
    fun onlyQuantity_priceMissing() {
        val p = InventoryLineParser.parse("lapte 5")!!
        assertEquals("lapte", p.nameText)
        assertEquals(5.0, p.quantity!!, 0.0)
        assertNull(p.priceBani)
    }

    @Test
    fun singleMoneyMarkedAmount_isPriceNotQuantity() {
        val p = InventoryLineParser.parse("lapte 5 lei")!!
        assertEquals("lapte", p.nameText)
        assertNull(p.quantity)
        assertEquals(500L, p.priceBani)
    }

    @Test
    fun onlyName_bothMissing() {
        val p = InventoryLineParser.parse("lapte zuzu")!!
        assertEquals("lapte zuzu", p.nameText)
        assertNull(p.quantity)
        assertNull(p.priceBani)
    }

    @Test
    fun numberInsideProductNameStaysInName() {
        val p = InventoryLineParser.parse("cola 2l 5 4 lei")!!
        assertEquals("cola 2l", p.nameText)
        assertEquals(5.0, p.quantity!!, 0.0)
        assertEquals(400L, p.priceBani)
    }

    @Test
    fun fillerWordsInsideNumericRegion() {
        val p = InventoryLineParser.parse("lapte 5 bucăți 4 lei 50")!!
        assertEquals("lapte", p.nameText)
        assertEquals(5.0, p.quantity!!, 0.0)
        assertEquals(450L, p.priceBani)
    }

    @Test
    fun decimalQuantity() {
        val p = InventoryLineParser.parse("cașcaval 2,5 30 lei")!!
        assertEquals(2.5, p.quantity!!, 0.0)
        assertEquals(3000L, p.priceBani)
    }

    @Test
    fun bareThirdNumberIgnored() {
        // convention: say "4 lei 50" or "4 virgulă 50"; a bare "4 50" is NOT merged
        val p = InventoryLineParser.parse("lapte 5 4 50")!!
        assertEquals(5.0, p.quantity!!, 0.0)
        assertEquals(400L, p.priceBani)
    }

    @Test
    fun emptyName_returnedBlank() {
        val p = InventoryLineParser.parse("5 4 lei")!!
        assertEquals("", p.nameText)
        assertEquals(5.0, p.quantity!!, 0.0)
        assertEquals(400L, p.priceBani)
    }

    @Test
    fun nameWithFillerWordsNotAtEnd_keptIntact() {
        val p = InventoryLineParser.parse("lapte de vacă")!!
        assertEquals("lapte de vacă", p.nameText)
        assertNull(p.quantity)
        assertNull(p.priceBani)
    }

    @Test
    fun uppercaseInputNormalized() {
        val p = InventoryLineParser.parse("LAPTE ZUZU 5 4 LEI")!!
        assertEquals("lapte zuzu", p.nameText)
        assertEquals(400L, p.priceBani)
    }

    @Test
    fun blank_returnsNull() {
        assertNull(InventoryLineParser.parse("   "))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.darius.listmanager.util.InventoryLineParserTest"`
Expected: FAIL to compile — `Unresolved reference: InventoryLineParser`.

- [ ] **Step 3: Write the parser**

Create `app/src/main/java/com/darius/listmanager/util/InventoryLineParser.kt`:

```kotlin
package com.darius.listmanager.util

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Result of parsing one spoken inventory line ("lapte zuzu 5 4 lei 50").
 * Missing/unparseable fields are null; the UI shows them blank for manual fill.
 */
data class ParsedInventoryLine(
    val nameText: String,
    val quantity: Double?,
    val priceBani: Long?
)

/**
 * Pure parser for spoken inventory lines in the fixed order
 * product -> quantity -> price (Romanian speech-recognizer output).
 *
 * Only the TRAILING numeric region of the line is interpreted as qty/price;
 * tokens like "2l" or "1.5%" are not pure numbers, so numbers inside product
 * names never leak into the amounts. First trailing amount = quantity, second
 * = price; a single amount carrying a money marker (lei/bani) is the price.
 */
object InventoryLineParser {

    private val FILLER = setOf("de", "la", "buc", "bucata", "bucată", "bucati", "bucăți")
    private val CURRENCY = setOf("lei", "leu", "ron")
    private val SUBUNIT = setOf("bani", "ban")
    private val COMMA_WORD = setOf("virgula", "virgulă")

    private val NUMBER = Regex("^\\d+([.,]\\d+)?$")

    private data class Amount(val value: Double, val money: Boolean)

    fun parse(text: String): ParsedInventoryLine? {
        val tokens = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        // 1. Trailing numeric region: scan from the end while tokens are pure
        //    numbers or known money/filler words.
        var start = tokens.size
        for (i in tokens.indices.reversed()) {
            val t = tokens[i]
            if (isNumber(t) || t in FILLER || t in CURRENCY || t in SUBUNIT || t in COMMA_WORD) {
                start = i
            } else break
        }
        val region = tokens.subList(start, tokens.size)
        if (region.none { isNumber(it) }) {
            // no numbers at the end -> the whole line is a product name
            return ParsedInventoryLine(tokens.joinToString(" "), quantity = null, priceBani = null)
        }
        val nameText = tokens.subList(0, start).joinToString(" ")

        // 2. Collapse the region into amounts (left to right).
        val amounts = mutableListOf<Amount>()
        var i = 0
        while (i < region.size) {
            val t = region[i]
            if (!isNumber(t)) { i++; continue }
            var value = numberOf(t)
            var money = false
            var consumed = 1
            val next = region.getOrNull(i + 1)
            val next2 = region.getOrNull(i + 2)
            val next3 = region.getOrNull(i + 3)
            when {
                // "4 virgulă 50" -> 4.50
                next != null && next in COMMA_WORD && next2 != null && isNumber(next2) -> {
                    value += fractionOf(next2)
                    consumed = 3
                }
                // "4 lei 50 (bani)?" -> 4.50
                next != null && next in CURRENCY && next2 != null && isNumber(next2) -> {
                    value += numberOf(next2) / 100.0
                    money = true
                    consumed = if (next3 != null && next3 in SUBUNIT) 4 else 3
                }
                // "4 lei" -> 4.00
                next != null && next in CURRENCY -> { money = true; consumed = 2 }
                // "50 bani" -> 0.50
                next != null && next in SUBUNIT -> { value /= 100.0; money = true; consumed = 2 }
            }
            amounts.add(Amount(value, money))
            i += consumed
        }

        // 3. Fixed order: first = quantity, second = price. A single
        //    money-marked amount is the price (qty missing).
        val quantity: Double?
        val priceLei: Double?
        when {
            amounts.size >= 2 -> { quantity = amounts[0].value; priceLei = amounts[1].value }
            amounts.size == 1 && amounts[0].money -> { quantity = null; priceLei = amounts[0].value }
            amounts.size == 1 -> { quantity = amounts[0].value; priceLei = null }
            else -> { quantity = null; priceLei = null }
        }

        return ParsedInventoryLine(
            nameText = nameText,
            quantity = quantity,
            priceBani = priceLei?.let { (it * 100).roundToLong() }
        )
    }

    private fun isNumber(t: String) = NUMBER.matches(t)

    private fun numberOf(t: String) = t.replace(',', '.').toDouble()

    /** "50" -> 0.50, "5" -> 0.5, "05" -> 0.05 (decimal part spoken after "virgulă"). */
    private fun fractionOf(t: String): Double = t.toDouble() / 10.0.pow(t.length)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.darius.listmanager.util.InventoryLineParserTest"`
Expected: BUILD SUCCESSFUL, 18 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/darius/listmanager/util/InventoryLineParser.kt app/src/test/java/com/darius/listmanager/util/InventoryLineParserTest.kt
git commit -m "feat(inventory): spoken-line parser for product + quantity + price"
```

---

### Task 2: `InventoryMath` (pure money math) + tests

**Files:**
- Create: `app/src/main/java/com/darius/listmanager/util/InventoryMath.kt`
- Test: `app/src/test/java/com/darius/listmanager/util/InventoryMathTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/darius/listmanager/util/InventoryMathTest.kt`:

```kotlin
package com.darius.listmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InventoryMathTest {

    @Test
    fun lineValue_basic() {
        assertEquals(2250L, InventoryMath.lineValueBani(5.0, 450L))
    }

    @Test
    fun lineValue_decimalQuantityRounds() {
        assertEquals(1125L, InventoryMath.lineValueBani(2.5, 450L))
        assertEquals(1667L, InventoryMath.lineValueBani(3.7037, 450L)) // 1666.665 -> 1667
    }

    @Test
    fun lineValue_missingFieldIsNull() {
        assertNull(InventoryMath.lineValueBani(null, 450L))
        assertNull(InventoryMath.lineValueBani(5.0, null))
    }

    @Test
    fun total_skipsIncompleteRows() {
        val total = InventoryMath.totalBani(
            listOf(
                Triple("a", 2.0, 100L),   // 200
                Triple("b", null, 500L),  // skipped
                Triple("c", 3.0, null),   // skipped
                Triple("d", 1.5, 200L)    // 300
            ).map { (_, q, p) -> q to p }
        )
        assertEquals(500L, total)
    }

    @Test
    fun formatLei() {
        assertEquals("4,50 lei", InventoryMath.formatLei(450L))
        assertEquals("0,05 lei", InventoryMath.formatLei(5L))
        assertEquals("0,00 lei", InventoryMath.formatLei(0L))
        assertEquals("12345,67 lei", InventoryMath.formatLei(1234567L))
    }

    @Test
    fun formatQuantity() {
        assertEquals("5", InventoryMath.formatQuantity(5.0))
        assertEquals("2,5", InventoryMath.formatQuantity(2.5))
        assertEquals("", InventoryMath.formatQuantity(null))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.darius.listmanager.util.InventoryMathTest"`
Expected: FAIL to compile — `Unresolved reference: InventoryMath`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/darius/listmanager/util/InventoryMath.kt`:

```kotlin
package com.darius.listmanager.util

import kotlin.math.roundToLong

/**
 * Pure money math for inventory lists. Prices are stored in bani (integer
 * minor units) — never floating-point lei — so totals cannot drift.
 */
object InventoryMath {

    /** quantity x unit price, rounded to whole bani; null if either is missing. */
    fun lineValueBani(quantity: Double?, priceBani: Long?): Long? =
        if (quantity == null || priceBani == null) null
        else (quantity * priceBani).roundToLong()

    /** Sum of complete rows' line values (incomplete rows contribute 0). */
    fun totalBani(rows: List<Pair<Double?, Long?>>): Long =
        rows.sumOf { (q, p) -> lineValueBani(q, p) ?: 0L }

    /** 450 -> "4,50 lei" (Romanian comma decimals). */
    fun formatLei(bani: Long): String {
        val sign = if (bani < 0) "-" else ""
        val abs = kotlin.math.abs(bani)
        return "$sign${abs / 100},${(abs % 100).toString().padStart(2, '0')} lei"
    }

    /** 5.0 -> "5", 2.5 -> "2,5", null -> "". */
    fun formatQuantity(quantity: Double?): String {
        if (quantity == null) return ""
        return if (quantity == quantity.toLong().toDouble()) quantity.toLong().toString()
        else quantity.toString().replace('.', ',')
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.darius.listmanager.util.InventoryMathTest"`
Expected: BUILD SUCCESSFUL, 6 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/darius/listmanager/util/InventoryMath.kt app/src/test/java/com/darius/listmanager/util/InventoryMathTest.kt
git commit -m "feat(inventory): bani-based money math and formatting"
```

---

### Task 3: Room layer (entity, DAO, repository, DB v7)

**Files:**
- Create: `app/src/main/java/com/darius/listmanager/data/local/entity/InventoryItemEntity.kt`
- Create: `app/src/main/java/com/darius/listmanager/data/local/dao/InventoryItemDao.kt`
- Create: `app/src/main/java/com/darius/listmanager/data/repository/InventoryRepository.kt`
- Modify: `app/src/main/java/com/darius/listmanager/data/local/AppDatabase.kt`

No JVM test (Room DAOs need instrumentation, which we defer); gate is compilation. The DAO is declarative and mirrors `NeedsReviewDao` exactly.

- [ ] **Step 1: Create the entity**

Create `app/src/main/java/com/darius/listmanager/data/local/entity/InventoryItemEntity.kt`:

```kotlin
package com.darius.listmanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row of the single active inventory list (local-only, v1). Quantity and
 * unit price may be null when the spoken line was incomplete — the UI shows
 * the blank cell highlighted for manual entry. Price is in bani (integer
 * minor units). productId is set when the spoken name matched the catalog.
 */
@Entity(tableName = "inventory_items")
data class InventoryItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val productId: Long? = null,
    val quantity: Double? = null,
    val priceBani: Long? = null,
    val createdAt: Long
)
```

- [ ] **Step 2: Create the DAO**

Create `app/src/main/java/com/darius/listmanager/data/local/dao/InventoryItemDao.kt`:

```kotlin
package com.darius.listmanager.data.local.dao

import androidx.room.*
import com.darius.listmanager.data.local.entity.InventoryItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryItemDao {

    @Query("SELECT * FROM inventory_items ORDER BY id ASC")
    fun getAllFlow(): Flow<List<InventoryItemEntity>>

    @Insert
    suspend fun insert(item: InventoryItemEntity): Long

    @Update
    suspend fun update(item: InventoryItemEntity)

    @Query("DELETE FROM inventory_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM inventory_items")
    suspend fun deleteAll()
}
```

- [ ] **Step 3: Create the repository**

Create `app/src/main/java/com/darius/listmanager/data/repository/InventoryRepository.kt`:

```kotlin
package com.darius.listmanager.data.repository

import com.darius.listmanager.data.local.dao.InventoryItemDao
import com.darius.listmanager.data.local.entity.InventoryItemEntity
import kotlinx.coroutines.flow.Flow

class InventoryRepository(private val dao: InventoryItemDao) {

    fun getAllFlow(): Flow<List<InventoryItemEntity>> = dao.getAllFlow()

    suspend fun insert(item: InventoryItemEntity): Long = dao.insert(item)

    suspend fun update(item: InventoryItemEntity) = dao.update(item)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()
}
```

- [ ] **Step 4: Register in `AppDatabase`**

In `app/src/main/java/com/darius/listmanager/data/local/AppDatabase.kt` (wildcard imports for `dao.*` and `entity.*` already cover the new classes):
1. Add `InventoryItemEntity::class` to the `entities = [...]` list (after `ProductEmbeddingEntity::class`).
2. Bump `version = 6` to `version = 7`.
3. Add the abstract getter after `productEmbeddingDao()`:

```kotlin
    abstract fun inventoryItemDao(): InventoryItemDao
```

(The builder keeps `fallbackToDestructiveMigration()` — the version bump wipes the local cache on first launch, which is the established pattern here; catalog re-syncs from the server.)

- [ ] **Step 5: Compile gate**

Run: `.\gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (Room's KSP validates the entity/DAO at compile time).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/darius/listmanager/data/local/entity/InventoryItemEntity.kt app/src/main/java/com/darius/listmanager/data/local/dao/InventoryItemDao.kt app/src/main/java/com/darius/listmanager/data/repository/InventoryRepository.kt app/src/main/java/com/darius/listmanager/data/local/AppDatabase.kt
git commit -m "feat(inventory): room table for the active inventory list (db v7)"
```

---

### Task 4: Inventory PDF layout on `PdfRepository`

**Files:**
- Modify: `app/src/main/java/com/darius/listmanager/data/repository/PdfRepository.kt`

Append a new method + row type; do NOT modify `upsertDistributorPdf`, `truncateText`, `getPdfDirectory`, `getAllPdfs`, or `PdfItem`. The method mirrors the existing layout machinery (same constants, same pagination shape, same directory so the existing FileProvider path covers it). Gate is compilation (android.graphics is not JVM-testable; all number logic was tested in Task 2).

- [ ] **Step 1: Add `InventoryPdfRow` and `createInventoryPdf`**

At the bottom of `PdfRepository.kt` (after the `PdfItem` data class), add:

```kotlin
/** One pre-formatted inventory table row. Formatting lives in InventoryMath. */
data class InventoryPdfRow(
    val name: String,
    val quantityText: String,
    val priceText: String,
    val valueText: String
)
```

Inside `class PdfRepository` (after `getAllPdfs()`), add:

```kotlin
    /**
     * Render the inventory list as one PDF: Produs | Cant. | Pret | Valoare
     * with a grand-total row on the last page. Rows arrive pre-formatted.
     */
    suspend fun createInventoryPdf(
        sessionDate: Long,
        items: List<InventoryPdfRow>,
        totalText: String
    ): File = withContext(Dispatchers.IO) {

        val pdfDocument = PdfDocument()

        val titlePaint = Paint().apply { textSize = 24f; isFakeBoldText = true; isAntiAlias = true }
        val headerPaint = Paint().apply { textSize = 16f; isFakeBoldText = true; isAntiAlias = true }
        val bodyPaint = Paint().apply { textSize = 12f; isAntiAlias = true }
        val smallPaint = Paint().apply { textSize = 10f; color = android.graphics.Color.GRAY; isAntiAlias = true }
        val tableBorderPaint = Paint().apply { color = android.graphics.Color.BLACK; strokeWidth = 1f; style = Paint.Style.STROKE }
        val tableHeaderBgPaint = Paint().apply { color = android.graphics.Color.LTGRAY; style = Paint.Style.FILL }

        // Columns: name (flexible) | qty 70 | price 90 | value 100
        val qtyColWidth = 70f
        val priceColWidth = 90f
        val valueColWidth = 100f
        val tableWidth = PAGE_WIDTH - (2 * MARGIN)
        val nameColWidth = tableWidth - qtyColWidth - priceColWidth - valueColWidth

        val nameColStart = MARGIN
        val qtyColStart = nameColStart + nameColWidth
        val priceColStart = qtyColStart + qtyColWidth
        val valueColStart = priceColStart + priceColWidth
        val tableEnd = PAGE_WIDTH - MARGIN

        val maxItemsPerPage = ((PAGE_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT - 60f) / ROW_HEIGHT).toInt()
        val totalPages = maxOf(1, kotlin.math.ceil(items.size.toDouble() / maxItemsPerPage).toInt())

        var currentPage = 1
        var itemIndex = 0

        do {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, currentPage).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            var yPosition = MARGIN + 30

            if (currentPage == 1) {
                canvas.drawText("Inventar", MARGIN, yPosition, titlePaint)
                yPosition += 40
                val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                canvas.drawText("Data: ${dateFormat.format(Date(sessionDate))}", MARGIN, yPosition, bodyPaint)
                yPosition += 30
                canvas.drawLine(MARGIN, yPosition, tableEnd, yPosition, bodyPaint)
                yPosition += 20
            } else {
                yPosition = MARGIN + 20
            }

            // table header
            val tableHeaderY = yPosition
            canvas.drawRect(nameColStart, tableHeaderY, tableEnd, tableHeaderY + ROW_HEIGHT, tableHeaderBgPaint)
            canvas.drawRect(nameColStart, tableHeaderY, tableEnd, tableHeaderY + ROW_HEIGHT, tableBorderPaint)
            canvas.drawLine(qtyColStart, tableHeaderY, qtyColStart, tableHeaderY + ROW_HEIGHT, tableBorderPaint)
            canvas.drawLine(priceColStart, tableHeaderY, priceColStart, tableHeaderY + ROW_HEIGHT, tableBorderPaint)
            canvas.drawLine(valueColStart, tableHeaderY, valueColStart, tableHeaderY + ROW_HEIGHT, tableBorderPaint)

            val headerTextY = tableHeaderY + (ROW_HEIGHT / 2) + 5
            canvas.drawText("Produs", nameColStart + CELL_PADDING, headerTextY, headerPaint)
            canvas.drawText("Cant.", qtyColStart + CELL_PADDING, headerTextY, headerPaint)
            canvas.drawText("Pret", priceColStart + CELL_PADDING, headerTextY, headerPaint)
            canvas.drawText("Valoare", valueColStart + CELL_PADDING, headerTextY, headerPaint)

            yPosition = tableHeaderY + ROW_HEIGHT

            val pageItemLimit = if (currentPage == 1) {
                ((PAGE_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT - 60f) / ROW_HEIGHT).toInt()
            } else {
                ((PAGE_HEIGHT - FOOTER_HEIGHT - 80f) / ROW_HEIGHT).toInt()
            }

            var rowCount = 0
            while (itemIndex < items.size && rowCount < pageItemLimit) {
                val item = items[itemIndex]
                val rowY = yPosition

                canvas.drawRect(nameColStart, rowY, tableEnd, rowY + ROW_HEIGHT, tableBorderPaint)
                canvas.drawLine(qtyColStart, rowY, qtyColStart, rowY + ROW_HEIGHT, tableBorderPaint)
                canvas.drawLine(priceColStart, rowY, priceColStart, rowY + ROW_HEIGHT, tableBorderPaint)
                canvas.drawLine(valueColStart, rowY, valueColStart, rowY + ROW_HEIGHT, tableBorderPaint)

                val textY = rowY + (ROW_HEIGHT / 2) + 5
                val nameText = truncateText(item.name, nameColWidth - (2 * CELL_PADDING), bodyPaint)
                canvas.drawText(nameText, nameColStart + CELL_PADDING, textY, bodyPaint)

                // numbers right-aligned within their columns
                val qtyW = bodyPaint.measureText(item.quantityText)
                canvas.drawText(item.quantityText, priceColStart - qtyW - CELL_PADDING, textY, bodyPaint)
                val priceW = bodyPaint.measureText(item.priceText)
                canvas.drawText(item.priceText, valueColStart - priceW - CELL_PADDING, textY, bodyPaint)
                val valueW = bodyPaint.measureText(item.valueText)
                canvas.drawText(item.valueText, tableEnd - valueW - CELL_PADDING, textY, bodyPaint)

                yPosition += ROW_HEIGHT
                rowCount++
                itemIndex++
            }

            // grand total on the last page
            if (itemIndex >= items.size) {
                yPosition += 20
                canvas.drawRect(nameColStart, yPosition, tableEnd, yPosition + ROW_HEIGHT, tableHeaderBgPaint)
                canvas.drawRect(nameColStart, yPosition, tableEnd, yPosition + ROW_HEIGHT, tableBorderPaint)
                val totalTextY = yPosition + (ROW_HEIGHT / 2) + 5
                canvas.drawText("Total: $totalText", nameColStart + CELL_PADDING, totalTextY, headerPaint)
            }

            canvas.drawText("Page $currentPage of $totalPages", PAGE_WIDTH - MARGIN - 80, PAGE_HEIGHT - MARGIN + 20, smallPaint)
            canvas.drawText("Generated by List Manager App", MARGIN, PAGE_HEIGHT - MARGIN + 20, smallPaint)
            pdfDocument.finishPage(page)
            currentPage++
        } while (itemIndex < items.size)

        val fileName = "Inventar_${System.currentTimeMillis()}.pdf"
        val file = File(getPdfDirectory(), fileName)
        FileOutputStream(file).use { outputStream -> pdfDocument.writeTo(outputStream) }
        pdfDocument.close()

        return@withContext file
    }
```

Note the `do/while`: an empty list still produces a one-page PDF with headers + "Total: 0,00 lei" (the ViewModel refuses to export an empty list anyway, but the method must not crash).

- [ ] **Step 2: Compile gate**

Run: `.\gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the existing unit suite (regression)**

Run: `.\gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (nothing existing was modified).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/darius/listmanager/data/repository/PdfRepository.kt
git commit -m "feat(inventory): inventory PDF layout with per-line values and grand total"
```

---

### Task 5: `InventoryViewModel`

**Files:**
- Create: `app/src/main/java/com/darius/listmanager/ui/viewmodel/InventoryViewModel.kt`

Follows the app's exact ViewModel pattern: `AndroidViewModel(application)`, self-built repositories, no factory. Gate: compilation + regression suite.

- [ ] **Step 1: Write the ViewModel**

Create `app/src/main/java/com/darius/listmanager/ui/viewmodel/InventoryViewModel.kt`:

```kotlin
package com.darius.listmanager.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.InventoryItemEntity
import com.darius.listmanager.data.repository.InventoryPdfRow
import com.darius.listmanager.data.repository.InventoryRepository
import com.darius.listmanager.data.repository.PdfRepository
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.data.repository.SpeechRepository
import com.darius.listmanager.data.speech.AndroidSpeechProvider
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.util.InventoryLineParser
import com.darius.listmanager.util.InventoryMath
import com.darius.listmanager.util.ProductRanker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class InventoryUiState(
    val items: List<InventoryItemEntity> = emptyList(),
    val totalBani: Long = 0L,
    val speechState: SpeechState = SpeechState.Idle,
    val message: String? = null,
    val isGeneratingPdf: Boolean = false,
    val generatedPdf: File? = null
)

class InventoryViewModel(application: Application) : AndroidViewModel(application) {

    private val speechRepository: SpeechRepository = AndroidSpeechProvider(application)
    private val database = AppDatabase.getInstance(application)
    private val pendingOperationRepo = PendingOperationRepository(database.pendingOperationDao())
    private val productRepository = ProductRepository(
        database.productDao(), pendingOperationRepo, application.applicationContext, RetrofitClient.api
    )
    private val inventoryRepository = InventoryRepository(database.inventoryItemDao())
    private val pdfRepository = PdfRepository(application)

    private val _uiState = MutableStateFlow(InventoryUiState())
    val uiState: StateFlow<InventoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            inventoryRepository.getAllFlow().collect { items ->
                _uiState.value = _uiState.value.copy(
                    items = items,
                    totalBani = InventoryMath.totalBani(items.map { it.quantity to it.priceBani })
                )
            }
        }
        viewModelScope.launch {
            speechRepository.speechState.collect { state ->
                _uiState.value = _uiState.value.copy(speechState = state)
                if (state is SpeechState.Final) {
                    // One line per tap: stop BEFORE the provider's policy can
                    // restart (collector runs synchronously on Main.immediate).
                    speechRepository.stopListening()
                    addSpokenLine(state.text)
                }
            }
        }
    }

    // ==================== Speech ====================

    fun startListening() {
        _uiState.value = _uiState.value.copy(message = null)
        speechRepository.startListening()
    }

    fun stopListening() { speechRepository.stopListening() }

    // ==================== Rows ====================

    fun addSpokenLine(text: String) {
        viewModelScope.launch {
            val parsed = InventoryLineParser.parse(text)
            if (parsed == null || parsed.nameText.isBlank()) {
                _uiState.value = _uiState.value.copy(message = "Nu am înțeles produsul — mai zi o dată")
                return@launch
            }
            val (name, productId) = resolveName(parsed.nameText)
            inventoryRepository.insert(
                InventoryItemEntity(
                    name = name,
                    productId = productId,
                    quantity = parsed.quantity,
                    priceBani = parsed.priceBani,
                    createdAt = System.currentTimeMillis()
                )
            )
            val missing = mutableListOf<String>()
            if (parsed.quantity == null) missing.add("cantitatea")
            if (parsed.priceBani == null) missing.add("prețul")
            _uiState.value = _uiState.value.copy(
                message = if (missing.isEmpty()) null
                          else "Lipsește ${missing.joinToString(" și ")} — completează în tabel"
            )
        }
    }

    /** Adopt the catalog name + id only on a high-confidence match; else keep free text. */
    private suspend fun resolveName(nameText: String): Pair<String, Long?> {
        val local = try {
            productRepository.getAllLocal()
        } catch (e: Exception) {
            Log.e(TAG, "getAllLocal failed: ${e.message}", e)
            emptyList()
        }
        if (local.isEmpty()) return nameText to null
        val top = ProductRanker.rank(nameText, local).firstOrNull()
        return if (top != null && top.score >= MATCH_THRESHOLD) top.product.name to top.product.id
        else nameText to null
    }

    fun updateItem(item: InventoryItemEntity) {
        viewModelScope.launch { inventoryRepository.update(item) }
    }

    fun deleteItem(id: Long) {
        viewModelScope.launch { inventoryRepository.deleteById(id) }
    }

    fun clearList() {
        viewModelScope.launch {
            inventoryRepository.deleteAll()
            _uiState.value = _uiState.value.copy(message = "Listă nouă")
        }
    }

    // ==================== Export ====================

    fun exportPdf() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.items.isEmpty()) {
                _uiState.value = state.copy(message = "Lista e goală")
                return@launch
            }
            try {
                _uiState.value = state.copy(isGeneratingPdf = true)
                val rows = state.items.map { item ->
                    InventoryPdfRow(
                        name = item.name,
                        quantityText = InventoryMath.formatQuantity(item.quantity),
                        priceText = item.priceBani?.let { InventoryMath.formatLei(it) } ?: "",
                        valueText = InventoryMath.lineValueBani(item.quantity, item.priceBani)
                            ?.let { InventoryMath.formatLei(it) } ?: ""
                    )
                }
                val file = pdfRepository.createInventoryPdf(
                    sessionDate = System.currentTimeMillis(),
                    items = rows,
                    totalText = InventoryMath.formatLei(state.totalBani)
                )
                _uiState.value = _uiState.value.copy(isGeneratingPdf = false, generatedPdf = file)
            } catch (e: Exception) {
                Log.e(TAG, "PDF export failed", e)
                _uiState.value = _uiState.value.copy(
                    isGeneratingPdf = false,
                    message = "Eroare la PDF: ${e.message}"
                )
            }
        }
    }

    fun createShareIntent(): Intent? {
        val file = _uiState.value.generatedPdf ?: return null
        val context = getApplication<Application>()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun clearGeneratedPdf() {
        _uiState.value = _uiState.value.copy(generatedPdf = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    override fun onCleared() {
        super.onCleared()
        speechRepository.release()
    }

    companion object {
        private const val TAG = "InventoryViewModel"
        private const val MATCH_THRESHOLD = 0.82
    }
}
```

- [ ] **Step 2: Compile gate + regression suite**

Run: `.\gradlew :app:compileDebugKotlin` then `.\gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL both; all tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/darius/listmanager/ui/viewmodel/InventoryViewModel.kt
git commit -m "feat(inventory): viewmodel with tap-to-speak line capture and pdf export"
```

---

### Task 6: `InventoryScreen` + navigation + drawer + home entry

**Files:**
- Create: `app/src/main/java/com/darius/listmanager/ui/screens/InventoryScreen.kt`
- Modify: `app/src/main/java/com/darius/listmanager/ui/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/darius/listmanager/ui/components/DrawerContent.kt`
- Modify: `app/src/main/java/com/darius/listmanager/ui/screens/HomeScreen.kt`

- [ ] **Step 1: Create the screen**

Create `app/src/main/java/com/darius/listmanager/ui/screens/InventoryScreen.kt`:

```kotlin
package com.darius.listmanager.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.data.local.entity.InventoryItemEntity
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.ui.viewmodel.InventoryViewModel
import com.darius.listmanager.util.InventoryMath

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    onBack: () -> Unit,
    viewModel: InventoryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var editingItem by remember { mutableStateOf<InventoryItemEntity?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.startListening() }

    fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.generatedPdf) {
        uiState.generatedPdf?.let {
            viewModel.createShareIntent()?.let { intent ->
                context.startActivity(Intent.createChooser(intent, "Trimite inventarul"))
            }
            viewModel.clearGeneratedPdf()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Inventar") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Rounded.DeleteSweep, "Listă nouă")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ===== Table =====
            if (uiState.items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Mic, contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Apasă microfonul și zi:\n„produs  cantitate  preț”",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // header row
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Produs", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Text("Cant.", Modifier.width(52.dp), style = MaterialTheme.typography.labelLarge)
                    Text("Preț", Modifier.width(72.dp), style = MaterialTheme.typography.labelLarge)
                    Text("Valoare", Modifier.width(80.dp), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(32.dp))
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(uiState.items, key = { it.id }) { item ->
                        InventoryRow(
                            item = item,
                            onClick = { editingItem = item },
                            onDelete = { viewModel.deleteItem(item.id) }
                        )
                        HorizontalDivider()
                    }
                }
            }

            // ===== Total + controls =====
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Total", style = MaterialTheme.typography.titleMedium)
                        Text(
                            InventoryMath.formatLei(uiState.totalBani),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // mic button — one spoken line per tap
                        Surface(
                            shape = CircleShape,
                            color = when (uiState.speechState) {
                                is SpeechState.Listening -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.primaryContainer
                            },
                            modifier = Modifier.size(64.dp),
                            onClick = {
                                when (uiState.speechState) {
                                    is SpeechState.Listening -> viewModel.stopListening()
                                    else -> checkPermissionAndStart()
                                }
                            }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (uiState.speechState is SpeechState.Listening)
                                        Icons.Rounded.Stop else Icons.Rounded.Mic,
                                    contentDescription = "Microfon",
                                    modifier = Modifier.size(32.dp),
                                    tint = when (uiState.speechState) {
                                        is SpeechState.Listening -> MaterialTheme.colorScheme.onPrimary
                                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                                    }
                                )
                            }
                        }
                        Button(
                            onClick = { viewModel.exportPdf() },
                            enabled = uiState.items.isNotEmpty() && !uiState.isGeneratingPdf,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (uiState.isGeneratingPdf) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.Description, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Export PDF")
                            }
                        }
                    }
                }
            }
        }
    }

    editingItem?.let { item ->
        EditInventoryRowDialog(
            item = item,
            onDismiss = { editingItem = null },
            onSave = { updated ->
                viewModel.updateItem(updated)
                editingItem = null
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Listă nouă") },
            text = { Text("Ștergi toate rândurile din inventarul curent?") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearList()
                }) { Text("Da, șterge tot") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Renunță") }
            }
        )
    }
}

@Composable
private fun InventoryRow(
    item: InventoryItemEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val valueBani = InventoryMath.lineValueBani(item.quantity, item.priceBani)
    Surface(onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                item.name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2
            )
            MissingAwareText(InventoryMath.formatQuantity(item.quantity), Modifier.width(52.dp))
            MissingAwareText(
                item.priceBani?.let { InventoryMath.formatLei(it) } ?: "",
                Modifier.width(72.dp)
            )
            Text(
                valueBani?.let { InventoryMath.formatLei(it) } ?: "—",
                Modifier.width(80.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Close, "Șterge",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Blank (missing) cells render as a highlighted em-dash inviting a tap-to-edit. */
@Composable
private fun MissingAwareText(text: String, modifier: Modifier) {
    if (text.isEmpty()) {
        Text(
            "—", modifier,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
    } else {
        Text(text, modifier, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EditInventoryRowDialog(
    item: InventoryItemEntity,
    onDismiss: () -> Unit,
    onSave: (InventoryItemEntity) -> Unit
) {
    var name by remember { mutableStateOf(item.name) }
    var quantityText by remember { mutableStateOf(InventoryMath.formatQuantity(item.quantity)) }
    var priceText by remember {
        mutableStateOf(item.priceBani?.let { b -> "${b / 100},${(b % 100).toString().padStart(2, '0')}" } ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifică rândul") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Produs") }, singleLine = true
                )
                OutlinedTextField(
                    value = quantityText, onValueChange = { quantityText = it },
                    label = { Text("Cantitate") }, singleLine = true
                )
                OutlinedTextField(
                    value = priceText, onValueChange = { priceText = it },
                    label = { Text("Preț (lei, ex. 4,50)") }, singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val quantity = quantityText.trim().replace(',', '.').toDoubleOrNull()
                    val priceBani = priceText.trim().replace(',', '.').toDoubleOrNull()
                        ?.let { Math.round(it * 100) }
                    onSave(
                        item.copy(
                            name = name.trim().ifBlank { item.name },
                            quantity = quantity,
                            priceBani = priceBani
                        )
                    )
                }
            ) { Text("Salvează") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Renunță") } }
    )
}
```

- [ ] **Step 2: Add the route in `NavGraph.kt`**

Add the import with the other screen imports:

```kotlin
import com.darius.listmanager.ui.screens.InventoryScreen
```

Add the route (after the `composable("review")` block):

```kotlin
        composable("inventory") {
            InventoryScreen(onBack = { navController.popBackStack() })
        }
```

Also add the parameter pass-through for the Home entry (see Step 4): in the `composable("home")` block, add after `onNavigateToReview = { navController.navigate("review") },`:

```kotlin
                onNavigateToInventory = { navController.navigate("inventory") },
```

- [ ] **Step 3: Add the drawer item in `DrawerContent.kt`**

After the line `DrawerItem(Icons.Rounded.Inventory, "Catalog") { onNavigate("catalog") }`, add:

```kotlin
        DrawerItem(Icons.Rounded.FactCheck, "Liste inventar") { onNavigate("inventory") }
```

(`Icons.Rounded.FactCheck` comes from `material-icons-extended`, already a dependency; the wildcard import `androidx.compose.material.icons.rounded.*` at the top of the file already covers it.)

- [ ] **Step 4: Add the Home entry point in `HomeScreen.kt`**

1. Add a parameter to the `HomeScreen` signature, after `onNavigateToReview: () -> Unit,`:

```kotlin
    onNavigateToInventory: () -> Unit,
```

2. Below the existing `PrimaryButton(text = "Mergi spre sesiunea curentă", ...)` call, add a second button:

```kotlin
                PrimaryButton(
                    text = "Listă de inventar",
                    icon = Icons.Rounded.FactCheck,
                    onClick = onNavigateToInventory
                )
```

(If `PrimaryButton` requires other parameters in this file, mirror exactly the session button's call shape. `Icons.Rounded.FactCheck` is covered by the file's existing rounded-icons wildcard import; if the file imports icons individually, add the matching import.)

- [ ] **Step 5: Compile + full unit suite**

Run: `.\gradlew :app:compileDebugKotlin` then `.\gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL both; all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/darius/listmanager/ui/screens/InventoryScreen.kt app/src/main/java/com/darius/listmanager/ui/navigation/NavGraph.kt app/src/main/java/com/darius/listmanager/ui/components/DrawerContent.kt app/src/main/java/com/darius/listmanager/ui/screens/HomeScreen.kt
git commit -m "feat(inventory): inventory screen with editable table, mic capture and export"
```

---

### Task 7: Final verification + docs

**Files:**
- Modify: `docs/PROGRESS.md` (repo root, one level above android-native)

- [ ] **Step 1: Full build + full unit suite**

Run: `.\gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL; all unit tests pass; `app-debug.apk` produced.

- [ ] **Step 2: Add a PROGRESS.md entry**

At the top of `../docs/PROGRESS.md` (below the intro, above the previous newest entry), add a session entry titled `## 2026-07-05 — Inventory lists v1 (feat/inventory-lists)` summarizing: what changed (parser, math, Room v7, PDF layout, ViewModel, screen + nav/drawer/home entries), the verification gate used (compile + JVM unit tests; on-device deferred to the user), and what's next (presence + home/profile tracks in parallel; inventory sync v2 later — see `docs/superpowers/specs/2026-07-05-inventory-lists-design.md`).

- [ ] **Step 3: Commit**

```bash
git add ../docs/PROGRESS.md
git commit -m "docs: log inventory lists v1 in PROGRESS"
```

- [ ] **Step 4: Hand off**

Report: full test/build output summary, the list of commits, and the manual on-device checklist for the user:
1. Install the APK, open drawer → "Liste inventar".
2. Tap mic, say "lapte zuzu 5 4 lei 50" → row appears with value 22,50 lei and total updates.
3. Say a line without price → price cell shows highlighted "—"; tap row, fill it in.
4. Say a product not in catalog → free-text row appears.
5. Export PDF → share sheet opens; PDF shows the table + total.
6. "Listă nouă" → confirm dialog → table empties.
