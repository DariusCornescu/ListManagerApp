# Voice matching benchmark

The T2 benchmark grades the on-device `ProductRanker` against a versioned JSON
corpus. It runs as part of the Android JVM test suite and reports top-1, top-3,
and mean reciprocal rank (MRR).

## Corpus format

The test asset is `android-native/app/src/test/resources/voice-benchmark/corpus.json`.
Its top-level fields are:

- `schema_version`: currently `1`;
- `privacy_mode`: how the examples were collected and retained;
- `products`: the fixed candidate catalog (`id`, `name`, `aliases`);
- `cases`: labelled examples with a stable `id`, transcribed `utterance`,
  `expected_product_id`, BCP-47 `locale`, and `noise_condition`.

The loader rejects unsupported versions, empty fields, duplicate IDs, and cases
whose expected product is absent from the embedded catalog. Unknown JSON fields
also fail loading so accidental schema changes cannot silently alter a run.

## Running it

From `android-native/`:

```powershell
.\gradlew testDebug --tests "com.darius.listmanager.benchmark.ProductRankerBenchmarkTest"
```

The test prints one machine-readable line:

```text
VOICE_BENCHMARK total=5 top1=1.0000 top3=1.0000 mrr=1.0000
```

The corpus currently contains five hand-written synthetic smoke cases and one
operator-confirmed S25 transcript from the deliberate M0 voice test. This is not
enough to establish the T2 baseline and must not be used to choose production
weights or set the accuracy target.

The weight-sweep harness evaluates baseline weights plus configurable variations
for all eight `ScoringWeights` fields. It ranks candidates by top-1, then MRR,
then top-3, and prints the leading candidates with `VOICE_WEIGHT_SWEEP` lines.

## Privacy policy for EX-19

The corpus uses **operator-confirmed text-only transcripts and retains no audio**.
Each accepted example contains only the recognizer output, expected product ID,
locale, and a broad noise label. Collection is manual from an explicitly chosen
debug or Preview run; production builds neither persist transcripts nor include
their content in logs. Do not add names, account details, free-form conversation,
or any example that was not deliberately dictated for this benchmark.

EX-19 still needs tens of labelled examples per noise condition. Only then should
EX-21 publish the baseline and set a top-1 target; EX-23 may tune weights and
enable a regression threshold after that measurement.

Append a deliberately dictated, operator-confirmed sample with:

```powershell
python tools/voice_corpus.py `
  --corpus app/src/test/resources/voice-benchmark/corpus.json `
  --id real-s25-shop-floor-001 `
  --utterance "transcript returned by Android" `
  --expected-product-id 1 `
  --noise-condition shop-floor `
  --confirmed-text-only
```

The tool validates the privacy mode, case ID, product reference, and noise label,
then replaces the JSON atomically. It never accepts an audio path or recording.

## Synthetic training pairs

`android-native/tools/generate_voice_training_data.py` creates deterministic
Romanian utterance-to-product examples for retrieval and reranking experiments.
It accepts either a JSON catalog with a `products` array or a read-only SQLite
catalog and selects only `id`, `name`, and `aliases` from the `products` table.
It does not ingest audio, transcripts, sessions, accounts, or other user data.

From `android-native/`:

```powershell
..\backend-fastapi\.venv\Scripts\python.exe tools\generate_voice_training_data.py `
  --catalog app/src/test/resources/voice-benchmark/corpus.json `
  --output training-data/voice/synthetic-v1.json `
  --positive-count 2500 `
  --negatives-per-positive 2 `
  --seed 240513
```

The checked-in `synthetic-v1.json` contains 2,500 positive pairs and two hard
negatives for each positive, for 7,500 rows in total. Templates vary commands,
quantities, diacritics, digit quantities, and common ASR spacing. Negative
candidates prioritize products from the same family and variants that differ by
numeric package attributes. Train, validation, and test products are disjoint,
so a product cannot leak across splits. A unit test regenerates the complete
file and fails if the code, fixture, or versioned artifact drifts.

The current artifact uses the four-product benchmark fixture. It validates the
data pipeline but has too little catalog diversity for training or an accuracy
claim. Before a model experiment, regenerate it from an approved catalog-only
JSON or SQLite export and review the class and split distribution. Keep the
operator-confirmed real transcript corpus separate and untouched as the final
evaluation set; synthetic rows may warm-start training and hard-negative mining,
but must not be counted as real recognition evidence.
