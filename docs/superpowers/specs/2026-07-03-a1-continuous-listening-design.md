# A1 — Live Continuous Listening ("one big listening")

**Date:** 2026-07-03
**Status:** Approved design, pending implementation plan
**Scope:** Android app (`android-native`) only. No backend/hosting changes.

## Goal

Let the user hold a single recording session and walk the warehouse dictating
many products with natural pauses. Each pause delimits one product, which is
matched against the catalog and added to the active session **live** as the user
speaks — instead of the recognizer dying after the first phrase (current
behavior).

## Background — current behavior

- [`AndroidSpeechProvider`](../../../android-native/app/src/main/java/com/darius/listmanager/data/speech/AndroidSpeechProvider.kt)
  wraps Android `SpeechRecognizer` (`ro-RO`, free-form). It recognizes **one
  utterance**, emits `SpeechState.Final(text)`, then goes `Idle`. On
  `NO_MATCH` / `SPEECH_TIMEOUT` it currently sets `Idle` and gives up.
- [`HomeViewModel`](../../../android-native/app/src/main/java/com/darius/listmanager/ui/viewmodel/HomeViewModel.kt)
  collects each `Final` and calls `processSpokenText`, which runs
  [`ResolveSpokenProductUseCase`](../../../android-native/app/src/main/java/com/darius/listmanager/data/usecase/ResolveSpokenProductUseCase.kt).
  Outcomes:
  - **AutoAdd** (score ≥ 0.82): adds product to session, qty 1.
  - **Suggestions** (0.60–0.82): shows a suggestion list, **waits for a tap**.
  - **Unknown** (< 0.60): saves spoken text to `unknown_products`.

The implicit assumption today is **one utterance = one product**, and the
Suggestions branch **blocks** on user interaction.

## Key decisions (confirmed with user)

1. **Processing model: live, per pause.** Each end-of-phrase utterance is
   matched and added immediately; the recognizer auto-restarts and keeps
   listening until the user taps Stop. No transcript-splitting step is needed
   because each pause is one item.
2. **Ambiguous (medium-confidence) items: queue for review.** Keep listening;
   collect ambiguous items into a persisted "needs review" list the user
   resolves in one batch afterward. Preserves the hands-free flow.
3. **Review queue is persisted to the DB** (offline-first consistency: survives
   app kill / crash / battery death mid-walk).
4. **Loop lives in the provider** (Approach A below).

## Approach — provider-driven auto-restart (chosen)

All continuous-listening logic stays inside `AndroidSpeechProvider`:

- Add a `wantListening: Boolean` flag.
- `startListening()` sets `wantListening = true` and begins.
- `stopListening()` sets `wantListening = false` and stops for real.
- On `onResults`: emit `Final(text)` as today; then, if `wantListening`,
  immediately restart the recognizer.
- On `NO_MATCH` / `SPEECH_TIMEOUT` while `wantListening`: restart silently
  instead of emitting an error.
- On `ERROR_RECOGNIZER_BUSY`: short retry.
- On real errors (permissions, audio): surface the error and stop the loop
  (`wantListening = false`).

The provider keeps emitting **one `Final` per utterance**, which `HomeViewModel`
already consumes — so the ViewModel change is minimal.

Rejected alternatives:
- **B. ViewModel-driven restart** — leaks recognizer lifecycle into the VM,
  duplicates state, no benefit here.
- **C. Foreground service** — most robust for screen-off long walks, but heavier
  (service + notification + lifecycle). YAGNI for v1; revisit if battery /
  screen-off becomes a real problem.

## Components

### 1. Speech layer — `AndroidSpeechProvider` + `SpeechState`

- Implement the `wantListening` state machine above.
- `SpeechState` gains a clear representation of "still listening between
  utterances" so the UI knows the mic stays hot across pauses. Exact shape to be
  settled in the plan (e.g. keep `Listening` as the between-utterances state and
  reserve `Idle` for a real stop). Real errors still transition out of the loop.

### 2. Matching — unchanged

Each `Final` still flows through `processSpokenText` → `ResolveSpokenProductUseCase`.
**AutoAdd** and **Unknown** branches are unchanged. Only the **Suggestions**
branch changes (below).

### 3. Needs-review queue (persisted) — new

- New Room entity `NeedsReviewEntity`: `id`, `spokenText`, `sessionId`,
  `createdAt`. Plus DAO + repository, mirroring the existing `UnknownProduct`
  plumbing.
- Kept **separate** from `unknown_products` because the semantics and the review
  action differ:
  - Unknown (< 0.60): "name this new product / add to catalog."
  - Needs-review (0.60–0.82): "pick which existing product you meant."
- When a `Final` resolves to **Suggestions**, do **not** stop — insert a
  needs-review row and keep listening.
- The review screen **re-runs** `ResolveSpokenProductUseCase` on the stored
  `spokenText` to show fresh candidates. This avoids persisting candidate lists
  and stays correct if the catalog changed since capture.

### 4. Review UX — new screen

- A "N items need review" affordance on Home (like the existing Unknown count).
- Opens a screen that, per queued item, shows the ranked candidates and lets the
  user either tap the correct product (adds to session, reusing
  `addSuggestedProduct` logic) or push the item to Unknown.
- Removing/resolving an item deletes its needs-review row.

### 5. Home UI

- Mic button becomes a **record/stop toggle**.
- While recording: show a live partial-text line and a running count of items
  added / queued for review this session, for continuous feedback that the mic
  is still live.

## Data flow (live session)

```
Tap Record → wantListening = true → recognizer starts
  ↓ (user speaks an item, then pauses)
onResults → Final(text) → processSpokenText → resolve
  ├─ AutoAdd     → add to session (qty 1)
  ├─ Suggestions → insert NeedsReviewEntity (keep listening)
  └─ Unknown     → insert UnknownProduct (keep listening)
  ↓ (provider auto-restarts because wantListening still true)
... repeats per utterance ...
Tap Stop → wantListening = false → recognizer stops
  ↓
User opens Review → per item, re-rank spokenText → tap product → add to session
```

## Error handling

- Rapid-restart guard to avoid tight restart loops.
- `ERROR_RECOGNIZER_BUSY` → short delayed retry.
- Recoverable errors (`NO_MATCH`, `SPEECH_TIMEOUT`) → silent restart while
  `wantListening`.
- Non-recoverable errors (permissions, audio) → surface + stop the loop.
- Optional generous safety auto-stop after a long pure-silence stretch
  (e.g. ~2 min) to protect battery.
- Duplicate items ("lapte … lapte") already increment quantity via the existing
  `AddProductUseCase` — no change needed.

## Testing

- Unit-test the provider restart state machine by mocking recognizer callbacks:
  final → restart, timeout → restart, busy → retry, fatal error → stop,
  `stopListening` → no restart.
- Unit-test the Suggestions → needs-review routing in the resolve flow.
- Room DAO test for `NeedsReviewEntity` (insert / query by session / delete).

## Out of scope for A1

- **Quantity parsing** ("două lapte" → qty 2). Today AutoAdd always adds qty 1.
  Useful for warehouse use but a distinct feature; keep separate.
- **B2 — local embedding model for matching.** The next track after A1.
- **Backend hosting** for multi-phone shared sync. Separate track; A1 is pure
  on-device work.
