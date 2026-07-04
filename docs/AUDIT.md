# ListManager — Phase 0 Audit

Read-only recon. No code changed. Scope: `backend-fastapi/`, `android-native/`,
`flutter_ui/`. Date: 2026-06-10. Backend reviewed at commit on branch `development`.

---

## 1. Architecture map

```
                    ┌──────────────────────────┐
                    │   FastAPI backend         │
                    │   backend-fastapi/app/    │
   Android  ──REST─▶│   main.py  (all routes)   │
   (Kotlin)         │   auth.py  (JWT/bcrypt)   │◀─REST── Flutter
   primary  ◀──WS───│   models.py (SQLAlchemy)  │         (prototype,
   client           │   websocket_manager.py    │          fake service,
            ◀──WS──▶│   /ws broadcast           │          not wired)
                    └──────────┬───────────────┘
                               │ SQLAlchemy 2.0 ORM (no Alembic)
                               ▼
                    SQLite (dev: listmanager.db) / Postgres (prod, DATABASE_URL)
                    create_all() + auto-seed on startup
```

**Data model** (`app/models.py`):
- `User(id, username, email, hashed_password, role[USER|ADMIN], is_active, created_at)`
- `Distributor(id, distributor_name, created_at)` ──1:N──▶ `Product`
- `Product(id, name, distributor_id→Distributor, aliases, created_at)`
- `GlobalSession(id, name, is_active, created_at, completed_at, version)` ──1:N──▶ items
- `GlobalSessionItem(id, session_id→GlobalSession, product_id→Product, quantity, version, created_at, updated_at)`

**No ownership** links `User` to `GlobalSession`. Sessions are global/singular: creating one
deactivates all others (`main.py:439`).

**Core flows:**
- **(a) Add item by voice** — *entirely on-device on Android*: `AndroidSpeechProvider` →
  transcript → `ProductRanker.rank()` (util/ProductRanker.kt, weighted blend of
  Levenshtein/Jaccard/token/phonetic/prefix/contains/number) → user confirms →
  `POST /api/session/{id}/items`. Backend does no speech/ranking.
- **(b) Realtime propagation** — any mutating endpoint calls `manager.broadcast(...)` →
  every connected client over `/ws?token=<jwt>`. Broadcast is global (no per-session/team
  scoping).
- **(c) Android offline edit** — writes queued in `PendingOperationEntity`; `SyncWorker` /
  `SyncService` replay them on reconnect. Item writes carry a `version` for optimistic
  locking (409 on mismatch).

---

## 2. Endpoint inventory (`app/main.py`)

| Method | Path | Auth today | Validation | Response model | Notes |
|---|---|---|---|---|---|
| GET | `/`, `/health` | none | — | — | ok |
| POST | `/api/auth/register` | none (public) | `UserCreate` | `UserDTO` | ok; 72-byte pw guard |
| POST | `/api/auth/login` | none (public) | `UserLogin` | `Token` | **no rate limit** |
| GET | `/api/auth/me` | user | — | `UserDTO` | ok |
| — | `PUT /api/auth/me` | — | — | — | **Android calls it; backend has no such route** |
| GET | `/api/catalog/distributors` | none | — | list DTO | open read |
| GET | `/api/catalog/distributors/{id}` | none | — | DTO | open read |
| POST | `/api/catalog/distributors` | **none** | `DistributorCreate` | DTO | **P0 open mutation** |
| PUT | `/api/catalog/distributors/{id}` | **none** | `DistributorCreate` | DTO | **P0 open mutation** |
| DELETE | `/api/catalog/distributors/{id}` | **none** | — | dict | **P0 open mutation; cascades products** |
| GET | `/api/catalog/products` | none | `search` query | list DTO | `ilike` (parameterized, safe) |
| GET | `/api/catalog/products/{id}` | none | — | DTO | open read |
| POST | `/api/catalog/products` | **none** | `ProductCreate` | DTO | **P0 open mutation** |
| PUT | `/api/catalog/products/{id}` | **none** | `ProductCreate` | DTO | **P0 open mutation** |
| DELETE | `/api/catalog/products/{id}` | **none** | — | dict | **P0 open mutation** |
| GET | `/api/session/active` | none | — | DTO | open read |
| POST | `/api/session/create` | user | `GlobalSessionCreate` | DTO | deactivates all others |
| POST | `/api/session/{id}/complete` | user | — | raw dict | no `response_model` |
| DELETE | `/api/session/{id}/items` | user | — | dict | clears any session |
| GET | `/api/session/{id}/items` | none | — | list DTO | open read |
| POST | `/api/session/{id}/items` | user | `...ItemCreate` | DTO | session_id not validated (see P2) |
| PUT | `/api/session/items/{id}` | user | `...ItemUpdate` | DTO | optimistic lock (409) |
| DELETE | `/api/session/items/{id}` | user | — | dict | any user, any item |
| GET | `/api/stats` | none | — | dict | open read |
| GET | `/api/admin/users` | admin | — | list DTO | ok |
| DELETE | `/api/admin/users/{id}` | admin | — | dict | ok; self-delete blocked |
| GET | `/api/protected/test` | user | — | dict | example |
| WS | `/ws?token=` | JWT in query | — | — | global broadcast |

