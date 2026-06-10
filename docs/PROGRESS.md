# Progress Log

Running log of working sessions: what changed, what's next, open questions.
Newest entries on top.

---

## 2026-06-10 — Phase 0: Recon & Audit (read-only, complete)

**Changed**
- Produced `docs/AUDIT.md`: architecture map, full endpoint inventory, sync behavior,
  OWASP-oriented security audit, test reality check, prioritized findings (F1–F12).

**Key findings**
- Test suite is **RED: 6 failed / 66 passed** — the failures are `*_no_auth` catalog tests
  that already encode the intended secure behavior (acceptance criteria for Phase 1 F1).
- **P0:** F1 unauthenticated catalog/product/distributor mutations; F2 forgeable JWTs via
  public `SECRET_KEY` fallback; F3 default admin creds re-reset on every startup.
- **P1:** no token refresh/revocation; no auth rate limiting; Android `PUT /api/auth/me`
  has no backend route; global (unscoped) WebSocket broadcast.
- Clean: no secrets in git history, no `.env` tracked, Android base URL externalized.

**Next**
- Human review of `docs/AUDIT.md`. Then: *"Execute Phase 1 only. Start in plan mode."*
- When Phase 1 starts (first code changes), branch off `development` before committing.

---

## 2026-06-10 — Docs setup & reality reconciliation

**Changed**
- Filled in `CLAUDE.md` with verified facts from the codebase (paths, commands, stack,
  auth, sync model, ranker location).
- Moved `UPGRADE_PROMPT.md` → `docs/UPGRADE_PROMPT.md` (matches its own header instructions)
  and created this `docs/PROGRESS.md`.

**Open questions / backlog — `UPGRADE_PROMPT.md` is stale vs. the actual code:**
The phase plan assumes a design the code does not match. Before running any phase, decide
how to reconcile each item:
1. Ranker is **Android Kotlin** (`util/ProductRanker.kt`), not a backend Python pipeline.
   Phase 2's "wrap ranker in `SimilarityRanker` protocol / inject into `SpeechIngestPipeline`"
   has no backend target. → Decide: build a backend speech pipeline, or keep speech on-device?
2. Sessions are a **single global shared session**, not per-user. Phase 0/1's object-level
   authz premise ("user A reads user B's session") doesn't apply as written, and Phase 3's
   "add multi-user shared sessions" is largely already the model. → Decide intended ownership
   model (global / per-user / teams).
3. Real-time sync (WebSocket), optimistic locking, and an Android offline queue **already
   exist**. Phase 3's "build sync, don't build WebSockets" is partly already done. → Decide
   whether to keep current sync or move to the op-log/CRDT design in the prompt.
4. No `vendor/listmanager-speech/`; `aliases` column already exists. → Remove or rewrite the
   Phase 2 placement/migration steps.

**Next**
- Reconcile `UPGRADE_PROMPT.md` with reality (or replace it with a fresh plan once goals
  are confirmed). Until then, do not execute its phases verbatim.
