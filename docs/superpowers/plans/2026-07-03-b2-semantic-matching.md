# B2 — Hybrid Semantic Matching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add on-device semantic (embedding) matching that augments the existing fuzzy matcher, so spoken products match catalog entries by meaning as well as spelling, fully offline.

**Architecture:** A pure math/search core (`VectorMath`, `EmbeddingSearch`) and a persisted `product_embeddings` cache feed an embedding-similarity signal into the existing pure `ProductRanker`. A `SentenceEmbedding`-backed `EmbeddingModel` (ONNX, bundled) produces vectors; a background backfill populates the cache. `ResolveSpokenProductUseCase` retrieves candidates from BOTH the FTS path and embedding nearest-neighbors, then ranks with the combined score. Model unavailable ⇒ silent fall back to fuzzy-only.

**Tech Stack:** Kotlin, Room, Coroutines, `io.gitlab.shubham0204:sentence-embeddings:0.0.6` (ONNX Runtime + HF tokenizer), model `paraphrase-multilingual-MiniLM-L12-v2` (384-dim). Pure logic tested with JUnit4 (`src/test`).

**Spec:** `docs/superpowers/specs/2026-07-03-b2-semantic-matching-design.md`

**Conventions (same as A1):**
- Gradle from `android-native/`: `cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew ...` (JDK 21).
- **Verification is compile + JVM-unit gate.** Pure-logic tasks are TDD'd with `testDebugUnitTest` (runs headless). Native/DB/wiring tasks are gated by `compileDebugKotlin` (Room validates DAO/SQL at compile time). Instrumented tests and on-device inference are deferred to a real device (same pre-existing WorkManager-instrumentation blocker as A1).
- Commit messages: NO `Co-Authored-By` / "Generated with Claude" trailer.
- Work happens in the `feat/b2-semantic-matching` worktree.

---

## File Structure

**Create:**
- `.../util/VectorMath.kt` — pure cosine + FloatArray↔ByteArray + L2-normalize.
- `.../util/EmbeddingSearch.kt` — pure top-K by cosine.
- `.../data/local/entity/ProductEmbeddingEntity.kt` — Room cache row.
- `.../data/local/dao/ProductEmbeddingDao.kt` — DAO.
- `.../data/repository/ProductEmbeddingRepository.kt` — typed FloatArray wrapper.
- `.../data/embedding/EmbeddingModel.kt` — ONNX inference wrapper (singleton).
- `.../data/embedding/EmbeddingBackfill.kt` — populate the cache.
- Tests: `src/test/.../util/VectorMathTest.kt`, `EmbeddingSearchTest.kt`, `.../util/ProductRankerEmbeddingTest.kt`.

**Modify:**
- `android-native/app/build.gradle.kts` — add the library dependency.
- `android-native/app/.gitignore` (or module `.gitignore`) — ignore the bundled model asset.
- `.../data/local/AppDatabase.kt` — register entity + DAO, bump version 2 → 3.
- `.../util/ProductRanker.kt` — add `embeddingScores` param + `embedding` weight + signal.
- `.../data/repository/ProductRepository.kt` — add `getAllLocal()`.
- `.../data/usecase/ResolveSpokenProductUseCase.kt` — hybrid retrieval + pass scores to ranker.
- `.../ui/viewmodel/HomeViewModel.kt` — construct embedding collaborators, trigger backfill.

Base package path: `android-native/app/src/main/java/com/darius/listmanager/`.

---

## Task 1: Dependency + model asset pipeline

**Files:**
- Modify: `android-native/app/build.gradle.kts`
- Create: `android-native/app/src/main/assets/README.md` (asset instructions)
- Modify/Create: `android-native/app/.gitignore`

- [ ] **Step 1: Add the library dependency**

In `android-native/app/build.gradle.kts`, inside the `dependencies { }` block (after the Room lines), add:

```kotlin
    // On-device sentence embeddings (ONNX Runtime + HF tokenizer)
    implementation("io.gitlab.shubham0204:sentence-embeddings:0.0.6")
```