Note: "user"/"admin" auth = `get_current_user` / `get_current_admin_user`. Any authenticated
user can mutate **any** session/item — acceptable under today's single-shared-session design,
but becomes object-level-authz debt the moment Phase 2 adds ownership.

---

## 3. Sync mechanism — current behavior

- **What syncs:** catalog + session + item mutations, fanned out as JSON events over `/ws`
  (`session_item_added/updated/deleted`, `product_*`, `session_*`, etc.).
- **Direction:** server→all clients broadcast (`websocket_manager.py:44`); clients also
  pull via REST. Inbound WS messages handle only `ping`/`pong`.
- **Conflict resolution:** optimistic locking on item quantity — client sends `version`;
  mismatch → HTTP 409 with both versions (`main.py:680`). Add-existing-item increments
  quantity server-side (`main.py:612`). No deltas, no op-log.
- **Identifiers:** server-generated integer PKs only. No client-generated UUIDs.
- **Two devices:** both see the same single global session. Concurrent edits to the *same
  item* → second writer gets 409 and (on Android) the queued op must be reconciled. Edits to
  *different* items are independent. **Offline + offline on the same item = one writer's
  change is rejected on replay** (data-loss risk once teams make this common — Phase 3).

---

## 4. Security audit (OWASP API Top 10, concrete)

- **Broken object/function-level authz —** catalog/distributor/product **create/update/delete
  require no authentication** (`main.py:191,228,261,320,348,386`; each docstring literally
  says "no auth required for demo"). Anyone reachable on the network can rewrite or wipe the
  catalog (delete-distributor cascades products). **P0.**
- **Secrets —** `SECRET_KEY` defaults to `"fallback-key-only-for-dev"` (`auth.py:16`). If the
  env var is unset in prod, **all JWTs are forgeable** with a publicly-known key. **P0.** No
  secrets found in git history; no `.env` tracked; Android `BASE_URL` correctly via
  `BuildConfig`/`secrets.properties`. Good.
- **Default credentials —** `seed_admin.py` creates `admin/admin123` and `user/user123`,
  env-overridable but defaulting to known values, and **re-resets the admin password to the
  default on every startup** if it doesn't match (`seed_admin.py:37-41`). In prod without
  `ADMIN_PASSWORD` set this is a known-credentials admin account. **P0.**
- **Authentication —** passwords bcrypt-hashed (good). JWT HS256, 24h expiry, **no refresh,
  no revocation, no logout server-side**. Stolen token valid for 24h. **P1.**
- **Rate limiting —** none on `/api/auth/login` or `/register`. Brute-force / credential
  stuffing open. **P1.**
- **Injection —** product search uses `Product.name.ilike(f"%{search}%")` via ORM
  parameters (`main.py:298`) — **not** string-built SQL; safe. No `os.system`/shell calls. OK.
- **Mass assignment / over-exposure —** request models are scoped (no `role`/`is_active` in
  `UserCreate`); response uses explicit DTOs; `UserDTO` excludes `hashed_password`. Low risk.
  `update_product`/`update_distributor` overwrite all fields from the create model — benign
  today (no sensitive fields). **P2.**
- **CORS —** no `CORSMiddleware` configured. Fine for native mobile; **must** be added
  (non-wildcard with credentials) before any browser/Flutter-web client. **P2.**
