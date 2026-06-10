# Progress Log

Running log of working sessions: what changed, what's next, open questions.
Newest entries on top.

---

## 2026-06-10 — Phase 1: Security Hardening (complete, suite green)

Done on branch `security-hardening` via two parallel agents (backend app+tests; seeder).

**Fixed**
- **F1** — catalog/distributor/product create/update/delete now require **admin** auth.
  The 6 previously-failing `*_no_auth` tests pass; success tests updated to use admin creds.
- **F2** — removed the insecure `SECRET_KEY` fallback. New `app/config.py`
  (pydantic-settings); missing `SECRET_KEY` now **fails fast** (ValidationError). Added
  `backend-fastapi/.env.example`. Tests inject a test key via `conftest.py`.
- **F3** — `seed_admin.py`: no default-credential admin (skips with a warning if
  `ADMIN_PASSWORD` unset), **never re-resets** an existing user's password, dev test-user
  gated behind `SEED_DEV_USERS`+`TEST_USER_PASSWORD`, no plaintext password logging.
- **F5** — `slowapi` rate limiting (`5/minute`) on `/api/auth/login` and `/register`,
  disableable via `RATE_LIMIT_ENABLED` (off in tests).
- **F6** — implemented `PUT /api/auth/me` (the route Android already calls); added tests.
- **F8** — `add_session_item` now 404s on a missing session.
- **F10** — deleted the stale `tests/TEST_SUMMARY.md`.

**Test suite:** `77 passed` (was 6 failed / 66 passed). Verified by coordinator.

**Deferred (with reason) — backlog for later phases:**
- **F4** refresh/revocation — keep 24h access token for now; revisit when clients need
  longer sessions or forced logout.
- **F7** scope WebSocket broadcasts — belongs to Phase 2/3 (needs the ownership model).
- **F11** CORS config — add when a browser/Flutter-web client appears.
- **F12** `pip-audit` — venv pip is broken (see note); run after repairing it.

**Heads-up:** the venv's own `pip` is corrupted (`pip._internal.operations.build` missing);
slowapi was installed via the base interpreter targeting the venv. Repair before relying on
`venv\Scripts\python -m pip` directly.

**Next:** human review of the diff / merge `security-hardening`. Then Phase 2 (Teams) —
start in plan mode; the global-session migration is a STOP-and-ask point.

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
