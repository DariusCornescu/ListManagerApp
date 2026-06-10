# Android UI Overhaul — Design Spec

Date: 2026-06-10 · App: `android-native` (Kotlin, Jetpack Compose, Room, Retrofit) ·
Backend baseline: Phases 0–4 on branch `security-hardening` (158 tests green).

## Goal
Make the Android client correct and pleasant against the hardened backend, in three
workstreams (sequenced, each built + installed for on-device testing before the next):
1. **Blocking bug fixes** — the app currently fakes success and degrades silently.
2. **Session sync (Approach A)** — make the shopping session actually sync across devices.
3. **UX/visual polish** — localization, empty states, correctness of small UI details.

## Verification model
Changes are compiled and installed here (`gradlew installDebug`), but **runtime UI behavior
is verified by Darius on the device.** Each batch ends with a build + install + a short
"what to test" note; fixes iterate from his feedback. No runtime-success claims are made from
this environment.

## Out of scope (explicitly deferred)
- Full op-log/offline-first sync (client UUIDs, deltas, `/ops?since=`) — Approach B, later.
- Teams UI (teams remain backend-only for now).
- Wiring Android voice to the Groq `/speech/transcribe` endpoint (on-device STT stays).

---

## Workstream 1 — Blocking bug fixes

### 1.1 Catalog 403 handling (non-admins)
**Problem:** non-admin create/edit/delete of products/distributors returns 403, but repos fall
through to the offline path (`ProductRepository.kt:96-103,244-250,291-297`;
`DistributorRepository.kt:92-98,146-152,193-199`), show fake success
(`EditProductViewModel.kt:79-80`, `CatalogViewModel.kt:172-178`), and queue a pending op that
`SyncService` retries forever (`SyncService.kt:264,281,297,315,331,348`; `SyncWorker.kt:100-103`).

**Design:**
- Repos return a typed result: `Success | Forbidden | Offline(queued) | Error`. Distinguish
  **transport failure** (no network → queue offline) from **server rejection** (4xx → surface,
  do **not** queue). Only `IOException`/connectivity → offline branch.
- `SyncService`: map `401/403/422` to `retryable = false` and drop the pending op (and emit a
  one-time "permission denied / not synced" signal).
- **Gate the UI by role:** load the user's role (`GET /api/auth/me`) on login; hide/disable
  catalog create/edit/delete affordances for non-admins (read stays available). 403 handling
  remains as a safety net.

### 1.2 Auth gate + start destination
**Problem:** logged-out users land on Home with full drawer access
(`ListManagerApp.kt:67`, `NavGraph.kt:28,35`, `DrawerContent.kt:29-38`).
**Design:** `startDestination = if (isLoggedIn) "home" else "login"`; redirect to `login` on
logout; gate write-capable destinations behind auth. Replace the per-navigation
EncryptedSharedPreferences polling (`ListManagerApp.kt:55-76`) with a single auth `StateFlow`
updated on login/logout.

### 1.3 401 / token-expiry recovery
**Problem:** no 401 handling anywhere (`RetrofitClient.kt:19-31`); expired token → silent
stale-cache/offline.
**Design:** add an OkHttp `Authenticator`/interceptor that on 401 clears the token, disconnects
the WebSocket, and routes to `login` with a "session expired" message.

### 1.4 Account profile editing
**Problem:** `PUT /api/auth/me` works now but there's no UI; `AccountScreen` shows a
sharedprefs username and hardcoded "Activ/Conectat".
**Design:** load the real account via `GET /api/auth/me` (username/email/role); add an
"Edit profile" form (email + optional new password) calling `updateCurrentUser`
(`UpdateUserRequest{email?, password?}` already matches backend `UserUpdate`).

---

## Workstream 2 — Session sync (Approach A: online-first + offline queue)

**Problem:** `SessionViewModel`/`SessionRepository` are Room-only; the session network + WS code
is dead and keyed on a local autoincrement id that never matches the server
(`SessionRepository.kt:31-45`, `SessionDao.kt:33-42`, `MainActivity.kt:211-228`).

**Design:**
- **Identity:** store the **server session id** as the canonical session id in Room (add
  `serverId`/use server id as PK for the active session). On login resolve it:
  `GET /api/session/active` → on **404** `POST /api/session/create` → persist id; then
  `GET /api/session/{id}/items` to hydrate Room.
- **Mutations (add / update qty / delete / clear)** go through `SessionRepository`:
  - **Online:** call the API (`addSessionItem`/`updateSessionItem`/`deleteSessionItem`/
    `clearSession`), update Room from the response.
  - **Offline (IOException):** write Room optimistically + enqueue a `PendingOperation`
    (`ADD/UPDATE/DELETE/CLEAR_SESSION_ITEM`) **keyed by the server session id**, with an
    **idempotency key** (UUID). Activate + fix the existing dead session branch in
    `SyncService.kt:357-422` to replay them; add `idempotency_key` to `AddItemRequest`/
    `UpdateItemRequest` (`DTOs.kt`) — the backend `GlobalSessionItemCreate/Update` now accept it.
  - 403/401/404 on replay → non-retryable (drop + surface), consistent with 1.1.
- **Real-time (WebSocket):** incoming session events now apply to the **server** session id, so
  the `MainActivity.updateLocalSessionItem` id-mismatch disappears. Upsert the referenced
  product first (or LEFT JOIN in `SessionItemDao.kt:19-30`) so WS items for not-yet-cached
  products don't vanish. Add reconnect-with-backoff + keepalive ping in `WebSocketService`
  (`:214-227`).
- **409 conflict (optimistic lock):** surface a "modified elsewhere — reload?" prompt in
  `SessionViewModel`; on reload, re-fetch items and version.

---

## Workstream 3 — UX / visual polish

**High:** move hardcoded English strings to `strings.xml` and localize (ro) — esp.
`HomeScreen.kt:252`, `HomeViewModel.kt:85,102,114,140`; remove demo creds from `LoginScreen.kt:370`;
fix the suggestion score badge (`HomeScreen.kt:433` shows `0.74%` → `(score*100).toInt()%`);
add pull-to-refresh to Catalog/Session/Unknown; make EditProduct distributor a dropdown
(`EditProductScreen.kt:140-152`); clarify "Continuă Offline" consequences (`LoginScreen.kt:353-364`).

**Medium:** real account status (Workstream 1.4); don't clear the session before a confirmed
PDF share (`SessionViewModel.kt:95`); remove the always-null `pdfGenerationProgress`; standardize
back-arrows on `Icons.AutoMirrored` (`CatalogScreen.kt:167`, `EditProductScreen.kt:57`,
`UnknownProductsScreen.kt:45`).

**Low:** Android plural resources (`HomeScreen.kt:383`, `CatalogViewModel.kt:174`); content
descriptions for TalkBack; remove dead/debug code (`MainActivity.testDatabaseAccess()` `:260-295`,
unused `formatSyncTime` `HomeScreen.kt:442-452`); fix catalog refresh to upsert instead of
`deleteAll`+reinsert (`ProductRepository.kt:38-40`) and de-dupe the double load
(`CatalogScreen.kt:38-61`).

---

## Sequencing & risks
- **Order:** WS1 (bugs) → WS2 (sync) → WS3 (polish). WS1 is independent and high-value; WS2 is
  the architectural core; WS3 is low-risk cleanup. Build + install after each.
- **Risks:** (a) runtime behavior unverifiable here — rely on device testing each batch;
  (b) WS2 touches the data layer (Room schema change for server id → a Room migration or a clean
  reinstall); (c) role-gating depends on `GET /api/auth/me` returning `role` (it does).
