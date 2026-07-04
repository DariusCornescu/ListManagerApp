# ListManager Upgrade — Master Prompt for Claude Code

> **How to use this file (read first, human):**
> 1. This file lives at `docs/UPGRADE_PROMPT.md`; `CLAUDE.md` is at the repo root.
> 2. Run **one phase per Claude Code session**. Start each session with:
>    *"Read CLAUDE.md and docs/UPGRADE_PROMPT.md. Execute Phase N only. Start in plan mode."*
> 3. Review the plan before approving. `/clear` between phases. Review diffs before committing.
> 4. Phases are ordered by dependency: **security hardening before introducing ownership**,
>    because the moment sessions become personal/team-scoped, every missing authorization
>    check becomes an exploitable cross-tenant bug. Harden the surface first, then add the
>    ownership model that raises the stakes.

---

## Mission

You are working on ListManager: a **FastAPI** backend + a **native Android (Kotlin/Compose)**
app (the primary, most complete client) + a **Flutter** client (currently an early
prototype on a fake service). Today the app centers on a **single global shared shopping
session** that all devices view and edit in real time; voice input is matched to a product
catalog by a custom weighted **on-device** similarity ranker (Android Kotlin).

Across the phases you will: audit the system as it really is, harden security, introduce a
**team-based ownership model** (personal vs. team sessions) on top of today's global-session
design, make multi-writer sync robust against the new sharing, and decide where voice
ingestion should live longer-term.

## Reality baseline (verified from the code — start here, do not re-assume)

- **DB:** SQLite in dev (`backend-fastapi/listmanager.db`), Postgres in prod via
  `DATABASE_URL`. SQLAlchemy 2.0 ORM. **No Alembic** — `Base.metadata.create_all()` on
  startup, then auto-seed. Any schema change to existing data needs a manual migration/cutover.
- **Auth:** bcrypt password hashing; JWT HS256 (`python-jose`), `Authorization: Bearer`;
  roles `USER`/`ADMIN`; 24h token, **no refresh/revocation**; `SECRET_KEY` has an
  **insecure dev fallback** in `app/auth.py`.
- **Sessions:** **one global shared session** (`GlobalSession` + `GlobalSessionItem`).
  **No per-user or team ownership exists yet.** Several catalog/distributor/product
  endpoints are **deliberately unauthenticated** ("no auth required for demo").
- **Sync today (already built):** real-time fan-out over WebSocket (`/ws?token=`),
  optimistic locking via a `version` column (→ HTTP 409 on conflict), and an Android
  **offline pending-operation queue** (`PendingOperation` + `SyncWorker`/`SyncService`)
  replayed on reconnect. There is **no** op-log / CRDT.
- **Speech + ranker:** **on-device Android**
  (`android-native/.../util/ProductRanker.kt`, `SimilarityEngine.kt`, `TextNorm.kt`;
  `AndroidSpeechProvider`, `ResolveSpokenProductUseCase`). **No backend ranker/pipeline.**
- **Flutter:** prototype only — product CRUD screens on `fake_product_service.dart`,
  not wired to the real API, auth, or sync.

## Global rules (apply in every phase)

- **Reality over assumption.** The code wins. If anything here is out of date, fix this
  doc and note it in `docs/PROGRESS.md` before proceeding.
- **Phase discipline.** Do only the current phase. Park out-of-phase discoveries in the
  `docs/PROGRESS.md` backlog and move on.
- **Each task = implement → test → run full suite → commit.** Small commits.
- **Stop conditions.** Stop and ask the user when: a migration would lose data (the single
  global session must be migrated carefully), a fix breaks a mobile-client contract, or two
  findings have conflicting fixes.
- Every phase ends with: green tests, updated `docs/PROGRESS.md`, and a short summary of
  what a reviewer should look at.

---

## Phase 0 — Recon & Audit (READ-ONLY: no code changes, no installs)

**Goal:** an accurate map + prioritized findings. Output: one file `docs/AUDIT.md`.

1. **Architecture map.** Modules/boundaries for backend / Android / Flutter; data model
   (every table + relationships); request flow for: (a) adding an item by voice (Android,
   on-device ranking → REST), (b) real-time update propagation over WebSocket, (c) Android
   offline edit → pending-op replay on reconnect. Text diagrams fine.
2. **Endpoint inventory.** Table: method, path, **auth required today?**, mutation vs read,
   input validation (pydantic model or raw?), response model present?, notes. Flag every
   endpoint currently marked "no auth required for demo".
3. **Sync mechanism — exact current behavior.** WebSocket broadcast contents + direction;
   optimistic-locking semantics (what 409 means to each client); what the Android
   pending-op queue stores and how it replays; what happens with two devices editing the
   one global session concurrently. Quote code. This determines Phase 3 difficulty.
