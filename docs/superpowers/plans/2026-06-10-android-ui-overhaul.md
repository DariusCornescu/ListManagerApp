# Android UI Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android client correct against the hardened backend — fix the blocking bugs, wire real session sync (Approach A), and polish the UX.

**Architecture:** Online-first repositories with an offline pending-op queue (reusing `SyncService`/`PendingOperation`); a single auth `StateFlow` gating navigation; the **server** session id as the canonical session identity; WebSocket for live multi-device updates.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Retrofit/OkHttp, Coroutines/Flow. Spec: `docs/superpowers/specs/2026-06-10-android-ui-overhaul-design.md`.

**Verification (Android-specific):** there is no fast unit-test loop for the UI in this environment. Per task: `cd android-native; .\gradlew.bat assembleDebug` must compile clean. Per **batch** (workstream): `.\gradlew.bat installDebug` then Darius tests on-device against the running backend (`localhost:8000` via `adb reverse`). Commit per task (no co-author trailer). Executing subagents must **read the named files** before editing.

---

## Workstream 1 — Blocking bug fixes

### Task 1: Typed repository results for catalog writes
**Files:** Modify `data/repository/ProductRepository.kt` (insert/update/delete ~`:96-103,244-250,291-297`), `data/repository/DistributorRepository.kt` (~`:92-98,146-152,193-199`); add `data/repository/RepoResult.kt`.
- [ ] Create a sealed `RepoResult` (`Success`, `Forbidden`, `QueuedOffline`, `Error(message)`).
- [ ] In each write method, branch on the outcome: HTTP 2xx → `Success`; **`IOException`/no connectivity only** → write local + enqueue pending op → `QueuedOffline`; HTTP 401/403 → `Forbidden` (do **not** write local, do **not** enqueue); other non-2xx → `Error`. Remove the current "any failure falls through to offline".
- [ ] Verify: `assembleDebug` compiles. Commit `fix(android): typed catalog repo results, stop fake-success on 403`.

