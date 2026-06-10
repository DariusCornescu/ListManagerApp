# Progress Log

Running log of working sessions: what changed, what's next, open questions.
Newest entries on top.

---

## 2026-06-10 — Phase 4: Voice ingestion — server transcription (backend done)

Decision (Darius): **split transcription from ranking** (see `docs/SPEECH_DESIGN.md` §0).
Transcription goes server-side (Groq `whisper-large-v3-turbo`) when online, with the
on-device `SpeechRecognizer` as offline fallback; **ranking stays on-device always** — so no
Python ranker port, no drift, and matching still works offline. Android-first; Flutter/web
voice out of scope.

**Added (backend, pytest-verifiable now)**
- `app/transcription.py`: `Transcriber` interface + `GroqTranscriber` (httpx → Groq
  OpenAI-compatible audio endpoint) + `get_transcriber()` dependency (mockable). Errors never
  leak the API key.
- `app/schemas_speech.py`: `TranscriptionResponse`.
- `POST /api/speech/transcribe` (in `main.py`): auth-required, `@limiter.limit("20/minute")`,
  `audio/*` only (415), size-capped at `MAX_AUDIO_BYTES` (413), provider failure → 502.
- `app/config.py`: `GROQ_API_KEY`, `TRANSCRIPTION_MODEL`, `MAX_AUDIO_BYTES`; documented in
  `.env.example`.
- `tests/test_transcribe.py`: 7 tests via a fake transcriber (no real network).

**Test suite:** `158 passed` (was 151). Verified independently by coordinator.

**To use real transcription:** set `GROQ_API_KEY` in `.env` (not required to run the suite).

**Deferred (Android, needs device/emulator — see SPEECH_DESIGN.md §0):**
- Record audio; online → POST to `/api/speech/transcribe`, feed transcript into the existing
  `ResolveSpokenProductUseCase`; offline → on-device `SpeechRecognizer`. Switch on connectivity
  via `NetworkHelper`.

**Still open from earlier:** `/api/stats` unauth global active-session leak (minor); Android
sync follow-on (Phase 3).

---

## 2026-06-10 — Phase 3: Multi-writer sync hardening (backend op-log, complete)

Scope chosen: **Option C** (backend op-log, backend-first; Android rework deferred). Design in
`docs/SYNC_DESIGN.md`. Built via a waved multi-agent workflow.

**Added**
- **Models:** `AppliedOp` (idempotency ledger) and `SessionOp` (append-only op-log with
  per-session monotonic `seq`); nullable `item_uuid` on `GlobalSessionItem`. All additive.
- **`app/sync_ops.py`:** `next_seq`, `record_op`, `check_idempotent`, `store_idempotent`,
  `get_ops_since`. **`app/schemas_sync.py`:** `OpDTO`.
- **Idempotency:** optional `idempotency_key` on item create/update — same key replays as a
  no-op returning the stored result (kills the FM3 double-count); different keys still sum.
- **Op pull endpoint:** `GET /api/session/{id}/ops?since=<seq>` (auth + access-scoped; 404 for
  non-members, 403 no token).
- **F7 fixed:** `broadcast_to_session` now scopes WebSocket events to the session's owner/team
  members; the 6 session/item broadcasts use it (catalog stays global).
- **Tests:** convergence, idempotency, ops-pull, WS-scoping (11 new).

**Test suite:** `151 passed` (was 140). Verified independently by coordinator.

**Mobile-compat:** all new request fields optional, response fields nullable, existing
endpoints unchanged in shape — shipped Android client unaffected.

**Deferred (separate follow-on, needs device/emulator — see SYNC_DESIGN.md §6):**
- Android queue rework: `idempotencyKey` column + send on replay, consume the `?since=` pull
  endpoint, replace drop-on-409 with a reconcile loop.
- Still open from earlier: `/api/stats` unauthenticated global active-session leak (minor).

**Next:** Android sync follow-on (on a device), or address the `/api/stats` leak, or Phase 4
(voice ingestion location — still an undecided decision per the upgrade plan).

---

## 2026-06-10 — Phase 2: Teams & session ownership (complete, suite green)

Built on branch `security-hardening` via a waved multi-agent workflow (Wave 0 foundation →
Wave 1 four parallel agents → Wave 2 integration). Migration decision: **WIPE / clean
cutover** (chosen by Darius).

**Added**
- **Models:** `Team`, `TeamMember(role: admin|member)`, `TeamInvite` (single-use, 7-day TTL);
  `GlobalSession` gained nullable `owner_user_id` + `team_id` with a DB
  `CheckConstraint("(owner_user_id IS NULL) != (team_id IS NULL)")` (exactly one set).
- **`app/authz.py`:** `require_session_access` (→404 hides inaccessible sessions),
  `require_team_member`, `require_team_admin` (→403 for members lacking the role),
  `get_user_team_ids`. Team role is kept separate from system `User.role`.
- **`app/routers/teams.py`** (first APIRouter): create/list/get/rename/delete team,
  invites (create/accept), member list/promote/demote/remove, with last-admin protection.
- **`app/migrations/phase2_cutover.py`:** idempotent wipe, gated behind `PHASE2_CUTOVER=run`.
- **Tests:** `test_authz_matrix.py` (42 cross-user checks) + `test_teams.py` (21).

**Changed (mobile-compat notes)**
- `POST /api/session/create`: defaults to a personal session (owner = caller); optional
  `team_id` (requires membership); "deactivate others" is now scoped, not global.
- `GET /api/session/active`: now **auth-required** and owner/team-scoped (was public).
  Android's RetrofitClient attaches the JWT, so logged-in clients are unaffected. Response
  gained nullable `owner_user_id`/`team_id` (Gson-safe).
- Every session/item endpoint now goes through `require_session_access`.

**Test suite:** `140 passed` (was 77). Verified independently by coordinator (2 runs).

**Still open / deferred**
- `/api/stats` still surfaces a global active session **unauthenticated** — minor info leak,
  left for a follow-up (scope or auth-gate it).
- **F7** WebSocket broadcast is still global (cross-team leak) — Phase 3.
- To apply the cutover on the real dev DB: run once with `PHASE2_CUTOVER=run` (or delete
  `listmanager.db`).

**Next:** Phase 3 (multi-writer sync hardening) — start in plan mode; or address the
deferred items first.

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
