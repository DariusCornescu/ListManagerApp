# B2 — Hybrid Semantic Product Matching (local embedding model)

**Date:** 2026-07-03
**Status:** Approved design, pending implementation plan
**Scope:** Android app (`android-native`) only. No backend/hosting changes.
**Branch:** `feat/b2-semantic-matching` (off `development`, independent of A1 / PR #3)

## Goal

Add on-device semantic matching so spoken products match catalog entries by
*meaning*, not just spelling — e.g. "suc de portocale" matches a product aliased
"orange juice" — something the current fuzzy engine (Levenshtein / Jaccard /
phonetic) cannot do. Embeddings **augment** the existing fuzzy matching; they do
not replace it, so product codes and dimensions (e.g. "șurub 6x100",
"cauciuc 27.5 x 2.25") keep their current exact/number/prefix accuracy.

Runs fully offline (model bundled in the APK).

## Background — current matching pipeline

- `ResolveSpokenProductUseCase.execute(spokenText)`: generates query variants,
  runs an FTS search to get candidate products, ranks them with `ProductRanker`,
  and decides AutoAdd (≥0.82) / Suggestions (≥0.60) / Unknown (<0.60).
- `ProductRanker.rank(spokenText, products, weights)` scores each candidate with
  weighted fuzzy signals (Levenshtein, Jaccard, token, phonetic, prefix,
  contains-bonus, number-seq) plus alias matching. It is a **pure** object that
  only depends on `ProductEntity` and `TextNorm`/`SimilarityEngine`.
- Products: `ProductEntity(id, name, distributorId, aliases)`; `aliases` is a
  comma-separated string.

## Key decisions (confirmed with user)

1. **Model is bundled in the APK; app stays 100% offline** (~90–120 MB).
2. **Hybrid**: embeddings augment fuzzy matching, not replace it. Retrieve
   candidates from BOTH the FTS/variant path AND embedding nearest-neighbors,
   then rank with a combined score.
3. **Runtime:** ONNX Runtime via the `shubham0204/Sentence-Embeddings-Android`
   library, model `paraphrase-multilingual-MiniLM-L12-v2` (ONNX, quantized),
   with its `tokenizer.json`. The library wraps ONNX inference and the HF
   tokenizer. 384-dim, multilingual (Romanian included).
   - Size lever (not chosen now): the same library supports multilingual
     `model2vec` static embeddings (~15–30 MB) as a lighter fallback if APK
     size becomes a problem.
4. **Product embeddings are persisted** in a new Room table, with background
   backfill.

## Components (isolated, testable units)

### 1. `EmbeddingModel` — inference wrapper
- Singleton; loads the ONNX model + tokenizer from `assets/` exactly once.
- `suspend fun embed(text: String): FloatArray?` — returns an L2-normalized
  384-dim vector, or `null` if the model failed to load (graceful degradation).
- Runs inference on `Dispatchers.Default`/IO.
- Depends only on the embeddings library; no app-DB knowledge.

### 2. `ProductEmbeddingEntity` + `ProductEmbeddingDao` + `ProductEmbeddingRepository`
- New Room table `product_embeddings`:
  - `productId: Long` (PrimaryKey, FK → `products.id`, `onDelete = CASCADE`)
  - `vector: ByteArray` (FloatArray serialized little-endian)
  - `modelVersion: String` (so a future model swap can invalidate cached vectors)
- DAO: `upsert`, `getAll(): List<ProductEmbeddingEntity>`, `getByIds(ids)`,
  `deleteById`, `getMissingProductIds()` (products with no current-version row).
- Repository exposes typed `FloatArray` (handles ByteArray↔FloatArray).
- Registered in `AppDatabase` (bump version; existing
  `fallbackToDestructiveMigration()` handles the schema change).

### 3. `VectorMath` — pure helpers
- `cosineSimilarity(a: FloatArray, b: FloatArray): Float` (dot product for
  normalized vectors).
- `floatsToBytes` / `bytesToFloats` serialization.
- Pure Kotlin, no Android deps → JVM-unit-testable.

### 4. `EmbeddingBackfill` — cache maintenance
- Embed text per product = `name + " " + aliases` (aliases comma→space).
- On first launch after the model ships, embed all products missing a current
  vector (`getMissingProductIds()`), in the background, batched, with a simple
  progress signal.
- Incremental: when a product is created/edited, recompute its vector; on delete
  the FK cascade removes it.
- If `EmbeddingModel.embed` returns null (model unavailable), backfill is a no-op
  and matching silently falls back to fuzzy-only.

### 5. Retrieval + ranking integration
- **`EmbeddingSearch`**: given a query `FloatArray` and the cached product
  vectors, returns top-K product ids by cosine (brute-force over all cached
  vectors — catalog is hundreds–low-thousands, <10 ms).
- **`ResolveSpokenProductUseCase`** changes:
  1. Embed the spoken query once (`EmbeddingModel.embed`).
  2. Candidate set = **union** of today's FTS/variant candidates **and** the
     top-K embedding neighbors (this is what adds semantic recall).
  3. Compute `Map<productId, cosine>` for the candidates.
  4. Call `ProductRanker.rank(spokenText, candidates, embeddingScores, weights)`.
  5. Decide AutoAdd / Suggestions / Unknown with **re-tuned thresholds**.
- **`ProductRanker` stays pure**: it gains an optional
  `embeddingScores: Map<Long, Double> = emptyMap()` parameter and an
  `embedding` weight in `ScoringWeights`. It adds `embeddingSimilarity` (looked
  up from the map, default 0.0) as one more weighted signal in the score
  breakdown. No model/DB dependency enters `ProductRanker`.

## Data flow (query)

```
spokenText
  ├─ EmbeddingModel.embed(query) ──► queryVec (or null → fuzzy-only)
  ├─ FTS/variant candidates (as today) ─┐
  └─ EmbeddingSearch.topK(queryVec) ────┤─► union → candidate products
                                         │
  candidates + Map<id,cosine> ─► ProductRanker.rank(...) ─► ranked
                                         │
                        threshold decision ─► AutoAdd / Suggestions / Unknown
```

## Error handling / graceful degradation

- **Model fails to load** (`embed` returns null): embedding signal is absent,
  candidate set = FTS-only, `embeddingScores` empty → ranker behaves like today.
  No crash.
- **Product not yet backfilled**: its cosine defaults to 0.0; fuzzy signals still
  rank it. Backfill fills it in later.
- **Blank query**: Unknown, as today.
- Fully offline; no network path.

## Testing

Pure JVM unit tests (reliable headless; the pattern proven in A1):
- `VectorMath.cosineSimilarity` — known vectors, orthogonal/identical/opposite.
- `floatsToBytes`/`bytesToFloats` round-trip.
- `EmbeddingSearch.topK` — with a small set of hand-made vectors, returns the
  right ids in the right order.
- `ProductRanker.rank` with an injected `embeddingScores` map — a product with a
  high embedding score but low fuzzy score ranks above a purely-fuzzy near-miss;
  and empty map reproduces current behavior (regression guard).

Native inference (`EmbeddingModel`), the backfill, and end-to-end resolution are
verified on-device / manually (deferred, same constraint as A1: instrumented
tests are blocked by the pre-existing WorkManager-init crash).

## APK size / performance

- Model + tokenizer bundled in `assets/` (~90–120 MB quantized).
- First-run backfill embeds every product once (seconds for a large catalog) —
  background with progress; matching works fuzzy-only until it completes.
- Per-query cost: one query embed (~30–80 ms on device) + brute-force cosine
  (<10 ms). Acceptable inside the A1 live loop.

## Out of scope

- **Fine-tuning / training** the model — pretrained only.
- **Approximate nearest-neighbor index** (HNSW etc.) — brute force is fine at
  this catalog size; revisit only if the catalog reaches tens of thousands.
- **Quantity parsing** and A1 features — separate tracks.
- **Backend/server embedding** — this is on-device only.

## Relationship to A1

Independent feature; branches from `development`. Both A1 and B2 touch
`ResolveSpokenProductUseCase` (A1 added a new *caller*, `NeedsReviewViewModel`;
B2 changes its *internals*), so a minor merge conflict is possible when both
land — manageable. B2's improvements automatically benefit A1's review screen
once both are merged.