- **Error/debug leakage —** no global exception handler; FastAPI default hides tracebacks
  unless `debug=True` (not set). OK. WebSocket logs user ids only.
- **Dependency health —** `pip-audit` not run (read-only phase, no install). Pinned versions
  in `requirements.txt`; `fastapi==0.109.0`, `python-jose==3.3.0`, `bcrypt==4.0.1` are older
  — run `pip-audit` in Phase 1. **TODO.**

---

## 5. Test reality check

- Suite **runs but is RED right now: 6 failed, 66 passed** (`venv/Scripts/python -m pytest`).
- All 6 failures are `tests/test_catalog.py::*_no_auth` asserting the catalog mutations return
  **403**; they return **200**. The tests already encode the intended secure behavior — they
  are a ready-made spec for the P0 fix, and will go green when Phase 1 §1 lands.
- `tests/TEST_SUMMARY.md` claims "60/60 passing" — **stale** (suite is now 72 tests). Update
  or delete it.
- Coverage is solid on backend happy/error paths; **no** tests for: WebSocket behavior,
  offline-replay/idempotency, or any cross-user authorization (none exists to test yet).
- Android: `Milestone3IntegrationTest.kt` + example tests present (not run this phase).

---

## 6. Findings (prioritized)

| ID | Sev | Finding | Location | Proposed fix |
|----|-----|---------|----------|--------------|
| F1 | **P0** | Catalog/distributor/product create/update/delete need no auth | `main.py:191,228,261,320,348,386` | Require auth; gate mutations behind ADMIN. Makes 6 red tests pass. |
| F2 | **P0** | Forgeable JWTs via public `SECRET_KEY` fallback | `auth.py:16` | Remove fallback; fail-fast if unset; load via pydantic-settings. |
| F3 | **P0** | Default/known admin creds, force-reset each startup | `seed_admin.py:19,37-44` | Require `ADMIN_PASSWORD`; don't auto-reset; seed only if absent; gate seeding to dev. |
| F4 | P1 | No token refresh/revocation; 24h stolen-token window | `auth.py:50-61` | Document story; add refresh or shorten access token + refresh. |
| F5 | P1 | No rate limiting on auth endpoints | `main.py:113` | Add slowapi limiter to login/register. |
| F6 | P1 | Android `PUT /api/auth/me` has no backend route | `ListManagerApi.kt:52` vs `main.py` | Implement endpoint or remove client call (AccountScreen edit is broken). |
| F7 | P1 | WS broadcast is global (no scoping) | `websocket_manager.py:44` | Acceptable now; must scope per session/team in Phase 2/3 to avoid cross-team leak. |
| F8 | P2 | `add_session_item` doesn't verify session exists/active | `main.py:599` | Validate session_id; reject inactive/missing (FK may not be enforced on SQLite). |
| F9 | P2 | Deprecated `@app.on_event("startup")` | `main.py:28` | Migrate to lifespan handler. |
| F10 | P2 | Stale `TEST_SUMMARY.md` (claims 60/60) | `tests/TEST_SUMMARY.md` | Regenerate or delete. |
| F11 | P2 | No CORS config (blocks future web client) | `main.py` | Add CORSMiddleware with explicit origins before web/Flutter-web. |
| F12 | P2 | `pip-audit` not yet run | `requirements.txt` | Run in Phase 1; bump any CVE deps (pinned + noted). |

---

## 7. Plan deltas for Phases 1–4

- **Phase 1 is well-scoped and high-value** as written: F1/F2/F3 are the P0 core; the 6 red
  tests are the acceptance criteria for F1. Add F5 (rate limit), F6 (auth/me), and run
  pip-audit (F12). The "centralized auth seam" task is the right setup for Phase 2.
- **Phase 2 (teams)** — heavier than a normal phase because there is *zero* ownership today.
  The single-global-session migration (2a) is the data-loss stop-point; get sign-off.
- **Phase 3 (sync)** — F7 + the offline-offline data-loss case are the real drivers; keep the
  existing WebSocket + optimistic-lock and add the op/idempotency layer only where teams make
  concurrent offline edits likely.
- **Phase 4 (voice location)** — unchanged: still a decision, no code until chosen.

**STOP — human review.** Recommended next session: *"Execute Phase 1 only. Start in plan mode."*