4. **Security audit** against OWASP API Top 10, concretely for *today's* design:
   - **Unauthenticated mutations:** catalog/distributor/product create/update/delete are
     open. Who should be allowed to call them? (Likely admin-only.)
   - **Secrets:** the `SECRET_KEY` insecure fallback; grep repo + git history for hardcoded
     keys/DB URLs/passwords.
   - **Auth:** bcrypt confirmed; token has no refresh/revocation — document the gap.
   - **Injection:** the product search uses `ilike` with ORM params (parameterized) —
     confirm no raw string SQL anywhere; any shell calls?
   - **Mass assignment / over-exposure:** do update endpoints accept full models? Do
     response models leak fields (e.g. `hashed_password`)?
   - **Rate limiting** on login/register: present?
   - **CORS**, stack-trace leakage, debug mode in prod config.
   - **Note (forward-looking):** object-level authorization is N/A today (no ownership) but
     becomes critical in Phase 2 — call this out so Phase 1 builds the seam for it.
   - Dependency health: `pip-audit` (read-only), list CVEs.
5. **Test reality check.** What `backend-fastapi/tests/` covers, gaps, can the suite run clean.
6. **Findings list**, tagged **P0** (exploitable / data loss), **P1** (weakness),
   **P2** (hygiene), with file:line + one-line proposed fix.
7. **Proposed plan deltas** for Phases 1–4 given what you found.

**Done when:** `docs/AUDIT.md` exists, is specific (file:line, not vibes), ends with the
prioritized table. STOP for human review.

---

## Phase 1 — Security Hardening (today's surface, before ownership exists)

**Goal:** every P0 fixed, P1s fixed or explicitly deferred. Work from `docs/AUDIT.md`,
order by severity, one finding per commit (`sec:` prefix).

1. **Authenticate the open endpoints.** The catalog/distributor/product mutations currently
   require no auth. Require authentication; gate mutations behind `ADMIN` (or a documented
   policy). Keep read endpoints' policy explicit.
2. **Secrets to environment.** Remove the insecure `SECRET_KEY` fallback — **fail fast** if
   unset. `.env` + `.env.example`, settings via `pydantic-settings`, `.gitignore` verified.
   If secrets exist in git history, flag for rotation in `PROGRESS.md` (do **not** rewrite
   history without asking).
