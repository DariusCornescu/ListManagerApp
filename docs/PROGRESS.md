# Progress Log

Running log of working sessions: what changed, what's next, open questions.
Newest entries on top.

---

## 2026-07-13 — Offline: remember the last account's role (fix/offline-role-persistence)

Reported from real use: offline, catalog edits refused with "doar administratorii pot
modifica catalogul" even for the admin account, and nothing got saved.

**Root cause**

The account *is* remembered offline (token in encrypted `sync_prefs`, username in
`auth` prefs) — but the **role** lived only in memory (`AuthState._role`) and was
populated exclusively from server responses (`GET /me` on startup/login). Offline cold
start → role stays `null` → `isAdmin == false` → CatalogScreen hides the add FAB and
selection mode, EditProductScreen goes read-only. The repositories themselves already
queue catalog writes offline (`RepoResult.QueuedOffline` + pending operations) and
never check the role locally — only the role-blind UI was in the way.

**What changed**

- `AuthViewModel`: persists `saved_role` in the `auth` prefs whenever the server
  confirms the identity (`loadCurrentUser`, `updateProfile`); restores it in
  `checkLoginStatus` (only into an empty in-memory role, so live state is never
  downgraded by disk); clears it on `logout()` and on `login()` success (a different
  account must not inherit the previous one's role — `/me` re-persists it right after).
- `MainActivity`: same guarded restore next to the existing login-state restore, since
  no `AuthViewModel` may exist yet on the home screen at cold start.
- Security unchanged: this is UX-gating only — every API call is still authorized
  server-side, so a stale/tampered `saved_role` can't grant anything. Worst case
  (admin demoted, then offline): ops queue locally and the server rejects them with
  403 on replay.
- Nice side effect: the Account screen now shows the correct role offline too.

**Verification:** `assembleDebug` + full `testDebug` suite green. No new JVM tests —
the change is SharedPreferences/ViewModel wiring with no pure logic seam, and the
project has no Robolectric; flagged in the PR (same status as the crash-guard Handler
glue).

**What's next / open**

- On-device check when convenient: airplane mode → force stop → reopen → catalog edit
  should queue ("pending") instead of the admin refusal, and sync after reconnect.
- If more Android glue keeps landing untested, consider adding Robolectric as a
  deliberate (pinned) test dependency in its own PR.

---

## 2026-07-08 — Induced-crash drill found the Application class was never registered (fix/register-application-class)

Deliberate end-to-end test of the crash pipeline (`adb shell am crash` on the preview
app) produced **no report** — and the investigation found a bug present since the first
commit.

**Root cause**

`AndroidManifest.xml` never had `android:name=".ListManagerApp"` on `<application>`, so
Android used the base `Application` class and **everything wired in
`ListManagerApp.onCreate` was dead code in every build so far**: crash reporting,
crash-loop guard, `SyncWorkManager.initialize`, and the startup auth-token restore.
The app appeared to work because login sets the token directly and WorkManager
auto-initializes. Evidence trail: crash left no `files/crashes/` and no
`crash_loop_guard.xml`; installed APK *did* contain the classes; logcat showed
`ProfileInstaller` debug logs but zero `ListManagerApp` logs → `onCreate` never ran.

**Fix + verification**

- One line: register `.ListManagerApp` in the manifest; instrumented regression test
  (`ApplicationRegistrationTest`) fails loudly if it ever goes missing again.
- Re-ran the drill on the phone: crash → stacktrace persisted + loop counter bumped →
  relaunch → `Uploaded crash report` in logcat, local file deleted, report visible on
  the live `/admin` dashboard. Token restore now also logs `Restored saved auth token`.
- Unit suite + androidTest compile green. Test loop-counter cleaned off the phone.
- Samsung note: `am crash <pkg>` works (ignore the "user 150" Secure Folder warning);
  `am crash --user 0 <pkg>` silently no-ops on this device.

**Open question**

`CrashLoopGuard` counts rapid crashes but never resets on a healthy session — three
rapid crashes weeks apart could accumulate and trigger an unwanted local-cache wipe.
Consider resetting the counter after the process survives the rapid window.

---

## 2026-07-08 — Admin overview dashboard (feat/inventory-lists, continued)

Requested view for `/admin`: stores, headcount, products, generated lists as an
always-current chart, and bug reports grouped by phone.

**Decisions**

- "Stores" map to the existing **Team** entity (each team/workspace = one store) — no
  schema change. A dedicated Store entity stays an option for later.
- The daily chart plots **both** lists completed (`GlobalSession.completed_at`) and
  products registered (`GlobalSessionItem.created_at`) — both already server-side, so
  zero Android changes.

**What changed**

- `GET /api/admin/dashboard` (admin only): stores with member counts; users / products /
  distributors / completed-lists / crash counters; zero-filled per-day activity series
  (clamped 7–90 days, default 30), recomputed live on every call. Date bucketing
  normalized so SQLite and Postgres group identically.
- `/admin` page: "Privire de ansamblu" card — stat tiles, store chips, and a
  dependency-free inline SVG chart (gold bars = products/day, sage line = lists/day, each
  on its own scale so a single list is still visible). Crashes card now opens with a
  per-device summary (reports grouped by phone). All DOM built via `textContent`
  (keeps the page's XSS hygiene).
- Tests: 7 new in `tests/test_admin_dashboard.py` (auth, counts, zero-fill, clamping,
  distinct-device counting); full backend suite **223 passed**. Endpoint also
  smoke-tested against a live local server with the seeded catalog.

**What's next**

- Merge PR #15 (contains everything since PR #14: crash reporting, presence, UI cleanup,
  this dashboard) → Render redeploys → verify `/admin` live.
- Later candidates: real Store entity with addresses; inventory-list export pings so
  local inventory PDFs can join the chart.

---

## 2026-07-08 — Reliability + presence (feat/inventory-lists, continued)

Priority list agreed: crash reporting → who-is-online → consolidate features.

**What changed**

- **Fluent dictation (inventory):** lines are now cut from TEXT, not silence —
  `segmentLines`/`parseMultiple` split one fluent breath into rows, committed live from
  partial results in continuous mode. No forced pauses (33 parser tests).
- **Self-hosted crash reporting:** phones persist uncaught exceptions locally and upload
  on next launch to `POST /api/crashes` (rate-limited, 20k cap); `crash_reports` table
  (migration `9819cacf8390`); admin-only listing + a "Crashes" card in `/admin`. A
  private `/crash-triage` skill closes the loop (fetch → group → root-cause → fix PR).
- **Crash-loop guard:** 3 rapid startup crashes in a row → next launch deletes the local
  Room cache (login + pending crash reports preserved) and notifies the user. Pure
  `CrashLoopPolicy` + tests.
- **Presence ("Online acum"):** WebSocket manager tracks usernames and broadcasts
  `{type: presence}` on connect/disconnect; `GET /api/presence` as REST fallback
  (7 backend tests). Android renders live green-dot list in the drawer, refreshed on
  drawer open.
- **UI cleanup:** the "Server disponibil" top banner is gone — connection/sync status is
  a compact drawer row (tap = manual sync). Home: bigger central mic (160dp), inventory
  button removed (drawer-only), the two large status cards replaced by compact stat
  cards, Romanian copy fixes.
- **Demo accounts** created on the live backend for populating/testing: tata, ana,
  mihai, depozit (credentials shared privately, not committed).

**Verification:** backend 216 passed; Android unit suite + assemblePreview + androidTest
compile all green.

**What's next**

- Merge PR #14 → Render redeploys (crash + presence endpoints go live) → rebuild/reinstall.
- Consolidation pass over existing features; optional: GitHub-Actions crash→issue→PR
  automation; profile/home-redesign track per roadmap.

---

## 2026-07-08 — PDF pagination quirk fixes (feat/inventory-lists)

Closes the follow-up logged on 2026-07-05: the two inherited cosmetic pagination quirks in
`PdfRepository`, fixed identically in `upsertDistributorPdf` and `createInventoryPdf`.

**What changed**

- **Page count** (`6a77ce1`): "Page X of N" no longer overstates N. `totalPages` divided by
  the first page's row limit (20, doc header included) although continuation pages fit 23
  rows — 41 items printed "of 3" on a 2-page PDF. New `countPages()` first pass walks the
  same per-page capacities the drawing loops use.
- **Grand-total row vs footer** (`f9bf306`): on an exactly-full last page the total row was
  drawn at y≈820–850, colliding with the footer text (y=812) and running past the page
  edge (842). New `totalRowFits()` check draws it only while spacing + one row fit in the
  row area; otherwise it spills onto a fresh page (repeated table header + total row), and
  `countPages()` counts that spill page so the label stays right.
- Pagination math exposed as `internal` companion functions; 6 JVM tests
  (`PdfRepositoryPaginationTest`) pin capacities (20/23), fit thresholds, and page counts.
- Item-row layout is untouched — limits and coordinates render exactly as before; only the
  total-row placement and the page label changed.

**Verification gate:** full JVM unit suite (80 tests, 0 failures) + assembleDebug.

**What's next**

- Unchanged from 2026-07-05: push + PR into `development`; presence + home/profile tracks.

**Open questions**

- The distributor first page's row limit (20) lets the last item rows reach y≈825, slightly
  past the footer text (y=812) — pre-existing, out of scope here since only the total row
  was flagged; worth a look if the distributor PDF ever gets a visual pass.

---

## 2026-07-05 — Inventory lists v1 (feat/inventory-lists)

Android-only feature: a new "Inventar" screen where the operator taps the mic, speaks one
line — product, quantity, price in fixed order ("lapte zuzu 5 4 lei 50") — and the app
appends an editable table row with value = qty × price and a live grand total, exportable
to PDF. Local, single active list (sync is a planned v2). Spec:
`docs/superpowers/specs/2026-07-05-inventory-lists-design.md`; plan:
`docs/superpowers/plans/2026-07-05-inventory-lists.md`.

**What changed**

- **`InventoryLineParser`** (`util/`, pure): trailing-numeric-region parsing — tokens like
  "2L" aren't pure numbers, so product names keep their digits; first trailing amount =
  quantity, second = price; a single money-marked amount ("5 lei") is the price. Romanian
  money forms: "4 lei 50 (bani)", "4 virgulă 50", "4,50"/"4.5", leu/ban/ron synonyms.
  Missing fields → nulls (highlighted in UI). 21 JVM tests incl. crash-regression and
  pinned number-word behavior.
- **`InventoryMath`** (`util/`, pure): money as integer bani; line value rounds per row
  then totals sum (invoice behavior); "4,50 lei" formatting. 6 tests.
- **Room v7**: `InventoryItemEntity` (nullable quantity/priceBani) + DAO + thin repository.
  NOTE: the version bump rides `fallbackToDestructiveMigration()` (established pattern) —
  first launch after update wipes the local cache including the pending-operations offline
  queue; catalog re-syncs from the server.
- **`PdfRepository.createInventoryPdf`** (additive): Produs | Cant. | Preț | Valoare table
  + grand-total row, mirroring the existing layout machinery. Two inherited cosmetic
  pagination quirks (page-count overstatement; total-row/footer collision on exactly-full
  pages) are logged as a separate follow-up task.
- **`InventoryViewModel`**: tap-to-speak one line per tap — the collector (Main.immediate)
  stops the provider on the first `Final` before the restart policy runs (verified against
  both interleavings); catalog name adopted only at ranker score ≥ 0.82, else free text;
  CRUD + PDF export + share intent.
- **`InventoryScreen`** + `inventory` route in NavGraph + "Liste inventar" drawer item +
  Home button: editable rows (tap → dialog), missing cells highlighted, live total,
  Export PDF, "Listă nouă" with confirm.

**Verification gate:** compile + full JVM unit suite + assembleDebug (established gate; no
emulator/instrumented tests in this environment). On-device checklist for manual testing:
say a full line → row + total; line without price → highlighted blank cell, fill via tap;
unknown product → free-text row; Export PDF → share sheet; Listă nouă → confirm → empty.

**Process note:** built subagent-driven with two-stage reviews through Task 5 (spec review
of the ViewModel verified independently); the account's monthly spend limit stopped
subagents mid-Task 5, so the Task 5 quality probe (one-shot speech concurrency) and all of
Task 6 were reviewed inline by the coordinator instead. A retroactive review pass over
commits `58c75c9`..`a9d1efb` is worth running once the limit lifts.

**What's next**

- Push + PR into `development`; user merges and tests on device.
- Parallel tracks (agreed roadmap): online presence (drawer "Online acum") and home
  redesign + profile (big REC layout, backend profile fields). Later: inventory sync v2.

**v1.1 (2026-07-08, same branch)** — driven by first real use ("cuie de 5" parsed as
qty 5): (1) **catalog-aware split** — `parse(text, nameScorer)` lets the name swallow
trailing numbers when a longer candidate matches the catalog ≥ 0.82, so hardware-style
names keep their size numbers (5 new parser tests, 26 total); (2) **live transcript +
draft row** — `SpeechState.Partial` now renders the words in real time plus a ghost
Produs|Cant|Preț row parsed live; (3) **continuous dictation toggle** — one tap, many
rows (a pause ends a row), off by default. Also added a `preview` build type
(`.preview` appId suffix, label "ListManager NOU") for side-by-side installs next to
the daily app. Deferred still: catalog-price autofill (needs the Android `price` field).

**Open questions**

- None blocking. Number words ("cinci") are out of scope v1 (pinned behavior; rows are
  editable).

---

## 2026-07-05 — Admin catalog CSV import + web page (feat/admin-catalog-csv)

Backend-only feature: bulk-load/update the product catalog from a CSV via an admin web
page, so the operator's SAGA-derived product list can be curated into one file and imported
instead of adding products one at a time. Built test-first across five tasks, each with
spec + code-quality review.

**What changed**

- **`price` on Product** (`app/models.py`, `app/schemas.py`, migration `9d27ab57ce58`):
  nullable `Numeric(10,2)` column + `price: Optional[Decimal] = Field(ge=0)` on `ProductBase`.
  Serialized as a JSON string (`"8.50"`) per Pydantic v2 — exact, 2-decimal.
- **CSV parser** (`app/services/catalog_import.py`, pure/DB-free): validates headers
  (`distributor`, `product_name` required; `aliases`, `price` optional), tolerates
  reordered/extra columns + BOM + CRLF, and isolates bad rows into `RowError`s (missing
  name, invalid/negative/NaN/Infinity price, ragged rows, duplicate headers) — one bad row
  never crashes the batch.
- **Upsert** (`apply_catalog_import`): matches `(distributor, product_name)` case-insensitively,
  auto-creates distributors, updates changed / inserts new / leaves absent products untouched
  (never deletes). Two-step: `dry_run` flush+rollback preview, then commit. Idempotent.
- **Endpoint** `POST /api/admin/catalog/import` (`app/main.py`): admin-gated,
  `@limiter.limit("5/minute")`, `settings.MAX_IMPORT_BYTES` (5 MB) → 413, non-UTF-8 → 400,
  `CatalogImportError` → 400, other failures → rollback + 500. `dry_run=true` default.
  Returns `ImportResultDTO` (new/updated/unchanged/committed/errors).
- **Admin web page** `GET /admin` (`app/static/admin.html`, self-contained): login (JWT),
  CSV preview → confirm, read-only catalog table. Output via `textContent` + escaping
  (no XSS from operator CSV free-text).

**Tests:** parser (15), upsert (9), endpoint (8), page (1) — full suite green (201 passed).

**What's next**

- Deploy rides the normal backend deploy (Render runs `alembic upgrade head` at startup).
- Curate a real SAGA export into the CSV format and import it; optionally add a "SAGA preset"
  column-mapping later.
- Deferred follow-ons: `price` on the Android phone/PDF; inline edit/delete + manual add on
  the page; a duplicate-in-file warning signal.

**Open questions**

- None blocking. Confirm the operator's curated CSV columns once a real SAGA export exists.

---

## 2026-06-11 — Alembic migrations adopted (feature/alembic-migrations)

Replaced `Base.metadata.create_all()` with Alembic migrations across five tasks on
`feature/alembic-migrations`. Schema changes now have a versioned, reversible path.

**What changed**

- **Alembic dependency + naming convention (`app/database.py`):** `alembic==1.13.1` added
  to `requirements.txt`. A `NAMING_CONVENTION` dict is now passed to `MetaData(...)` on
  `declarative_base()`, giving every constraint a deterministic name that matches across
  SQLite and Postgres (critical for batch-mode `DROP CONSTRAINT` calls by name).

- **Alembic scaffolding:** `alembic.ini` + `alembic/env.py` + `alembic/script.py.mako`
  created in `backend-fastapi/`. `env.py` sources the DB URL from `app.database.DATABASE_URL`
  (or a per-invocation override), imports `app.models` to register all tables, and sets
  `render_as_batch=True` + `compare_type=True` for SQLite compatibility.

- **Baseline migration 0001** (`alembic/versions/0001_baseline_schema.py`): full current
  schema — all ten tables — autogenerated against an empty DB. This is the new source of
  truth replacing `create_all`. `down_revision = None`.

- **Startup** (`app/main.py`): the `PHASE2_CUTOVER` env-gated hook and `create_all()` call
  are replaced by `_run_migrations_to_head()`, which resolves `alembic.ini` from the module
  path and calls `alembic.command.upgrade(cfg, "head")` in-process. Fresh DB → all
  migrations applied; already-current DB → no-op.
  `app/migrations/phase2_cutover.py` is **SUPERSEDED/obsolete** — left in place, not
  deleted; the baseline already includes `owner_user_id`/`team_id` so the cutover is
  redundant.

- **Migration 0002** (`alembic/versions/0002_idempotency_schema_fixes.py`): ships the two
  deferred security schema fixes:
  - `AppliedOp` composite PK `(key, session_id)` — an idempotency key is now unique
    *per session*, not globally. Prevents a key reused across sessions from returning
    another session's stored result (cross-tenant disclosure fix).
  - Dropped the dead global unique constraint on `SessionOp.idempotency_key` (unused in
    current `record_op` writes; the constraint would block any future per-session use).
  Both tables are rewritten via `op.batch_alter_table(..., recreate="always")` for SQLite
  compatibility. In-place `ALTER` on Postgres. `sync_ops.store_idempotent` was updated to
  pass `session_id` when writing under the composite PK.

- **`tests/test_migrations.py`** (2 new tests, suite 163 → 165):
  - *Drift guard* — after `upgrade head`, compares live schema to `Base.metadata` via
    `compare_metadata`; fails immediately when a model changes without a matching migration.
  - *Round-trip* — upgrade → downgrade base → upgrade head; proves every migration's
    downgrade path works.

**Operational notes for deploy**
- **Pre-Alembic DB (tables exist, no `alembic_version` row):** run `alembic stamp 0001`
  ONCE to mark the baseline as already applied, then run `upgrade head` for 0002 and
  future migrations.
- **Failed SQLite migration cleanup:** drop any leftover `_alembic_tmp_*` table before
  retrying (Postgres DDL is transactional — unaffected).
- The 0002 upgrade rewrites `applied_ops` and `session_ops` in place on SQLite via batch
  recreate; Postgres handles it with a standard ALTER.

**Schema-change workflow (going forward)**
See the DB bullet in `CLAUDE.md` — edit models, `alembic revision --autogenerate -m "..."`,
review (hand-author PK changes), run the full suite (drift guard catches divergence).

**Next:** tasks on `feature/team-session-ui` (two-device manual verification of
team session sync); merge `feature/alembic-migrations` → `development`.

---

## 2026-06-11 — Team-session Android UI + device→server item sync (feature/team-session-ui)

Shipped the team-session UI plan (`docs/superpowers/plans/2026-06-10-team-session-ui.md`,
9 tasks + 6.5 addendum), all on `feature/team-session-ui`.

**Android — team-session UI**
- Drawer workspace switcher (`DrawerContent.kt` + `ListManagerApp.kt`): Personal / each of
  my teams / "Manage teams…"; team list refreshed on drawer open; switching navigates to
  the session screen and `SessionViewModel` reloads for the new workspace.
- New routes `teams` and `team_detail/{teamId}?name=` — `TeamsScreen` (list/create/join with
  invite code, "switch now?" dialog) and `TeamDetailScreen` (members, generate & share invite
  code, remove member as admin, leave team with Personal fallback).
- `Workspace` sealed class + persisted `WorkspaceManager` singleton (unit-tested); workspace
  reset to Personal on logout so it can't leak across users on a shared device.
- UX hardening from review: create/join double-submit guards (`isSubmitting`), inline
  "Couldn't load" + Retry states on Teams/TeamDetail (`loadFailed`), Teams list refreshes on
  ON_RESUME (`LifecycleResumeEffect`), Leave disabled until own user id known, friendly
  last-admin-leave 400 message.

**Backend**
- Team self-removal: members may now `DELETE /api/teams/{id}/members/{own_user_id}` (leave);
  last-admin leave still 400.
- WS: session/item broadcasts now include the session `version` so clients can detect staleness.

**Android — device→server write-through item sync (Task 6.5 scope addition)**
- Item add/update/delete/clear now write through to the server when online and enqueue
  pending ops when offline (previously the app had NO device→server upload path for items;
  team sessions were one-way server→device).

**Migration notes (behavior changes)**
- **PDF generation now COMPLETES the shared session** (`POST /api/session/{id}/complete`)
  instead of clearing items in place — affects ALL clients: after a PDF the active session
  is a new/next one, not the same emptied session.
- **Android DB schema v4, destructive migration**: on first launch after upgrade the local
  session cache AND the pending-op queue are wiped once (unsynced offline ops from before
  the upgrade are lost).

**Open follow-ups (pre-existing or known, tracked in the plan's Task 5/6 addenda)**
- `session_items` id collision: offline-created local items use positive autogenerated ids;
  server-mirrored rows `REPLACE` into the same table — needs negative local ids or a
  separate serverId column (same fix already applied to sessions).
- Pending ops queued against a negative fallback session id never drain after resolve
  (replay hits 404 forever) — re-target or drop them during resolve.
- Item stranding: items added to a negative-id fallback session are orphaned when
  `activateServerSession` deactivates it without migrating items.
- Resolve-blocking UX: workspace resolve can block the session screen for up to the 30s
  OkHttp timeout — consider `withTimeout(5s)` + cached-items-first.
- Idempotency keys: backend supports `idempotency_key` on add-item but Android never sends
  one — timeout-after-apply + replay can double-increment.
- `TeamMemberDTO` has no username (members shown as "User <id>"); no role-management UI
  (promote/demote) yet.
- AccessLost fallback is silent: losing team access drops you to Personal with only the
  header label changing — the design spec asked for a toast/snackbar notice.
- Cosmetic race: creating a team while an ON_RESUME refresh is in flight can skip the
  post-create list refresh (team appears after re-entering the screen).

**Post-review hardening (same branch, after this entry was first written)**
- Backend rejects item adds to inactive sessions (400 + test; suite now 162); Android
  SyncService treats 400 as non-retryable so stale queued ops drop instead of retrying forever.
- Android reacts to `session_completed`/`session_created` WS events (marks the local session
  inactive, re-resolves via the new `SessionEvents` signal) — without this, other devices
  kept writing into a session someone had completed via PDF generation.
- Drawer team cache cleared on logout.

**Next:** manual two-device verification (create/join via invite code, WS fan-out of items,
offline add → reconnect sync, Personal/team isolation, PDF-complete → fresh session on both
devices).

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