- [ ] **Step 2: Gitignore the large model asset (it exceeds GitHub's 100MB/file limit)**

Create or append to `android-native/app/.gitignore`:

```
# Bundled ML model (downloaded at setup, not committed — see src/main/assets/README.md)
/src/main/assets/model.onnx
```

The `tokenizer.json` (a few MB) MAY be committed; the `model.onnx` (~100MB+) must NOT be. It is fetched by the developer (Step 3) and packaged into the APK at build time.

- [ ] **Step 3: Document asset acquisition (developer-run, once)**

Create `android-native/app/src/main/assets/README.md`:

```markdown
# Embedding model assets

The app bundles a sentence-embedding model for offline semantic product matching.
Two files must exist in this folder before building a release/APK that uses embeddings:

- `model.onnx`   — paraphrase-multilingual-MiniLM-L12-v2, ONNX (quantized), gitignored
- `tokenizer.json` — the matching tokenizer

## Fetch (run once, from repo root or here):

    curl -L -o model.onnx \
      "https://huggingface.co/onnx-models/paraphrase-multilingual-MiniLM-L12-v2-onnx/resolve/main/model.onnx"
    curl -L -o tokenizer.json \
      "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/tokenizer.json"

## Verify the output tensor name

`EmbeddingModel` calls `init(..., outputTensorName = "sentence_embedding")`. If your
ONNX export names its output differently, update that constant. Inspect with Netron
(https://netron.app) or:

    python -c "import onnx; m=onnx.load('model.onnx'); print([o.name for o in m.graph.output])"

If the app logs `Embedding model init failed`, matching silently falls back to
fuzzy-only — the app still works, just without semantic matching.
```

- [ ] **Step 4: Verify the project still builds without the model present**

The model is loaded at runtime, so the build must succeed even before `model.onnx` is downloaded.

Run: `cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL. The dependency resolves from **Maven Central** (already configured; no repo change needed). Only versions `v6` and `0.0.6` exist — use `0.0.6`.

**Prerequisite for a fresh worktree:** the project references `com.darius.listmanager.network.ApiConfig`, a **gitignored local file** not in the repo (see PR #2 / ApiConfig setup docs). A fresh worktree fails with `Unresolved reference 'ApiConfig'` until it is copied in from the main checkout: `android-native/app/src/main/java/com/darius/listmanager/network/ApiConfig.kt`.

- [ ] **Step 5: Commit**

```bash
git add android-native/app/build.gradle.kts android-native/app/.gitignore android-native/app/src/main/assets/README.md
git commit -m "build: add sentence-embeddings dependency and model asset pipeline"
```

---

## Task 2: VectorMath (pure) + unit tests

**Files:**
- Create: `android-native/app/src/main/java/com/darius/listmanager/util/VectorMath.kt`
- Test: `android-native/app/src/test/java/com/darius/listmanager/util/VectorMathTest.kt`

- [ ] **Step 1: Write the failing test**

`android-native/app/src/test/java/com/darius/listmanager/util/VectorMathTest.kt`:

```kotlin
package com.darius.listmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VectorMathTest {

    @Test
    fun cosine_identicalVectors_isOne() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertEquals(1.0f, VectorMath.cosineSimilarity(v, v), 1e-5f)
    }

    @Test
    fun cosine_orthogonalVectors_isZero() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(a, b), 1e-5f)
    }

    @Test
    fun cosine_oppositeVectors_isNegativeOne() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(-1f, 0f)
        assertEquals(-1.0f, VectorMath.cosineSimilarity(a, b), 1e-5f)
    }

    @Test
    fun cosine_mismatchedOrEmpty_isZero() {
        assertEquals(0.0f, VectorMath.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f)), 1e-6f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(floatArrayOf(), floatArrayOf()), 1e-6f)
    }

    @Test
    fun bytes_roundTrip_preservesFloats() {
        val v = floatArrayOf(1.5f, -2.25f, 0f, 3.125f)
        val round = VectorMath.bytesToFloats(VectorMath.floatsToBytes(v))
        assertArrayEquals(v, round, 0f)
    }

    @Test
    fun l2Normalize_producesUnitLength() {
        val v = floatArrayOf(3f, 4f)
        val n = VectorMath.l2Normalize(v)
        assertEquals(1.0f, VectorMath.cosineSimilarity(n, n), 1e-5f)
        assertEquals(0.6f, n[0], 1e-5f)
        assertEquals(0.8f, n[1], 1e-5f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

`cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew testDebugUnitTest --tests "com.darius.listmanager.util.VectorMathTest" --console=plain`
Expected: FAIL — unresolved reference `VectorMath`.

- [ ] **Step 3: Write the implementation**

`android-native/app/src/main/java/com/darius/listmanager/util/VectorMath.kt`:

```kotlin
package com.darius.listmanager.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Pure vector helpers for embedding similarity and storage. No Android deps. */
object VectorMath {

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return dot / (sqrt(na) * sqrt(nb))
    }

    fun l2Normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        val norm = sqrt(n)
        if (norm == 0f) return v.copyOf()
        return FloatArray(v.size) { v[it] / norm }
    }

    fun floatsToBytes(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) bb.putFloat(x)
        return bb.array()
    }

    fun bytesToFloats(b: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(b.size / 4)
        for (i in out.indices) out[i] = bb.float
        return out
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

`cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew testDebugUnitTest --tests "com.darius.listmanager.util.VectorMathTest" --console=plain`
Expected: BUILD SUCCESSFUL, 6 tests passed.

- [ ] **Step 5: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/util/VectorMath.kt android-native/app/src/test/java/com/darius/listmanager/util/VectorMathTest.kt
git commit -m "feat(embed): add pure VectorMath with unit tests"
```

---

## Task 3: EmbeddingSearch (pure) + unit tests

**Files:**
- Create: `android-native/app/src/main/java/com/darius/listmanager/util/EmbeddingSearch.kt`
- Test: `android-native/app/src/test/java/com/darius/listmanager/util/EmbeddingSearchTest.kt`

- [ ] **Step 1: Write the failing test**

`android-native/app/src/test/java/com/darius/listmanager/util/EmbeddingSearchTest.kt`:

```kotlin
package com.darius.listmanager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingSearchTest {

    private val query = floatArrayOf(1f, 0f)
    private val candidates = listOf(
        7L to floatArrayOf(1f, 0f),    // identical -> 1.0
        8L to floatArrayOf(0.7f, 0.7f), // ~0.707
        9L to floatArrayOf(0f, 1f)     // orthogonal -> 0.0
    )

    @Test
    fun topK_ordersByCosineDescending() {
        val result = EmbeddingSearch.topK(query, candidates, k = 3)
        assertEquals(listOf(7L, 8L, 9L), result.map { it.productId })
    }

    @Test
    fun topK_limitsToK() {
        val result = EmbeddingSearch.topK(query, candidates, k = 2)
        assertEquals(2, result.size)
        assertEquals(listOf(7L, 8L), result.map { it.productId })
    }

    @Test
    fun topK_emptyCandidates_returnsEmpty() {
        assertEquals(emptyList<Long>(), EmbeddingSearch.topK(query, emptyList(), k = 5).map { it.productId })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

`cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew testDebugUnitTest --tests "com.darius.listmanager.util.EmbeddingSearchTest" --console=plain`
Expected: FAIL — unresolved reference `EmbeddingSearch`.

- [ ] **Step 3: Write the implementation**

`android-native/app/src/main/java/com/darius/listmanager/util/EmbeddingSearch.kt`:

```kotlin
package com.darius.listmanager.util

/** Pure brute-force nearest-neighbor search over cached product vectors. */
object EmbeddingSearch {

    data class Scored(val productId: Long, val score: Float)

    fun topK(query: FloatArray, candidates: List<Pair<Long, FloatArray>>, k: Int): List<Scored> {
        return candidates
            .map { (id, vec) -> Scored(id, VectorMath.cosineSimilarity(query, vec)) }
            .sortedByDescending { it.score }
            .take(k)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

`cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew testDebugUnitTest --tests "com.darius.listmanager.util.EmbeddingSearchTest" --console=plain`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/util/EmbeddingSearch.kt android-native/app/src/test/java/com/darius/listmanager/util/EmbeddingSearchTest.kt
git commit -m "feat(embed): add pure EmbeddingSearch with unit tests"
```

---

## Task 4: ProductEmbedding entity + DAO + DB registration

**Files:**
- Create: `.../data/local/entity/ProductEmbeddingEntity.kt`
- Create: `.../data/local/dao/ProductEmbeddingDao.kt`
- Modify: `.../data/local/AppDatabase.kt`

- [ ] **Step 1: Create the entity**

`android-native/app/src/main/java/com/darius/listmanager/data/local/entity/ProductEmbeddingEntity.kt`:

```kotlin
package com.darius.listmanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Cached embedding vector for a product. One row per product per model version. */
@Entity(
    tableName = "product_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ProductEmbeddingEntity(
    @PrimaryKey
    val productId: Long,
    val vector: ByteArray,
    val modelVersion: String
) {
    // ByteArray needs structural equals/hashCode for Room + correctness.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProductEmbeddingEntity) return false
        return productId == other.productId &&
            vector.contentEquals(other.vector) &&
            modelVersion == other.modelVersion
    }

    override fun hashCode(): Int {
        var result = productId.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + modelVersion.hashCode()
        return result
    }
}
```

- [ ] **Step 2: Create the DAO**

`android-native/app/src/main/java/com/darius/listmanager/data/local/dao/ProductEmbeddingDao.kt`:

```kotlin
package com.darius.listmanager.data.local.dao

import androidx.room.*
import com.darius.listmanager.data.local.entity.ProductEmbeddingEntity

@Dao
interface ProductEmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ProductEmbeddingEntity)

    @Query("SELECT * FROM product_embeddings WHERE modelVersion = :modelVersion")
    suspend fun getAllForVersion(modelVersion: String): List<ProductEmbeddingEntity>

    @Query(
        """
        SELECT p.id FROM products p
        LEFT JOIN product_embeddings e
          ON p.id = e.productId AND e.modelVersion = :modelVersion
        WHERE e.productId IS NULL
        """
    )
    suspend fun getMissingProductIds(modelVersion: String): List<Long>

    @Query("DELETE FROM product_embeddings WHERE productId = :productId")
    suspend fun deleteByProductId(productId: Long)

    @Query("DELETE FROM product_embeddings")
    suspend fun deleteAll()
}
```

- [ ] **Step 3: Register in AppDatabase and bump version**

In `.../data/local/AppDatabase.kt`: add `ProductEmbeddingEntity::class` to the `entities` array, change `version = 2` to `version = 3`, and add the accessor `abstract fun productEmbeddingDao(): ProductEmbeddingDao`. The existing wildcard imports (`...entity.*`, `...dao.*`) cover the new classes, and `.fallbackToDestructiveMigration()` handles the schema change (no manual Migration).

The `entities` list and accessors currently read (base = development):
```kotlin
    entities = [
        DistributorEntity::class,
        ProductEntity::class,
        ProductFts::class,
        SessionEntity::class,
        SessionItemEntity::class,
        UnknownProductEntity::class,
        PendingOperationEntity::class
    ],
    version = 2,
```
Change to:
```kotlin
    entities = [
        DistributorEntity::class,
        ProductEntity::class,
        ProductFts::class,
        SessionEntity::class,
        SessionItemEntity::class,
        UnknownProductEntity::class,
        PendingOperationEntity::class,
        ProductEmbeddingEntity::class
    ],
    version = 3,
```
And add after `abstract fun pendingOperationDao(): PendingOperationDao`:
```kotlin
    abstract fun productEmbeddingDao(): ProductEmbeddingDao
```

- [ ] **Step 4: Verify compile (Room validates the DAO SQL)**

`cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/data/local/entity/ProductEmbeddingEntity.kt android-native/app/src/main/java/com/darius/listmanager/data/local/dao/ProductEmbeddingDao.kt android-native/app/src/main/java/com/darius/listmanager/data/local/AppDatabase.kt
git commit -m "feat(db): add product_embeddings table"
```

---

## Task 5: ProductEmbeddingRepository

**Files:**
- Create: `.../data/repository/ProductEmbeddingRepository.kt`

- [ ] **Step 1: Create the repository**

`android-native/app/src/main/java/com/darius/listmanager/data/repository/ProductEmbeddingRepository.kt`:

```kotlin
package com.darius.listmanager.data.repository

import com.darius.listmanager.data.local.dao.ProductEmbeddingDao
import com.darius.listmanager.data.local.entity.ProductEmbeddingEntity
import com.darius.listmanager.util.VectorMath

class ProductEmbeddingRepository(private val dao: ProductEmbeddingDao) {

    suspend fun upsert(productId: Long, vector: FloatArray, modelVersion: String) {
        dao.upsert(
            ProductEmbeddingEntity(
                productId = productId,
                vector = VectorMath.floatsToBytes(vector),
                modelVersion = modelVersion
            )
        )
    }

    /** All cached vectors for [modelVersion] as (productId, vector) pairs. */
    suspend fun getAllForVersion(modelVersion: String): List<Pair<Long, FloatArray>> =
        dao.getAllForVersion(modelVersion).map { it.productId to VectorMath.bytesToFloats(it.vector) }

    suspend fun getMissingProductIds(modelVersion: String): List<Long> =
        dao.getMissingProductIds(modelVersion)

    suspend fun deleteByProductId(productId: Long) = dao.deleteByProductId(productId)
}
```

- [ ] **Step 2: Verify compile**

`cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/data/repository/ProductEmbeddingRepository.kt
git commit -m "feat(embed): add ProductEmbeddingRepository"
```

---

## Task 6: ProductRanker embedding signal + unit tests

**Files:**
- Modify: `.../util/ProductRanker.kt`
- Test: `android-native/app/src/test/java/com/darius/listmanager/util/ProductRankerEmbeddingTest.kt`

The change adds an optional `embeddingScores: Map<Long, Double>` (productId → cosine) and an `embedding` weight. The final score becomes `maxOf(fuzzyFinal, embeddingContribution)`, so an **empty map reproduces today's behavior exactly** (regression-safe) while a strong embedding score can raise a semantically-related product.

- [ ] **Step 1: Write the failing test**

`android-native/app/src/test/java/com/darius/listmanager/util/ProductRankerEmbeddingTest.kt`:

```kotlin
package com.darius.listmanager.util

import com.darius.listmanager.data.local.entity.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductRankerEmbeddingTest {

    private val orange = ProductEntity(id = 1L, name = "suc portocale", distributorId = 1L, aliases = "orange juice")
    private val water = ProductEntity(id = 2L, name = "apa plata", distributorId = 1L, aliases = "")
    private val products = listOf(orange, water)

    @Test
    fun emptyEmbeddingScores_reproducesBaselineOrder() {
        val baseline = ProductRanker.rank("apa plata", products)
        val withEmptyMap = ProductRanker.rank("apa plata", products, emptyMap())
        assertEquals(
            baseline.map { it.product.id to it.score },
            withEmptyMap.map { it.product.id to it.score }
        )
    }

    @Test
    fun strongEmbeddingScore_raisesSemanticMatch() {
        // Query lexically unlike "suc portocale", but embeddings say it's the orange product.
        val scores = mapOf(1L to 0.9, 2L to 0.05)
        val ranked = ProductRanker.rank("orange juice drink", products, scores)
        assertEquals("orange product should rank first", 1L, ranked.first().product.id)
        assertTrue("embedding should lift its score", ranked.first().score >= 0.9 - 1e-6)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

`cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew testDebugUnitTest --tests "com.darius.listmanager.util.ProductRankerEmbeddingTest" --console=plain`
Expected: FAIL — `rank` has no 3-arg (Map) overload.

- [ ] **Step 3: Modify ProductRanker**

Edit `.../util/ProductRanker.kt`:

(a) Change the `rank` signature and thread the per-product embedding score in. Replace:
```kotlin
    fun rank(spokenText: String, products: List<ProductEntity>, weights: ScoringWeights = ScoringWeights()): List<RankedProduct> {
        val normalizedSpoken = TextNorm.normalize(spokenText)

        return products.map { product ->
            val score = calculateScore(normalizedSpoken, product, weights)
            RankedProduct(
                product = product,
                score = score.total,
                breakdown = score.breakdown
            )
        }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
    }
```
with:
```kotlin
    fun rank(
        spokenText: String,
        products: List<ProductEntity>,
        embeddingScores: Map<Long, Double> = emptyMap(),
        weights: ScoringWeights = ScoringWeights()
    ): List<RankedProduct> {
        val normalizedSpoken = TextNorm.normalize(spokenText)

        return products.map { product ->
            val embeddingSim = embeddingScores[product.id] ?: 0.0
            val score = calculateScore(normalizedSpoken, product, embeddingSim, weights)
            RankedProduct(
                product = product,
                score = score.total,
                breakdown = score.breakdown
            )
        }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
    }
```

(b) Change `calculateScore` to accept and apply the embedding signal. Replace its signature line:
```kotlin
    private fun calculateScore(
        normalizedSpoken: String,
        product: ProductEntity,
        weights: ScoringWeights
    ): Score {
```
with:
```kotlin
    private fun calculateScore(
        normalizedSpoken: String,
        product: ProductEntity,
        embeddingSim: Double,
        weights: ScoringWeights
    ): Score {
```

(c) Replace the final-score computation and the returned `Score`. Find:
```kotlin
        // Take the best of name score or alias score
        val finalScore = maxOf(nameScore, maxAliasScore)

        return Score(
            total = finalScore,
            breakdown = mapOf(
                "levenshtein" to nameLevenshtein,
                "jaccard" to nameJaccard,
                "token" to nameToken,
                "phonetic" to namePhonetic,
                "prefix" to namePrefix,
                "contains" to containsBonus,
                "number" to numberSim,
                "aliasMax" to maxAliasScore,
                "final" to finalScore
            )
        )
```
and replace with:
```kotlin
        // Embedding contribution (0 when no cached vector / no model → regression-safe)
        val embeddingContribution = (embeddingSim * weights.embedding).coerceIn(0.0, 1.0)

        // Take the best of fuzzy name score, alias score, or embedding similarity
        val finalScore = maxOf(nameScore, maxAliasScore, embeddingContribution)

        return Score(
            total = finalScore,
            breakdown = mapOf(
                "levenshtein" to nameLevenshtein,
                "jaccard" to nameJaccard,
                "token" to nameToken,
                "phonetic" to namePhonetic,
                "prefix" to namePrefix,
                "contains" to containsBonus,
                "number" to numberSim,
                "aliasMax" to maxAliasScore,
                "embedding" to embeddingContribution,
                "final" to finalScore
            )
        )
```

(d) Add the `embedding` weight to `ScoringWeights`. Replace:
```kotlin
data class ScoringWeights(
    val levenshtein: Double = 1.5,  // Edit distance - high weight
    val jaccard: Double = 1.2,      // Character n-grams
    val token: Double = 1.3,        // Token matching
    val phonetic: Double = 0.8,     // Phonetic similarity
    val prefix: Double = 0.7,       // Prefix matching
    val containsBonus: Double = 2.0,// Contains/exact match bonus - highest
    val numberSeq: Double = 1.0     // Number sequence matching
)
```
with:
```kotlin
data class ScoringWeights(
    val levenshtein: Double = 1.5,  // Edit distance - high weight
    val jaccard: Double = 1.2,      // Character n-grams
    val token: Double = 1.3,        // Token matching
    val phonetic: Double = 0.8,     // Phonetic similarity
    val prefix: Double = 0.7,       // Prefix matching
    val containsBonus: Double = 2.0,// Contains/exact match bonus - highest
    val numberSeq: Double = 1.0,    // Number sequence matching
    val embedding: Double = 1.0     // Semantic embedding similarity
)
```

- [ ] **Step 4: Run test to verify it passes**

`cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew testDebugUnitTest --tests "com.darius.listmanager.util.ProductRankerEmbeddingTest" --console=plain`
Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/util/ProductRanker.kt android-native/app/src/test/java/com/darius/listmanager/util/ProductRankerEmbeddingTest.kt
git commit -m "feat(match): add embedding-similarity signal to ProductRanker"
```

---

## Task 7: EmbeddingModel wrapper (ONNX inference)

**Files:**
- Create: `.../data/embedding/EmbeddingModel.kt`

Native inference is verified on-device (deferred); this task is compile-gated. Uses the `SentenceEmbedding` API: `suspend init(modelFilepath, tokenizerBytes, useTokenTypeIds, outputTensorName, useFP16, useXNNPack)` and `suspend encode(text): FloatArray`. The ONNX model is bundled in `assets/`; `init` needs a file path, so the model is copied `assets → filesDir` once. `useTokenTypeIds = false` (the model is XLM-R based).

- [ ] **Step 1: Create the wrapper**

`android-native/app/src/main/java/com/darius/listmanager/data/embedding/EmbeddingModel.kt`:

```kotlin
package com.darius.listmanager.data.embedding

import android.content.Context
import android.util.Log
import com.darius.listmanager.util.VectorMath
import com.ml.shubham0204.sentence_embeddings.SentenceEmbedding
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Loads the bundled ONNX sentence-embedding model once and produces normalized
 * vectors. All failures degrade gracefully to null so callers fall back to
 * fuzzy-only matching. Singleton so the model is loaded at most once per process.
 */
class EmbeddingModel private constructor(private val appContext: Context) {

    private val embedder = SentenceEmbedding()
    private val initMutex = Mutex()

    @Volatile
    private var ready = false

    /** Ensure the model+tokenizer are loaded. Returns false if unavailable. */
    suspend fun ensureReady(): Boolean {
        if (ready) return true
        initMutex.withLock {
            if (ready) return true
            try {
                val modelFile = File(appContext.filesDir, MODEL_ASSET)
                if (!modelFile.exists()) {
                    appContext.assets.open(MODEL_ASSET).use { input ->
                        modelFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val tokenizerBytes = appContext.assets.open(TOKENIZER_ASSET).use { it.readBytes() }
                embedder.init(
                    modelFilepath = modelFile.absolutePath,
                    tokenizerBytes = tokenizerBytes,
                    useTokenTypeIds = false,
                    outputTensorName = "sentence_embedding",
                    useFP16 = false,
                    useXNNPack = true
                )
                ready = true
            } catch (e: Throwable) {
                Log.e(TAG, "Embedding model init failed; falling back to fuzzy-only", e)
                ready = false
            }
        }
        return ready
    }

    /** Embed [text] to an L2-normalized vector, or null if the model is unavailable. */
    suspend fun embed(text: String): FloatArray? {
        if (text.isBlank()) return null
        if (!ensureReady()) return null
        return try {
            VectorMath.l2Normalize(embedder.encode(text))
        } catch (e: Throwable) {
            Log.e(TAG, "encode failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "EmbeddingModel"
        const val MODEL_ASSET = "model.onnx"
        const val TOKENIZER_ASSET = "tokenizer.json"
        const val MODEL_VERSION = "minilm-multilingual-v1"

        @Volatile
        private var INSTANCE: EmbeddingModel? = null

        fun getInstance(context: Context): EmbeddingModel =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: EmbeddingModel(context.applicationContext).also { INSTANCE = it }
            }
    }
}
```

- [ ] **Step 2: Verify compile**

`cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL. If the `com.ml.shubham0204.sentence_embeddings.SentenceEmbedding` import is unresolved, confirm Task 1's dependency line is present and re-sync.

- [ ] **Step 3: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/data/embedding/EmbeddingModel.kt
git commit -m "feat(embed): add EmbeddingModel ONNX wrapper (graceful fallback)"
```

---

## Task 8: EmbeddingBackfill

**Files:**
- Create: `.../data/embedding/EmbeddingBackfill.kt`

- [ ] **Step 1: Create the backfill**

`android-native/app/src/main/java/com/darius/listmanager/data/embedding/EmbeddingBackfill.kt`:

```kotlin
package com.darius.listmanager.data.embedding

import android.util.Log
import com.darius.listmanager.data.local.dao.ProductDao
import com.darius.listmanager.data.repository.ProductEmbeddingRepository

/**
 * Computes and caches embeddings for products that don't yet have one for the
 * current model version. Idempotent and safe to call on every app start.
 * No-op (silent) if the model is unavailable.
 */
class EmbeddingBackfill(
    private val productDao: ProductDao,
    private val embeddingRepository: ProductEmbeddingRepository,
    private val embeddingModel: EmbeddingModel
) {
    suspend fun run() {
        if (!embeddingModel.ensureReady()) {
            Log.d(TAG, "Model not ready; skipping backfill")
            return
        }
        val missingIds = embeddingRepository.getMissingProductIds(EmbeddingModel.MODEL_VERSION).toSet()
        if (missingIds.isEmpty()) return

        val products = productDao.getAll().filter { it.id in missingIds }
        Log.d(TAG, "Backfilling ${products.size} product embeddings")
        for (product in products) {
            val text = buildString {
                append(product.name)
                val aliases = product.aliases
                if (!aliases.isNullOrBlank()) {
                    append(' ')
                    append(aliases.replace(',', ' '))
                }
            }
            val vector = embeddingModel.embed(text) ?: continue
            embeddingRepository.upsert(product.id, vector, EmbeddingModel.MODEL_VERSION)
        }
        Log.d(TAG, "Backfill complete")
    }

    companion object { private const val TAG = "EmbeddingBackfill" }
}
```

- [ ] **Step 2: Verify compile**

`cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/data/embedding/EmbeddingBackfill.kt
git commit -m "feat(embed): add EmbeddingBackfill for the product vector cache"
```

---

## Task 9: Hybrid retrieval in ResolveSpokenProductUseCase

**Files:**
- Modify: `.../data/repository/ProductRepository.kt` (add `getAllLocal()`)
- Modify: `.../data/usecase/ResolveSpokenProductUseCase.kt`

- [ ] **Step 1: Add a local-only product accessor**

In `.../data/repository/ProductRepository.kt`, add this method inside the class (the class already has `private val dao: ProductDao` and a `getAllFlow()` using `dao`):
```kotlin
    /** Local products only — never touches the network (used by embedding retrieval). */
    suspend fun getAllLocal(): List<ProductEntity> = dao.getAll()
```
(If `ProductEntity` is not already imported in this file, add `import com.darius.listmanager.data.local.entity.ProductEntity`.)

- [ ] **Step 2: Add embedding collaborators + hybrid retrieval to the use case**

Edit `.../data/usecase/ResolveSpokenProductUseCase.kt`.

(a) Add imports near the top:
```kotlin
import com.darius.listmanager.data.embedding.EmbeddingModel
import com.darius.listmanager.data.repository.ProductEmbeddingRepository
import com.darius.listmanager.util.EmbeddingSearch
import com.darius.listmanager.util.VectorMath
```

(b) Add optional constructor params (defaults keep existing callers — e.g. `Milestone3IntegrationTest` — working, and give fuzzy-only fallback):
```kotlin
class ResolveSpokenProductUseCase(
    private val productRepository: ProductRepository,
    private val embeddingModel: EmbeddingModel? = null,
    private val embeddingRepository: ProductEmbeddingRepository? = null
) {
```
(Replace the current `class ResolveSpokenProductUseCase( private val productRepository: ProductRepository ) {` line.)

(c) Add a constant in the `companion object` (next to the existing thresholds):
```kotlin
        private const val EMB_TOP_K = 10
```

(d) After the FTS candidate list is built and before ranking, compute embedding scores and add embedding neighbors to the candidate set. Find the block that ends the candidate selection — currently:
```kotlin
        if (candidateProducts.isEmpty()) {
            android.util.Log.d("ResolveUseCase", "No candidates -> Unknown")
            return ResolveResult.Unknown(spokenText)
        }

        // Step 4: Rank products by similarity
        val rankedProducts = ProductRanker.rank(spokenText, candidateProducts)
```
Replace that with:
```kotlin
        // Step 3.5: Semantic retrieval (hybrid). If no model/vectors, this is a no-op.
        var embeddingScores: Map<Long, Double> = emptyMap()
        var embeddingCandidates: List<ProductEntity> = emptyList()
        val queryVec = embeddingModel?.embed(spokenText)
        if (queryVec != null && embeddingRepository != null) {
            val cached = embeddingRepository.getAllForVersion(EmbeddingModel.MODEL_VERSION)
            if (cached.isNotEmpty()) {
                embeddingScores = cached.associate { (id, vec) ->
                    id to VectorMath.cosineSimilarity(queryVec, vec).toDouble()
                }
                val topIds = EmbeddingSearch.topK(queryVec, cached, EMB_TOP_K).map { it.productId }.toSet()
                if (topIds.isNotEmpty()) {
                    val localById = productRepository.getAllLocal().associateBy { it.id }
                    embeddingCandidates = topIds.mapNotNull { localById[it] }
                }
            }
        }

        val allCandidates = (candidateProducts + embeddingCandidates).distinctBy { it.id }

        if (allCandidates.isEmpty()) {
            android.util.Log.d("ResolveUseCase", "No candidates -> Unknown")
            return ResolveResult.Unknown(spokenText)
        }

        // Step 4: Rank products by similarity (fuzzy + embedding)
        val rankedProducts = ProductRanker.rank(spokenText, allCandidates, embeddingScores)
```
Note: the original early `if (candidateProducts.isEmpty()) return Unknown` guard is now replaced by the `allCandidates.isEmpty()` guard above (embedding neighbors can rescue a case where FTS found nothing). Leave the rest of the method (the threshold decision on `rankedProducts`) unchanged.

- [ ] **Step 3: Verify compile**

`cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/data/repository/ProductRepository.kt android-native/app/src/main/java/com/darius/listmanager/data/usecase/ResolveSpokenProductUseCase.kt
git commit -m "feat(match): hybrid FTS+embedding retrieval in ResolveSpokenProductUseCase"
```

---

## Task 10: Wire collaborators + backfill trigger in HomeViewModel

**Files:**
- Modify: `.../ui/viewmodel/HomeViewModel.kt`

This is the base (development) `HomeViewModel`. It constructs `resolveSpokenProductUseCase = ResolveSpokenProductUseCase(productRepository)` and has an `init { }` block. We build the embedding collaborators, pass them to the use case, and kick off the backfill.

- [ ] **Step 1: Add imports**

Near the other imports in `HomeViewModel.kt`:
```kotlin
import com.darius.listmanager.data.embedding.EmbeddingBackfill
import com.darius.listmanager.data.embedding.EmbeddingModel
import com.darius.listmanager.data.repository.ProductEmbeddingRepository
import kotlinx.coroutines.Dispatchers
```

- [ ] **Step 2: Construct the embedding collaborators and pass them to the use case**

Find:
```kotlin
    private val resolveSpokenProductUseCase = ResolveSpokenProductUseCase(productRepository)
```
Replace with:
```kotlin
    private val embeddingModel = EmbeddingModel.getInstance(application)
    private val productEmbeddingRepository = ProductEmbeddingRepository(database.productEmbeddingDao())
    private val resolveSpokenProductUseCase =
        ResolveSpokenProductUseCase(productRepository, embeddingModel, productEmbeddingRepository)
    private val embeddingBackfill =
        EmbeddingBackfill(database.productDao(), productEmbeddingRepository, embeddingModel)
```
(`application` is the `AndroidViewModel` constructor parameter; `database` already exists in this class.)

- [ ] **Step 3: Trigger the backfill on init**

At the end of the existing `init { }` block, add:
```kotlin
        // Warm the embedding cache in the background (no-op if model unavailable)
        viewModelScope.launch(Dispatchers.IO) {
            embeddingBackfill.run()
        }
```
(`viewModelScope` and `launch` are already used in this file; `Dispatchers` import was added in Step 1.)

- [ ] **Step 4: Verify compile**

`cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/ui/viewmodel/HomeViewModel.kt
git commit -m "feat(home): wire embedding model + backfill into HomeViewModel"
```

---

## Task 11: Full build + verification

**Files:** none (verification only).

- [ ] **Step 1: Assemble the debug APK + run all unit tests**

```
cd android-native && export ANDROID_HOME="C:\Users\dariu\AppData\Local\Android\Sdk" && ./gradlew assembleDebug testDebugUnitTest --console=plain
```
Expected: BUILD SUCCESSFUL; all unit tests green (`VectorMathTest`, `EmbeddingSearchTest`, `ProductRankerEmbeddingTest`). This builds even without `model.onnx` present (the model loads at runtime).

- [ ] **Step 2: (Developer, on device) Provide the model assets and smoke-test**

1. Download `model.onnx` + `tokenizer.json` into `android-native/app/src/main/assets/` per `assets/README.md`.
2. Verify the ONNX output tensor is named `sentence_embedding` (else update `EmbeddingModel.MODEL_VERSION`'s sibling constant `outputTensorName`); confirm no `Embedding model init failed` log on launch.
3. Install on a device, open the app (Home) — the backfill runs once in the background.
4. Say a product using a **synonym/paraphrase** not in the catalog name but semantically close (e.g. an item aliased "orange juice" spoken as "suc de portocale"). Confirm it now surfaces as a match/suggestion where fuzzy-only previously returned Unknown.
5. Confirm product-code/dimension items (e.g. "șurub 6x100") still match exactly (no regression).
6. Toggle airplane mode — confirm matching still works (fully offline).

- [ ] **Step 3: (If needed) Commit fixes found during device testing**

```bash
git add -A
git commit -m "fix(b2): address issues found during on-device verification"
```

---

## Notes / deferred

- **Threshold re-tuning:** kept at 0.82 / 0.60. Because the embedding term enters via `maxOf(...)`, it can only *raise* scores and *add* candidates, so existing fuzzy behavior is unchanged; embedding-only matches will mostly land in the Suggestions band. Tune on device with real data if needed.
- **Edit invalidation:** backfill fills products with **no** current-version vector, so newly-added products are embedded on the next launch. Renaming an existing product leaves its old vector until the model version changes; the fuzzy signals still match the new name. Immediate edit-invalidation (delete the row in the product-edit path) is a small follow-up, out of scope here.
- **model2vec fallback:** if ~100MB is too large, swap the asset for a multilingual model2vec export and bump `MODEL_VERSION` (forces a re-backfill). No code change beyond the constant and the asset.
- **Merge with A1:** both branches touch `ResolveSpokenProductUseCase` and `HomeViewModel`; resolve the small conflicts at merge. B2's `AppDatabase` bump to v3 also overlaps A1's v3 — when both merge, the combined DB should include both `needs_review` and `product_embeddings` and use a higher version number.
- **ANN index:** brute-force cosine is fine at this catalog size; revisit only at tens of thousands of products.