3. **Auth baseline.** bcrypt already in place. Decide and **document** a refresh/revocation
   story (even if minimal: short access token + refresh, or documented "tokens are 24h,
   logout is client-side only" with the risk noted).
4. **Validation & exposure.** Confirm pydantic request models on all endpoints; add length
   limits on free-text (names, future transcripts ≤ 2000 chars); verify explicit response
   models so no endpoint leaks extra fields (kills mass-assignment + over-exposure).
5. **Rate limiting** (e.g. slowapi) on login/register.
6. **Centralized auth seam.** Introduce a single dependency (e.g. `require_user`,
   `require_admin`) used everywhere — the seam that Phase 2's object-level checks will plug
   into. Do **not** add ownership logic yet; just stop copy-pasting `Depends(get_current_user)`
   semantics ad hoc.

**Done when:** suite green; open endpoints closed; secrets externalized; `AUDIT.md` findings
annotated fixed/deferred; `PROGRESS.md` updated. STOP.

---

## Phase 2 — Ownership & Teams (introduce the model that doesn't exist yet)

**Goal:** sessions stop being one global object and gain ownership: **personal sessions**
(owner-only) and **team sessions** (all team members read/write; admins manage membership).
This is net-new — there is currently no `user_id`/`team_id` on sessions at all.

### 2a. Domain model
- `Team(id, name)`, `TeamMember(team_id, user_id, role ∈ {admin, member})`, invite
  mechanism (short-lived invite code is enough).
- `Session` gains `owner_user_id` (personal) and nullable `team_id` (team). Exactly one
  ownership dimension set per session; document the rule.
- **Migration of the existing global session — STOP and ask before running.** Options to
  present: (a) convert the current active global session into a personal session of the
  admin user, or (b) into a default team's session. No data loss. Document the cutover and
  how `is_active` semantics change when sessions are no longer singular.

### 2b. Object-level authorization (now it matters)
- One helper `require_session_access(session_id, user) -> Session` used by **every** session
  and session-item endpoint, built on the Phase 1 auth seam. Return **404** (not 403) for
  sessions the user can't see, to avoid ID enumeration.
- For team sessions: membership grants read/write; only admins manage membership.

### 2c. The authz test matrix (the most valuable artifact here)
Parametrized tests: non-member vs member vs admin × every session/item operation ×
personal vs team session → each gets the right 401/403/404/200. This matrix is what keeps
Phase 3 honest.

**Done when:** ownership model migrated (with human sign-off), every session endpoint behind
the access helper, authz matrix green, `PROGRESS.md` updated. STOP.

---

## Phase 3 — Multi-Writer Sync Hardening (adapt what already exists)

**Goal:** concurrent edits across team members — including offline — converge without
silently losing changes. **Do not start greenfield:** WebSocket fan-out, optimistic locking
(version→409), and the Android offline pending-op queue already exist. First evaluate, then
upgrade only where the team-sharing from Phase 2 breaks the current approach.

### 3a. Design before code — `docs/SYNC_DESIGN.md` (~1 page)
Compare the **current** mechanism (optimistic locking + last-writer-409 + replay queue)
against a **target** op-based design, and decide per-concern which to adopt:

- **Where optimistic locking is enough** vs. where it loses data (two offline users both
  edit the same item → one's replay 409s and is dropped). Document the failure modes.
- **Target building blocks to adopt where needed:**
  - Operation-based ops (`AddItem`, `ChangeQty`, `SetUnit`, `CheckItem`, `RemoveItem`)
    instead of state snapshots.
  - **Client-generated UUIDs** for items (two offline users never collide).
  - **Idempotency keys** (op UUID) so replaying a flaky offline batch is safe — the Android
    queue already replays; make replay idempotent server-side.
  - **Quantity as deltas** (`+2`, `-1`) so "both added milk offline" sums instead of
    overwriting.
  - **Add-add same `product_id`** → merge into one item, sum quantities (the add endpoint
    already increments on existing — extend that to the op model).
  - **Tombstones** for deletes; document the delete-vs-concurrent-update rule
    (recommended: delete wins; explicit re-add resurrects).
  - **Per-session monotonic `seq`**; pull endpoint `GET /sessions/{id}/ops?since=<seq>`
    designed so the existing WebSocket push and the offline pull share one op model.
- **Keep WebSocket for liveness** (it's already built); the op-log is for correctness/merge,
  not a replacement for the realtime channel.

### 3b. Tests that earn trust
- Two simulated clients, both offline, divergent edits on a **team** session, both replay →
  assert converged state (items, summed quantities, delete rule honored).
- Idempotency: replaying the same op batch twice changes nothing.
- The Phase 2 permission matrix still holds under concurrent ops.

**Done when:** convergence + idempotency + permission tests green, `SYNC_DESIGN.md` matches
the implementation, Android offline queue interops with the new model, existing clients
still function. STOP.

---

## Phase 4 — Voice Ingestion: location decision (DECISION REQUIRED before starting)

**Status: undecided.** Today, speech capture + the weighted ranker are **on-device Android**
and work. Before any work here, the human must choose a direction. Write the analysis to
`docs/SPEECH_DESIGN.md` and STOP for the decision.

- **Option A — stay on-device (Android only).** No backend speech work. Other clients
  (Flutter/web) get no voice. Lowest cost; ranker logic stays in Kotlin only.
- **Option B — backend ingestion + ranking pipeline.** Build a server-side endpoint that
  takes a transcript and returns matched / needs-confirmation / unknown buckets, so Flutter
  and future web clients share one ranker. Implications: port/duplicate the Kotlin ranking
  logic to Python (or define a shared contract + conformance corpus so both stay in sync),
  add `rapidfuzz` or similar, a DB-backed catalog loader (the `aliases` column already
  exists), rate limiting, transcript length caps, and the Phase 2 access check on the
  session it writes to.

Capture the trade-offs (latency, offline support, duplicated logic, which clients need
voice) and let the human pick. Do **not** implement until chosen.

---

## Phase 5 — Flutter Client (currently a prototype)

**Goal (scope to be confirmed):** the Flutter client runs on `fake_product_service.dart`
with no real API/auth/sync. Decide its role — throwaway prototype, or a real second client.
If real: wire it to the backend API, JWT auth, the WebSocket channel, and (post-Phase 2)
team-scoped sessions. Treat as a normal phase only once its purpose is confirmed with the
human.

---

## Backlog (do not start without explicit instruction)

- WebSocket presence ("Maria is editing") + richer live updates.
- Catalog alias learning: confirmed voice matches become new aliases (the `aliases` column
  already supports this).
- Per-team analytics; audit log of ops (free once the Phase 3 op log exists).
- Shared ranker conformance corpus (only if Phase 4 Option B is chosen) so Kotlin and any
  Python ranker stay behaviorally identical.