### Task 2: SyncService — non-retryable 4xx, drop bad ops
**Files:** Modify `sync/SyncService.kt` (`:264,281,297,315,331,348` mappers; `handleConflict` `:426-443`), `sync/SyncWorker.kt` (`:100-103`).
- [ ] Map HTTP 401/403/422 to `retryable = false` and delete the pending op (don't loop forever). Keep 5xx/IO as retryable.
- [ ] Verify compile. Commit `fix(android): drop non-retryable (401/403/422) sync ops instead of infinite retry`.

### Task 3: Role-gate catalog mutation UI + surface 403
**Files:** Modify `ui/viewmodel/CatalogViewModel.kt` (`:172-178`), `ui/viewmodel/EditProductViewModel.kt` (`:79-80`), `ui/screens/CatalogScreen.kt`, `ui/screens/EditProductScreen.kt`; read role from `AuthViewModel`.
- [ ] Load the current user's `role` (via `getCurrentUser()` / cached) into an exposed state.
- [ ] Hide/disable add/edit/delete affordances when role != `ADMIN` (read stays). In ViewModels, on `RepoResult.Forbidden` show a snackbar ("Doar administratorii pot modifica catalogul") and do **not** report success.
- [ ] Verify compile. Commit `feat(android): gate catalog edits by admin role + show 403 message`.

### Task 4: Auth gate + start destination + auth StateFlow
**Files:** Modify `ListManagerApp.kt` (`:55-76` polling, `:67` startDestination), `ui/navigation/NavGraph.kt` (`:28,35`), `ui/viewmodel/AuthViewModel.kt` (`:38-47`).
- [ ] Replace the per-navigation `EncryptedSharedPreferences` polling with a single `AuthViewModel` `StateFlow<Boolean> isLoggedIn`, updated on login/logout.
- [ ] `startDestination = if (isLoggedIn) "home" else "login"`; on logout navigate to `login` clearing back stack.
- [ ] Verify compile. Commit `fix(android): real auth gate + observable login state`.

### Task 5: 401 / token-expiry recovery
**Files:** Modify `network/RetrofitClient.kt` (`:19-31`); a callback into auth/logout.
- [ ] Add an OkHttp `Authenticator` (or response interceptor) that on 401 clears the token, disconnects the WebSocket, and signals the app to route to `login` ("Sesiune expirată").
- [ ] Verify compile. Commit `fix(android): handle 401 by clearing session and routing to login`.

### Task 6: Account profile editing
**Files:** Modify `ui/screens/AccountScreen.kt`, `ui/viewmodel/AuthViewModel.kt`; uses `ListManagerApi.getCurrentUser()` + `updateCurrentUser(UpdateUserRequest)` (`network/ListManagerApi.kt:49-53`).
- [ ] Load real account (`getCurrentUser` → username/email/role) instead of the sharedprefs/hardcoded values (`AccountScreen.kt:112,177`).
- [ ] Add an "Edit profile" form (email + optional new password) calling `updateCurrentUser`; show success/error.
- [ ] Verify compile. **Batch boundary:** `installDebug`; Darius tests WS1 on device. Commit `feat(android): account profile view + edit via PUT /api/auth/me`.

---

## Workstream 2 — Session sync (Approach A)

### Task 7: Room — server session id as canonical
**Files:** Modify `data/local/entity/SessionEntity.kt`, `data/local/AppDatabase.kt` (bump version), `data/local/dao/SessionDao.kt` (`:33-42`).
- [ ] Store the server session id on the active session (use it as the id, or add `serverId`). Bump the Room DB version; a **destructive migration / clean reinstall is acceptable** (dev decision).
- [ ] Verify compile. Commit `feat(android): persist server session id on session entity`.

### Task 8: Backend-aware session resolution + hydrate
**Files:** Modify `data/repository/SessionRepository.kt` (`:31-45`), `ui/viewmodel/SessionViewModel.kt`; uses `getActiveSession`/`createSession`/`getSessionItems`.
- [ ] On session load while online: `GET /api/session/active`; on 404 → `POST /api/session/create`; persist server id; `GET /api/session/{id}/items` → upsert into Room. Offline → use the local cached active session.
- [ ] Verify compile. Commit `feat(android): resolve and hydrate the server active session`.

### Task 9: Session mutations online + offline queue
**Files:** Modify `data/repository/SessionRepository.kt`, `network/DTOs.kt` (add optional `idempotency_key` to `AddItemRequest`/`UpdateItemRequest`), `sync/SyncService.kt` (activate the dead session branch `:357-422`).
- [ ] add/update/delete/clear: online → call API (with a generated `idempotency_key` UUID), update Room from response; offline (`IOException`) → optimistic Room write + enqueue `PendingOperation` keyed by **server** session id + the same idempotency key.
- [ ] Wire `SyncService` session branch to replay these (it already exists, dead); apply Task 2's non-retryable rule.
- [ ] Verify compile. Commit `feat(android): session item mutations via API with offline queue + idempotency`.

### Task 10: WebSocket session events + reconnect
**Files:** Modify `MainActivity.kt` (`updateLocalSessionItem` `:211-228`), `data/local/dao/SessionItemDao.kt` (`:19-30`), `data/websocket/WebSocketService.kt` (`:104-129` parsing, `:214-227` reconnect).
- [ ] Apply incoming session events under the **server** session id (now matches Room). Upsert the referenced product before inserting the item (or LEFT JOIN so unknown-product items still show).
- [ ] Use `opt*` JSON getters consistently; normalize `"null"`/empty → null. Add reconnect-with-backoff on `onFailure`/`onClosed` + schedule the existing `sendPing()` keepalive.
- [ ] Verify compile. Commit `fix(android): WS session events use server id, upsert product, auto-reconnect`.

### Task 11: 409 conflict prompt
**Files:** Modify `ui/viewmodel/SessionViewModel.kt`, `ui/screens/SessionScreen.kt`.
- [ ] On a 409 from `updateSessionItem`, expose a "modified elsewhere — reload?" state; reload re-fetches items + version.
- [ ] Verify compile. **Batch boundary:** `installDebug`; Darius tests WS2 (two devices / re-login). Commit `feat(android): surface 409 session conflicts with reload`.

---

## Workstream 3 — UX / visual polish

### Task 12: Localize hardcoded strings + plurals
**Files:** Modify `res/values/strings.xml` (+ `values-ro` if present), `ui/screens/HomeScreen.kt` (`:252,383,433`), `ui/viewmodel/HomeViewModel.kt` (`:85,102,114,140`), `ui/viewmodel/CatalogViewModel.kt` (`:174`).
- [ ] Move English user-facing strings to resources, Romanian copy; use Android plural resources for the "s" pluralization bugs.
- [ ] **Fix the score badge:** `HomeScreen.kt:433` render `(score*100).toInt()` + "%", not the raw fraction.
- [ ] Verify compile. Commit `fix(android): localize strings, plural resources, score badge percent`.

### Task 13: Login + Catalog + EditProduct UX
**Files:** Modify `ui/screens/LoginScreen.kt` (`:353-364,370`), `ui/screens/EditProductScreen.kt` (`:140-152`), `ui/screens/CatalogScreen.kt` / `SessionScreen.kt` / `UnknownProductsScreen.kt`.
- [ ] Remove demo creds line; clarify "Continuă Offline" limitations (or disable writes when offline-unauth).
- [ ] EditProduct: distributor becomes a dropdown (data already in `CatalogViewModel`).
- [ ] Add `PullToRefreshBox` to Catalog/Session/Unknown.
- [ ] Verify compile. Commit `feat(android): login/offline clarity, distributor dropdown, pull-to-refresh`.

### Task 14: Cleanup pass
**Files:** Modify `ui/viewmodel/SessionViewModel.kt` (`:26,91,95`), `MainActivity.kt` (`:260-295`), `ui/screens/HomeScreen.kt` (`:442-452`), back-arrow imports (`CatalogScreen.kt:167`, `EditProductScreen.kt:57`, `UnknownProductsScreen.kt:45`), `data/repository/ProductRepository.kt` (`:38-40`), `CatalogScreen.kt` (`:38-61`).
- [ ] Clear session only after a confirmed PDF share; remove the always-null `pdfGenerationProgress`; standardize back-arrows on `Icons.AutoMirrored`; delete dead code (`testDatabaseAccess`, unused `formatSyncTime`); catalog refresh upserts instead of `deleteAll`+reinsert and de-dupes the double load.
- [ ] Verify compile. **Batch boundary:** `installDebug`; Darius tests WS3. Commit `chore(android): UX cleanup (pdf-share, icons, dead code, catalog upsert)`.

---

## Workstream 4 — Premium aesthetic + team UX (Editorial Minimal, light + dark)
Decision: Editorial Minimal (light-first, matching dark); personal-first with a team switcher.

### WS4a — Theme + redesigned login (independent; build right after WS1)
- **Theme** (`ui/theme/Color.kt`, `Theme.kt`, `Type.kt`): replace the default purple template.
  Light: bg `#F7F6F3`, surface `#FFFFFF`, primary `#4F46E5`, onPrimary `#FFFFFF`, text `#18181B`,
  muted `#6B7280`, outline/hairline `#E7E5E0`, success `#16A34A`, error `#DC2626`.
  Dark: bg `#18181B`, surface `#1F1F23`, primary `#6366F1`, text `#F4F4F5`, muted `#9CA3AF`,
  outline `#2E2E33`. **Disable `dynamicColor`** (keep brand colors). Bundle **Inter** in
  `res/font` + a full type scale (SemiBold/Bold headings w/ tight tracking, Regular body).
- **Components** (`ui/components/`): `PrimaryButton` (full-width 52dp indigo, 14dp radius),
  `AppTextField` (labeled, outlined, indigo focus), `AppCard` (white, 1dp hairline + soft shadow).
- **Login/Register** (`ui/screens/LoginScreen.kt`): wordmark + tagline, labeled email/password
  with visibility toggle, full-width indigo Sign in, Create-account toggle (register gets an
  email field), de-emphasized "Continue offline" text link, **remove demo creds** (`:370`).
  Keep `AuthViewModel` hooks + the WS1 auth gate intact.
- Verify `assembleDebug`; batch boundary `installDebug`.

### WS4b — Team switcher + create/join (after WS2 session sync)
- Android team API + DTOs (`POST`/`GET /api/teams`, `POST /api/teams/{id}/invites`,
  `POST /api/teams/invites/{code}/accept`, `GET /api/teams/{id}/members`), `TeamRepository`/VM.
- Top-bar **context switcher** (Personal / Teams / Create team / Join with code); selecting a
  team sets the session context (ties to WS2: `createSession`/`getActiveSession` scoped by team).
- Create-team + Join-by-code screens.

**Revised order:** WS1 (done) → **WS4a** → WS2 → WS4b → WS3 (much absorbed by the theme).

## Self-review notes
- **Spec coverage:** WS1 §1.1→Tasks 1-3; §1.2→Task 4; §1.3→Task 5; §1.4→Task 6. WS2→Tasks 7-11. WS3→Tasks 12-14. All spec items mapped.
- **Verification caveat:** compile-checked here; behavior verified on-device per batch (no automated UI tests added — out of scope this pass).
- **Risk:** Room version bump (Task 7) → clean reinstall expected on the dev phone.
