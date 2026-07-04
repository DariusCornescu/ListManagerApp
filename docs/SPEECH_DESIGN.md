# Speech / Voice Ingestion — Location Decision (Phase 4)

> Status: **DECIDED (2026-06-10)** — see §0. Algorithm facts below are quoted from the Android
> code with file:line and remain the spec for the on-device ranker.

## 0. Decision — split transcription from ranking

The original A-vs-B framing conflated two concerns. The chosen architecture separates them:

- **Transcription (audio → text):** server-side **Groq `whisper-large-v3-turbo`** when online
  (much better than the on-device recognizer, esp. Romanian/noisy stores); on-device
  `SpeechRecognizer` as the **offline fallback**. So voice still works with no signal.
- **Ranking (text → product):** stays **on-device, always** (the Kotlin ranker in §1). This
  keeps a single ranker implementation — **no Python port, no conformance corpus, no drift** —
  and keeps matching offline-capable.
- **Priority:** Android. Flutter/web voice are out of scope for now.

Server only does audio→text. Implications: the backend Option-B *ranker* port in §2 is **NOT
built**; instead we add a **transcription-only** endpoint. The §1 algorithm spec stays as the
authoritative description of the on-device ranker.

**Backend (built now, pytest-verifiable):** `POST /api/speech/transcribe` — auth-required,
rate-limited, audio size-capped; behind a swappable `Transcriber` provider (Groq impl + a fake
for tests). `GROQ_API_KEY` via pydantic-settings.

**Android (deferred — needs device/emulator):** record audio; when online POST it to
`/api/speech/transcribe` and feed the returned transcript into the existing
`ResolveSpokenProductUseCase`; when offline use the on-device `SpeechRecognizer`. Switch on
connectivity via `NetworkHelper`.

## 1. Current on-device pipeline (Android, Kotlin)

Voice ingestion is entirely on-device. No backend ranker/speech endpoint exists (server-side
product matching is only a substring `ilike` in `get_products`, `main.py`).

### Stages
1. **Capture** — `AndroidSpeechProvider`: Android `SpeechRecognizer`, locale `ro-RO`, partial
   results; emits `SpeechState` (`Idle|Listening|Partial|Final|Error`). Final transcript = first
   hypothesis. `SpeechRepository` deals only in raw text (no matching).
2. **Resolve / bucket** — `ResolveSpokenProductUseCase`: blank → `Unknown`; narrow candidates via
   SQLite FTS over `QueryVariants.generate` (fallback `getAll()` on empty/exception); rank;
   decide on the top score.
3. **Rank** — `ProductRanker.rank`: normalize spoken text once, score each candidate, drop
   zero-scores, sort desc. (Variants affect candidate selection only, NOT the score — score is
   raw transcript vs candidate name/aliases.)

### Normalization — `TextNorm.normalize`
lowercase → strip Romanian diacritics (`ă→a, â→a, î→i, ș→s, ț→t`) + NFD strip combining marks →
dimension fixups (`×/*/" pe " → x`, `(\d)\s*x\s*(\d)→\1x\2`, `(\d{1,3})\s+(\d{2,4})→\1x\2`) →
trim → collapse whitespace.

### Similarity metrics — `SimilarityEngine` (all → [0,1])
- **levenshtein**: `1 - dist/max(len)` (1.0 equal, 0.0 if either empty).
- **jaccard** (bigrams): set IoU of length-2 substrings (if `len<2`, set is `{s}`).
- **token**: Jaccard over token sets.
- **numberSeq**: `|nums1 ∩ set(nums2)| / max(|nums1|,|nums2|)`.
- **phonetic**: levenshtein of phonetic keys. Key = normalize then ordered subs
  `[aeiou]+→A`, `c|k→C`, `s|z→S`, `d|t→T`, `b|p→P`, `g|j→G`, then collapse repeats `([A-Z])\1+→\1`.
- **prefix**: equal leading chars / len(longer).

### Weights — `ScoringWeights`
`lev 1.5 · jaccard 1.2 · token 1.3 · phonetic 0.8 · prefix 0.7 · containsBonus 2.0 · numberSeq 1.0`

### Name vs alias combination — `calculateScore`
- aliases = comma-separated string, normalized individually.
- `containsBonus(spoken,target)` = 1.0 if equal, 0.5 if either contains the other, else 0.0.
- **name_score** = `(lev*1.5 + jac*1.2 + tok*1.3 + pho*0.8 + prefix*0.7 + contains*2.0 + num*1.0)/7.0`
  (num only if either side has digits, else 0).
- **alias_score_i** = `(lev*1.5 + jac*1.2 + tok*1.3 + pho*0.8 + contains*2.0)/5.0` (no prefix/number);
  `max_alias = max(...)` or 0.
- **final = max(name_score, max_alias)**. Divisors 7.0/5.0 are fixed literals — port verbatim.

### Bucketing thresholds — `ResolveSpokenProductUseCase`
Decision on the **top** product's score: `>= 0.82` → **AutoAdd** (top 1); `>= 0.60` →
**Suggestions/needs-confirmation** (top 5); else **Unknown** (carries the spoken text).

## 2. The two options

### Option A — stay on-device (Android only)
No backend work; ranker stays Kotlin-only. Flutter (prototype, no voice) and a future web client
get no voice. Lowest cost, zero drift risk, full offline, best latency.

### Option B — backend ingestion + ranking pipeline
A server endpoint takes a transcript (+ session) and returns the three buckets so Flutter/web can
share one ranker. Requires: faithful Python port of §1 (pure-Python preferred to keep exact
parity — `rapidfuzz` only as a Levenshtein backend), DB-backed catalog loader (`name`+`aliases`
already exist — no schema change), threshold reuse (`T_AUTO=0.82`, `T_CONFIRM=0.60`), the Phase-2
access check on the session (404 for non-members), Phase-3 op-log + idempotency on the auto-add
write path, rate limiting, and a ≤2000-char transcript cap.

## 3. Drift prevention (mandatory for Option B)
Two implementations diverge silently. Mitigation: one **shared conformance corpus**
(`docs/conformance/speech_corpus.json`) of `{transcript, catalog, expected_bucket,
expected_product, expected_top_score±tol}` cases that BOTH rankers must pass — Python via pytest
(now), Kotlin via `./gradlew testDebug` (deferred). Any algorithm change updates the corpus and
passes on both sides in the same PR.

## 4. Tradeoffs

| concern | A (on-device) | B (backend) |
|---|---|---|
| Latency | best | + network round-trip |
| Offline matching | full | none |
| Clients with voice | Android only | Android + Flutter + web |
| Drift risk | none | high (needs §3 corpus) |
| Effort | zero | new module + endpoint + loader + tests + corpus |
| Catalog freshness | synced on-device | always server-current |
| Who needs it today | Android has it | Flutter=prototype (no voice), web=doesn't exist |

**Observation (not the decision):** the only client that has or needs voice today is Android,
which already works on-device. Option B's payoff is entirely future multi-client reuse, paid now
in porting + drift-prevention cost. A defensible path is to defer Option B until a second client
actually needs voice; if built now, the conformance corpus is mandatory. **The human decides.**

> Implementation plan for Option B (faithful Python algorithm spec, endpoint design, and a
> file-disjoint parallel-agent breakdown) is held ready and will be appended/executed if Option B
> is chosen.
