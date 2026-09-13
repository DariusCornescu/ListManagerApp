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

The current five examples are hand-written synthetic smoke data. Their perfect
score proves the loader and metric calculations run; it is not the T2 baseline
and must not be used to choose production weights or set the accuracy target.

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
