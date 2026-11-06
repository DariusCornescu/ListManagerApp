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

## Experiments, November 2026

**Read this first.** No new real speech was recorded. The EX-42 slice is hand-written and
the EX-39 and EX-40 corpus is a seeded model of recognizer mistakes over 25 seed products.
These results can rule a change out, but they cannot prove real-shop accuracy. Repeat them
on the real corpus once EX-19 has collected it.

Each run prints machine-readable lines:

```powershell
.\gradlew testDebugUnitTest --tests "com.darius.listmanager.benchmark.*"
```

### EX-42: quantity and price parsing

`QuantityParsingBenchmarkTest` uses a hand-labelled slice of 36 session phrases and 17
inventory lines.

| Parser | Before | After |
| --- | --- | --- |
| Session quantity (`SpokenQuantity`) | 25/36 | 36/36 |
| Inventory line (`InventoryLineParser`) | 11/17 | 17/17 |

Every miss was a number in words. Above ten, words were not understood at all, and "două
sute" parsed as 2 rather than 200. Inventory lines did not understand number words at all,
though the recognizer returned the one real transcript that way.

**Kept.** The slice is now a regression gate. Its 100% only means the known gaps are closed:
the slice and the fix have the same author. `segmentLines` still needs digits to split
several inventory lines from one breath.

### EX-40: N-best depth

| Hypotheses used | Top-1 | Auto-add rate | Wrong auto-adds |
| --- | --- | --- | --- |
| 1 | 89.7% | 1.5% | 0 |
| 2 | 90.8% | 2.7% | 0 |
| 3 | 91.9% | 3.5% | 0 |
| 4 | 92.8% | 4.6% | 0 |
| **5 (shipped)** | **94.4%** | **5.1%** | **0** |

**Result: keep 5.** Accuracy has not plateaued within the 5 hypotheses the app asks for, and
no extra wrong auto-adds appeared across about 1,100 cases. Whether real recognizers return
useful alternatives this deep is what the synthetic corpus cannot say.

### EX-39: phonetic weight

| Weight | Top-1 | Auto-add rate | Wrong auto-adds |
| --- | --- | --- | --- |
| 0.0 | 93.3% | 3.0% | 0 |
| 0.4 | 94.1% | 4.3% | 0 |
| **0.8 (shipped)** | **94.4%** | **5.1%** | **0** |
| 1.2 | 94.7% | 8.8% | 0 |
| 1.6 | 95.0% | 14.6% | 1 |
| 2.4 | 96.0% | 41.4% | 15 |

**Result: drop.** Top-1 gains little, and scores are a plain weighted sum, so raising any
weight also inflates them past the fixed 0.82 auto-add threshold. At 2.4 that means 15 wrong
products added without asking, which is worse than a review prompt. Any retuning has to
adjust the thresholds or normalize the score at the same time. Romanian-specific rules in
`phoneticKey` were not tried: this corpus's noise model would only reward rules written to
match it.

### EX-38: on-device embeddings on another runtime

**Not run; stays open.** The runtime half is ready: ONNX Runtime 1.24.3 loads through JNI on
the S25 and passes the 16 KB check (see `M0-ONE-TRUNK.md`). A benchmark delta also needs a
`model.onnx`, which is not packaged, plus a real corpus to grade it, because the synthetic
one has no notion of meaning. Weigh it against the size cost: the B2 spec estimates a 90 to
120 MB app, and alias learning from review picks is much cheaper to try first.

### EX-41: alternative speech-to-text

**Not run; stays open.** A comparison on the benchmark needs recorded shop audio, and the
benchmark's privacy policy deliberately retains none. Until a consented audio set exists,
this is what is known from the code, and not a measurement:

| | Android recognizer (today) | Whisper through Groq (`/api/speech/transcribe`, backend only) | Whisper on the device |
| --- | --- | --- | --- |
| Works offline | Depends on the phone's language pack | No | Yes |
| Latency | Live partial results | Network round trip, no partials | Slow on mid-range phones |
| N-best alternatives | Yes, which the ranker uses | No | No |
| Cost | None | Per use | App size |
| Audio leaves the phone | Up to the phone's speech service | Yes | No |

**Provisional recommendation: stay on the Android recognizer.** Live partials and N-best feed
today's matching, and a shop's connection is not reliable. To settle it, record about 100
consented phrases in a real shop, then run all three on the same audio.
