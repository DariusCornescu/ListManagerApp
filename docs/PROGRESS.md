# Progress Log

Running log of working sessions: what changed, what's next, open questions.
Newest entries on top.

---

## 2026-11-06 - Catalog at scale: imports reach phones without opening Catalog (WP3)

**Changed**
- Backend: the two public catalog lists answer `If-None-Match` with a bodyless 304, and large
  responses are gzipped. A committed import that changed rows broadcasts `catalog_changed` with
  a new `change_id`; old apps ignore the unknown type. No migration, and paths are unchanged.
- Android: new `sync/CatalogRefresher`. It runs after pending work replays, so also on the
  15-minute periodic run, at app start, on socket connect, on `catalog_changed`, and when a
  session snapshot hits an unknown product. `WorkspaceSessionResolver` uses it too, so a Session
  screen edit no longer re-downloads the catalog.
- Flutter: refreshes on `catalog_changed` and on reconnect, ignores stale overlapping answers, and
  normalizes search text once per loaded list rather than per keystroke.

**What protects open work**
- Only rows that differ are written, so an unchanged catalog doesn't touch the search index or
  re-emit session flows.
- Rows with unfinished offline work (create, update, delete, or an add that refers to them) are
  skipped and retried. The saved version is not recorded while anything is skipped.
- A product leaves the phone only if no session item, inventory row or unfinished operation uses
  it, and never because of an empty server list. Distributors are never removed.
- A saved version is ignored when the local catalog is empty, because a wipe by `CrashLoopGuard`
  would otherwise answer 304 forever.
- A distributor name held under another id no longer blocks refreshes.

**Changed from the plan:** skipping is per row rather than deferring the whole refresh while a
create is pending, because one stuck failed operation would otherwise block catalog updates forever.

**Verified:** backend suite, Android debug suite with new `CatalogRefresherTest`, Flutter suite
including the contract tests against a real local backend. Against a local backend with 144
products: unchanged check 304 and 0 bytes, full list 17,139 bytes plain and 1,799 gzipped.

**Not verified:** a real phone, including going offline during an import. Production until this
merges. Android needs a rebuilt app to get any of this.

**Known gap, not fixed here:** after an offline product create replays, the phone keeps its local
id instead of the server's (`SyncService`). A later server row with that id can still overwrite the
local row. Only admins can create products, so it should be rare. `ProductRepository.getAll()`, used
by the Catalog screen and the voice fallback, still downloads the full list; WP4 removes the fallback.

**Next**
- WP4 baseline on a real phone.
- Clickable prototype for smoother UI, which the owner wants after WP3. Found so far: Flutter uses
  default purple, not the brand colours, and "Ai vrut sa zici?" is missing a diacritic on Android.

---

## 2026-11-06 - Catalog at scale: admin read API and private prices (WP1 + WP2)

Production now holds 3,196 products and 67 distributors after a SAGA import through `/admin`.
The spec `docs/superpowers/specs/2026-11-06-admin-catalog-at-scale-design.md` was not found in
the repo or git history, so this work follows the owner's written priorities. WP0 is the owner's
own Render check, and no production data was changed.

**Changed**
- `GET /api/catalog/products` and `GET /api/catalog/products/{id}` no longer return `price`.
- New admin-only `GET /api/admin/catalog/products`: `q`, `distributor_id`, `limit` (up to 200),
  `offset`, returning `{items, total, limit, offset}` ordered by name then id.
- `/admin` reads through it with server-side search and paging.
- `PUT /api/catalog/products/{id}` only changes `price` when the request includes it. Before this,
  an edit from Android or Flutter, which never send it, erased that product's price.
- `TRACK-OWNERSHIP.md`: new T5 for catalog admin; T3 gains the product and distributor repositories.

**Migration note**
- Response shape change: `price` is gone from the two public `GET`s. Paths are unchanged and the
  list stays complete. Android has no catalog price, and Flutter's field is nullable and unread.
- Any integration reading `price` from those endpoints must move to the admin endpoint with an
  admin token.
- Prices were public until this deploys. Removing them can't recall copies already fetched.

**Verified**
- Backend suite, including new tests for every public response, admin access, paging, wildcards
  and price retention.
- `/admin` in a browser against a throwaway local database: paging, search, form edit keeping the
  price, a phone-style edit keeping it, emptying the last page, and an expired token returning
  to login.

**Not verified:** anything against production until this is merged and deployed.

**Next**
- After deploy: no-login requests show no `price`, and `/admin` pages through 3,196 products.
- WP3 (catalog reaches phones without opening Catalog), then WP4 (voice speed on a real phone).
- Still left out: a CSV without a `price` or `aliases` column nulls them on matched products. Fix
  this before the next import. `/admin` search doesn't match "paine" to "Pâine".

---

## 2026-11-06 - Linear issue sweep, integration and application review

Work was done on branch `claude/ex-dario-issues-features-b0e765`, then fast-forwarded into
local `development` (`8701773` to `05d767d`). It reached `origin/development` through PR 22, which
replaced PR 21, merged as `42f98b2` on 2026-11-06 with CI passing. Full review:
`APP-REVIEW-2026-11.md`.

**Production was down and is restored, but its data is gone.** `listmanager-api` was crashing at
startup because its database host, `dpg-d94buhtckfvc739n0p5g-a`, no longer resolved, and the
workspace had no database. Render's articles give free Postgres 30 days plus a 14-day grace
period, and it was created around 2026-07-04. Merging PR 22 made the Blueprint recreate
`listmanager-db` on `0.1c-256mb`, connect it and move the service to `0.5c-512mb`. The first
deploy failed and the plan-change deploy succeeded. Live checks after that: `/health` healthy,
new routes present, seed catalog of 48 distributors and 24 products with no duplicates.
Accounts, lists and any imported catalog from before are lost. The new database is
PostgreSQL 18, while CI tests on 17. Admin login works.

`android-native/training-data/voice/synthetic-v1.json` (117,546 lines) was later taken out of
the tree and ignored. It is generated, and the documented command in `VOICE-BENCHMARK.md`
reproduced it byte for byte before removal. It remains in older commits, starting at `eb37aa2`.

The `Co-Authored-By: Claude` lines were later removed from this PR's commits, at the owner's
request. That changed the hashes of the 34 commits below; code, authors and dates are unchanged.
Hashes cited elsewhere in this entry, in the other docs and in Linear comments are the old ones.

<details><summary>Old and new commit hashes</summary>

| Old | New | Commit |
| --- | --- | --- |
| `fb734a4` | `5ce9cc1` | docs: drop superseded deferred sync section |
| `2707be5` | `1fd1a98` | Merge branch 'feat/offline-session-lifecycle' into claude/ex-dario-iss |
| `c86fe0d` | `54afef6` | Merge branch 'fix/voice-listening-lifecycle' into claude/ex-dario-issu |
| `2f83d39` | `1de3045` | Merge branch 'feat/multi-product-dictation' into claude/ex-dario-issue |
| `e610a3a` | `e4d1377` | Merge branch 'feat/voice-synthetic-corpus' into claude/ex-dario-issues |
| `19dde18` | `489e50b` | sec: restrict CORS to configured origins |
| `8e330b8` | `0ddce60` | sec: add refresh token rotation and revocation |
| `2a1b3cf` | `d748b1b` | sec: upgrade vulnerable backend dependencies |
| `513b754` | `dbe2dd8` | sec: refresh Android login and stop logging secrets |
| `4c97cde` | `8dda2a8` | fix: make the offline outbox actually replay |
| `ff34a69` | `bbf942c` | fix: give held sync conflicts a way out |
| `7b4c065` | `68e17cf` | refactor: simplify SyncService and SyncDebugScreen |
| `85a12ea` | `2cf0c63` | refactor: split HomeScreen into focused composables |
| `cfe106c` | `bc153a4` | refactor: split CatalogScreen into focused composables |
| `e69d99f` | `730b2a6` | fix: never let sync scheduling crash app start |
| `19a5834` | `9399b81` | feat: show whether the last change is saved |
| `e6fe6c6` | `5d82c4c` | feat: make recording the one obvious action on Home |
| `b470618` | `1ab4727` | feat: add user profile fields |
| `4b32c62` | `62d3a36` | fix: understand spoken numbers above ten and in inventory lines |
| `58e0c6e` | `75a32bd` | fix: show suggestion confidence as a real percentage |
| `1204b66` | `cbd2f21` | test: grade N-best depth and phonetic weight on synthetic noise |
| `1748c22` | `1b9728a` | feat: add shared inventory API |
| `90e93d8` | `e532c1d` | feat: share the inventory between phones |
| `e2b63f2` | `b5f731e` | feat: connect the Flutter client to the real backend |
| `bb64bb6` | `b403015` | chore: add CI for all three projects |
| `8afab20` | `d19faf6` | fix: harden refresh, revocation and lock handling after review |
| `7f9c52d` | `4e9cf45` | fix: close sync dead ends found in review |
| `d4fa053` | `2f0db01` | docs: add application review and record this work |
| `05d767d` | `642fd37` | docs: record Linear reconciliation and a commit slip |
| `c5e6227` | `cb53ef7` | docs: record local merge and final Linear status |
| `5f44584` | `56d9f12` | test: scroll to the logout button before tapping it |
| `69bade6` | `d2fb8b5` | fix: raise Flutter's Android build tools to its minimums |
| `abceac3` | `a59df56` | docs: record Flutter test and build results and VS Code setup |
| `5dc70b2` | `6ccbe5e` | chore: stop tracking the generated voice dataset |

</details>

**Integrated** the finished local branches, with both histories kept: M7, EX-24, M5 and M6,
the voice stop fix, multi-product dictation and the synthetic corpus. M6 and M7 needed real
conflict resolution: mutations now check `is_active` under the row lock, and PUT replays are
answered before that check. M6's migration was re-parented onto M7's to keep one head.
The Talos EX-24 candidate (`workbench/5adfcd05-374`) was closed unmerged by the owner's decision. Talos was not running, so its branch and worktree remain.

**Fixed, found while working**
- Offline edits never replayed on their own. The only trigger, the drawer button, built an
  expedited WorkManager request with a battery constraint, which always throws, and the
  exception was swallowed. Replay now runs when work is queued or the socket connects.
- A 409 held an operation forever, blocking the whole queue and every refresh, with no way
  out except deleting all pending work on the debug screen. Users now choose (EX-25).
- `HttpLoggingInterceptor` at BODY level wrote passwords and tokens to logcat in every build.
  The WebSocket URL, which holds the token, was logged too.
- No screen showed save state: `SyncStatusBar` and its dialog were not used anywhere (EX-35).
- Tapping stop while the recognizer was mid-phrase was ignored.
- Suggestion badges printed raw scores such as `0.73%`.

**Issues implemented on this branch**
- Security: EX-45 refresh and revocation, EX-46 CORS, EX-47 dependency upgrade (43 advisories
  to 0; `python-jose` replaced by PyJWT). See `DEPENDENCY-AUDIT.md`.
- Sync: EX-25, EX-29 (`SyncService` 563 to 335 lines, `SyncDebugScreen` 607 to 221).
- UX: EX-33, EX-34, EX-35, EX-36, EX-37, with Compose UI tests running under Robolectric.
- Voice: EX-42 fixed (session 25/36 to 36/36, inventory 11/17 to 17/17 on a hand-written
  slice). EX-39 dropped and EX-40 kept at 5, on synthetic noise only. EX-38 and EX-41 are
  written up in `VOICE-BENCHMARK.md` but not run.
- EX-43, decided: finish. Flutter now uses the real API. Voice, offline queue and PDFs remain
  Android-only.
- EX-44: shared inventory, server and Android, tested with two simulated phones.
- CI added in `.github/workflows/ci.yml`. Its first run failed on Android because `gradlew` was
  committed as non-executable, which is fixed; it now passes.

**Compatibility and migration notes**
- Additive endpoints: `/api/auth/refresh`, `/logout`, `/logout-all`,
  `/api/admin/users/{id}/revoke-tokens`, `/api/inventory/*`. Additive fields on login and
  `/api/auth/me`. The route contract test now reads the OpenAPI schema.
- **Behaviour change:** a password change now ends every login, including the current one. An
  installed older client is signed out and must log in again. Clients without the new
  `ver` claim otherwise keep working until a user's first revocation.
- A request with no token still gets 403, kept deliberately; newer FastAPI would send 401.
- Alembic order: M7 `e6f7a8b9c013`, M6 `f7a8b9c0d124`, `a8b9c0d1e235`, `b9c0d1e2f346`,
  `c0d1e2f3a457`. Room goes to version 10, keeping inventory rows.
- Existing virtual environments must reinstall `requirements.txt`.

**Verification**
- CI at `a079749`, on Linux: backend on Python 3.11 349 passed, 0 skipped, `pip-audit` clean;
  Android tests and Preview build pass; Flutter passes. The backend run includes the 3
  PostgreSQL tests, which run every migration on a real, empty PostgreSQL server.
- Re-run on `05d767d` after the merge: backend 346 passed, 3 skipped; Android 265 Debug and
  252 Release with the tests forced to re-run, plus Preview assembly. No phone was attached.
- Backend: 346 passed, 3 skipped (the PostgreSQL gate), on the pinned dependencies; `pip-audit`
  finds no known vulnerabilities. A real server booted and ran the token flow.
- Android: 265 Debug and 252 Release unit tests, including 12 Compose UI tests that render
  under Robolectric, plus Preview assembly and instrumentation-code compilation.
- Voice tools: 14 passed at integration.
- Flutter: `flutter analyze` clean. On the plain Dart VM: API client 10/10, list controller
  17/17, live updates 4/4, and 5/5 contract tests against a real running backend.

**Not verified**
- Flutter widget tests were blocked for most of this work, because this machine refused
  `dartaotruntime.exe`. Later the same day they started running, with Smart App Control still
  on and no change we know of, so the block may return. `flutter test`: 51 passed, 5 skipped
  (the contract tests, which need a running backend and passed 5/5 on the plain Dart VM).
- The first real runs found two faults that the merged code carried. A widget test tapped a
  button below the fold (`5f44584`, test only). `flutter build apk --debug` failed because
  Gradle, AGP and Kotlin were below Flutter 3.47.9's minimums; they are now at those minimums
  and the debug APK builds. Kotlin 2.2.20 draws a warning that 2.3.20 will soon be required.
  CI runs analyze and test but no Flutter build, so it would not have caught this.
- No Flutter build has run on a device or emulator.
- The migrations against real production data: CI runs them only on an empty database.
- No real device was attached, so nothing here ran on a phone. Screens were rendered under
  Robolectric.

**Linear, updated with an evidence comment on each issue touched**
- All 43: 29 Done, 3 In Review, 1 In Progress, 10 Backlog, read back from the project page.
- Moved to Done after the merge, each against its own "Done when", with what stays unverified
  in its comment: EX-25, EX-29, EX-33, EX-34, EX-37, EX-39, EX-40, EX-42, EX-43, EX-45, EX-46,
  EX-47. Done here means in local `development`, not pushed or deployed.
- Moved to Done earlier: EX-5, EX-6, EX-7, EX-26, EX-27, EX-30, EX-31.
- EX-26 is Done against its stated criterion, a drop-and-replay test. Those tests call
  `SyncService` directly, so they did not catch the broken trigger. The fix, `4c97cde`, is now
  in local `development`, but `origin/development` still replays only from the debug screen.
- Merged but left In Review, because their criteria need the real world: EX-35 and EX-36 (a
  real user, EX-32), EX-44 (two real phones, EX-28).
- EX-24 is Done against its original criterion, after the owner chose to close the Talos candidate.
- Unchanged: EX-15 (In Progress), EX-8, EX-9, EX-16, EX-19, EX-21, EX-23, EX-28, EX-32, EX-38,
  EX-41 (Backlog). Each needs a person, a device, real data or a go-ahead.

**Commit slip:** the removal of the old Flutter demo screens and fake service landed in
`1748c22` (inventory API) instead of `e2b63f2` (Flutter), breaking the one-concern rule. The
removal itself was intended, but `flutter_ui` does not compile between those two commits, which
matters only when bisecting. History was left as it is.

**VS Code setup (machine-only, nothing in the repo)**
- `C:\Users\c0rnesky\GitHub\ListManager.code-workspace` holds the folders and build tasks. The
  user's VS Code settings point Flutter and integrated terminals at `android-native/.tooling`.
- Added the alpha `JetBrains.kotlin-server` extension, whose Android support is experimental.
  Compose previews need Android Studio, which is free. No emulator is installed.

**Next**
- Re-import the catalog, and rebuild the apps against the new backend.
- Point CI at PostgreSQL 18 to match production, and do EX-8's branch cleanup.
- Device gates: EX-9, EX-28 (second phone), EX-32 (real user), then EX-35 against its findings.
- Real data: EX-15 and EX-16 (shop crashes), EX-19 then EX-21 and EX-23 (real dictations),
  EX-38 and EX-41 (model, recorded audio).

**Open questions**
- Are per-distributor PDFs sent to suppliers, or used as pick lists? It shapes the top features.

---

## 2026-09-13 - Synthetic Romanian voice-pair pipeline

- Added a deterministic catalog-derived generator for Romanian product retrieval
  and reranking experiments. It reads only product IDs, names, and aliases from
  JSON or read-only SQLite and retains no audio, user text, sessions, or accounts.
- Versioned 2,500 positive text-product pairs with two hard negatives per pair,
  producing 7,500 rows. Templates cover quantities, natural commands, missing
  diacritics, digit quantities, and common ASR spacing variations.
- Splits are product-disjoint and negative mining prioritizes same-family and
  numeric package variants. The complete artifact is regenerated in tests so
  code/data drift fails the milestone gate.
- The checked-in artifact uses only the four-product benchmark fixture and is a
  pipeline sample, not training evidence. A catalog-only export with substantially
  more products is required before the first model experiment; real confirmed
  transcripts stay reserved for evaluation.
## 2026-09-13 - Natural multi-product dictation extraction

- Catalog-aware segmentation now treats punctuation and Romanian connectors
  such as `și`, `plus`, `apoi`, and `iar` as boundaries when they are not part
  of a matched catalog name.
- Conversational commands and count units are removed around products, so a
  phrase such as „aș vrea să adaug două baxuri de apă și trei bucăți de săpun”
  produces two additions with quantities 2 and 3 instead of unknown fragments.
- Matching windows support catalog names up to seven words, while a connector
  inside a real name such as „sare și piper” remains intact.
- Five regressions cover commands, quantities, connectors, punctuation, and
  long names. Final gates: 175 Debug and 175 Release tests passed without
  failures, errors, or skips; Preview assembly succeeded.
## 2026-09-13 - Voice stop lifecycle race fixed

- Each explicit recording start now owns a generation token. Final/error
  callbacks and delayed restarts from older recognizers are ignored after Stop
  or after a newer Start.
- Stop cancels and destroys the recognizer, clears scheduled retries, and moves
  immediately to Idle. Continuous mode still restarts between utterances only
  while the same user-started session remains active.
- Three state-machine regressions cover stop invalidation, stale callbacks after
  a new start, and fatal callbacks scoped to their own session.
- Final gates: 173 Debug tests and 173 Release tests passed with no failures,
  errors, or skips; Preview assembly succeeded.
## 2026-09-13 - M6 offline session lifecycle complete

- Offline sessions now receive a stable client UUID and a durable create intent.
  Item edits stay in FIFO order behind creation, and reconnect atomically maps
  the negative Room id to the server id before replay continues.
- Completion is durable and idempotent. It waits behind outstanding item writes,
  survives restart, and cannot be undone by workspace resolution. Completed
  sessions reject add, update, delete, and clear; corrections use a new session.
- Room schema 9 adds the nullable unique session identity. Migration retention,
  resolver deferral, restart/reconnect promotion, two-item replay, completion
  ordering, and lost create-response recovery have regression coverage.
- Final gates: 256 backend tests, 177 Android Debug tests, and 177 Android Release
  tests passed without failures, errors, or skips; Preview assembly succeeded.
  Installing Preview over the existing S25 data preserved the app and launched
  without Room, SQLite, or runtime failures.
- Integration note: M6 is stacked on M5. After the dedicated M7 branch is merged,
  rebase M6 and point its Alembic revision at M7's revision before opening its PR.

---

## 2026-09-13 - M5 account/workspace isolation complete

- Room schema 8 adds an account owner to cached sessions. Personal sessions are
  selected by account id; team sessions remain intentionally shared by team id.
- The authenticated user id is restored with the encrypted auth state. A new
  login clears the previous id until `/auth/me` confirms the new identity, and
  the session UI waits instead of opening a stale cache in that interval.
- New outbox entries always record their author. Replay, pending/failed UI,
  snapshot protection, and acknowledgement rebasing operate only on the current
  account; unattributed legacy operations remain stored but quarantined.
- Three native SQLite tests cover migration retention, personal/team cache
  visibility, and A/B replay isolation with A able to resume later.
- Final gates: 173 Debug tests and 173 Release tests passed with no failures or
  skips; Preview assembly succeeded. An in-place S25 Preview upgrade preserved
  its database and cold-launched without Room, SQLite, or runtime errors.
- Next: M6 offline session promotion, durable completion, and immutable
  completed-list behavior.
## 2026-09-13 - EX-24 randomized sync interleavings

- Added a deterministic randomized convergence matrix for eight reproducible
  seeds. Each case interleaves 36 operations from two team members across three
  independently refreshed replicas.
- The generated histories cover additions, absolute quantity changes, deletes,
  clears, re-adds, and duplicate delivery of idempotent add/update requests.
  Replica pulls occur at unrelated points through the public `?since=` op-log.
- Every run asserts contiguous operation sequence numbers, exactly one log entry
  per logical mutation, identical final views for both members, and equality
  between every folded replica and the authoritative session snapshot.
- Focused gate: 9 convergence tests passed. Full backend gate: 261 tests passed,
  zero failures/errors, in 121.82 seconds. No API, schema, dependency, or runtime
  behavior changed; EX-24 is test instrumentation for the existing contract.
- Next: EX-25 should exercise real conflicting writers and repeated 409/reconcile
  cycles. M7 must then verify sequence allocation under concurrent PostgreSQL
  transactions, which this sequential TestClient matrix deliberately does not claim.
## 2026-09-13 - M7 real PostgreSQL concurrency complete

- Session item add, update, delete, clear, and completion transactions now lock
  the owning session row before reading mutable state or checking idempotency.
- Replaced `MAX(seq) + 1` with an atomic per-session sequence reservation and
  added a database unique constraint on `(session_id, seq)`.
- The Alembic migration initializes counters from existing op history and stops
  with a diagnostic if duplicate historical sequences would make the new
  invariant unsafe.
- A real PostgreSQL 17.11 gate with separate connections passed concurrent
  increments, duplicate delivery of one idempotency key, competing versioned
  updates (one 200 and one 409), and contiguous unique operation sequences.
- Full backend gate: 255 passed, 218 warnings, zero failures/skips in 91.58s.
- Next: M5 account/workspace isolation, followed by M6 offline session creation
  and completion lifecycle.

---

## 2026-09-13 - M0 S25 session restore and PDF acceptance passed

- A clean Preview reinstall exposed a foreign-key ordering defect: the server
  returned the active session and its item, but Room rejected that item before
  its product and distributor parents had been cached. The session screen
  therefore appeared empty even though the server held `Lapte Zuzu 1L` ×2.
- Workspace resolution now refreshes distributors and products atomically before
  applying the session snapshot. If either catalog request fails, it preserves
  the last complete local snapshot instead of replacing it with partial data.
- Two native Room regressions cover first-install hydration and preservation on
  catalog failure. Both failed before the correction and pass afterwards. The
  full gate passes 170 Debug tests, 170 Release tests, and Preview assembly.
- On the Samsung S25, the corrected clean install displayed the existing METRO
  item with quantity 2. The drawer scrolled to Teams, Generated PDFs, Settings,
  and About.
- Completing the session generated one METRO PDF with the expected distributor,
  product, quantity 2, total 2, and page 1 of 1. Text extraction and a 160 DPI
  rendered-page inspection found no clipping or overlap. The backend marked
  session 1 complete and created empty active session 2.
- M0 remains open for the second-phone and GitHub publication gates.
- The fresh APK passes all eight Python tool tests, the eight-library ELF 16 KB
  check, and `zipalign -P 16`; SHA-256 is
  `52847B3FF9EE6B342E72348CBAE85350031965067546A3FA7F919C359CDDF35B`.

---

## 2026-09-13 - M0 Android 16 KB native-library gate passed

- Upgraded `sentence-embeddings` from `0.0.6` to the NDK-r28-built `v6` artifact
  and forced its ONNX Runtime dependency from `1.17.0` to `1.24.3`.
- Added a platform-independent APK/ELF gate for every `arm64-v8a` and `x86_64`
  native library. Four tests cover aligned, unaligned, legacy-ABI, and malformed
  inputs; all eight Python tool tests pass.
- The fresh Preview APK passes the new gate for all eight relevant native
  libraries and Android Build Tools `zipalign -P 16`. Before the upgrade, direct
  inspection found 12 unaligned libraries across the packaged ABIs.
- Android gates pass: 168 Debug tests, 168 Release tests, and Preview assembly.
  The upgraded APK installs over Preview without deleting its data and starts
  cold on the S25 without a native-link or process crash.
- Android instrumentation now targets the isolated Preview build. Its focused
  ONNX smoke test loaded `OrtEnvironment` through JNI on the S25 (1/1 passed).
- The S25 reports a 4096-byte kernel page size, so it cannot replace a real
  16 KB device/emulator execution. Startup also confirms the separately known
  missing `model.onnx` asset; semantic matching still falls back to fuzzy-only.

---

## 2026-09-13 - M0 S25 voice quantity acceptance passed

- The S25 recognizer returned "două lapte Zuzu". The resolver matched both
  segments to the same catalog product and merged them before persistence.
- Exactly one AutoAdd and one POST occurred. The request carried product ID 1
  and quantity 2, returned 200, and the session screen displayed one
  `Lapte Zuzu 1L` row with quantity 2.
- Existing review/unknown counters stayed at one each; this run did not create
  another fallback item. The real transcript is retained text-only in the T2
  corpus and no audio was recorded.
- M0 stays open for drawer, completion/PDF, second-phone, and publication.

---

## 2026-09-13 - T2 controlled real-transcript corpus seeding

- Added a local corpus appender that requires explicit text-only confirmation,
  a `real-` case ID, an existing product ID, and a supported broad noise label.
  It performs an atomic JSON replacement and has no audio input.
- Added the deliberately dictated S25 transcript "două lapte Zuzu" as the first
  real case, labelled `unspecified` because the acoustic condition was not recorded.
- The mixed corpus now contains five synthetic smoke cases and one real case.
  Current harness result remains top-1 1.0000, top-3 1.0000, MRR 1.0000; the
  corpus is still too small for an accuracy target or weight tuning.
- Gates: 4 collector tests and all 168 Android Debug tests passed with zero
  failures/errors; Preview APK assembly succeeded. The benchmark's 2 tests are
  included in the Android total.

---

## 2026-09-13 - T2 text-only voice diagnostics policy

- Chose operator-confirmed, text-only benchmark collection for EX-19; audio is
  never retained and collection is limited to deliberate debug/Preview runs.
- Release logging now emits only structural voice diagnostics. Dictated text,
  N-best hypotheses, segments, unknown/review phrases, and search queries are
  available only in debuggable local builds.
- Added a lazy redaction policy so release logging does not even evaluate the
  sensitive message supplier, with unit coverage for both modes.
- Gates: 168 Debug tests and 3 Release privacy tests passed with zero
  failures/errors; Preview APK assembly succeeded.

---

## 2026-09-13 - S25 authentication and active-session verification

- Authenticated the Preview app against the isolated USB-forwarded backend and
  verified `/api/auth/login` and `/api/auth/me` return 200 for the local test user.
- Opened the personal active session on the Samsung S25. Both
  `/api/session/active` and `/api/session/1/items` return 200; the active session
  exists and currently contains no server items.
- Corrected the sign-in form so email is shown and required only for account
  creation. Sign-in now reflects the backend contract: username and password.
- Added unit coverage for login and registration submission rules. Full Android
  gate: 165 tests, zero failures/errors, and Preview APK assembly successful.

---

## 2026-09-13 - T1 real-device crash recovery and retry idempotency

- Added a crash trigger compiled only into the side-by-side Preview build and
  covered it with a Robolectric test. The Preview gate passed 157 tests before
  commit `487648e`.
- Ran the full crash-loop path on a Samsung S25 with Android 16. Three rapid
  crashes reset the streak, recreated the Room database, uploaded the pending
  reports, and grouped them under one signature.
- The first device run exposed duplicate ingestion under at-least-once delivery:
  three crash files created four rows. Added optional client event IDs, a unique
  indexed server column in Alembic revision `d5e6f7a8b902`, and conflict-safe
  idempotent ingestion. Backend gate: 253 tests; commit `c16415c`.
- Android now names each crash with a UUID and reuses that filename-derived ID
  for every upload attempt. Android gate: 157 tests plus Preview assembly;
  commit `dd66807`.
- The repeated S25 test passed exactly: 3 reports, 3 event IDs, 1 signature,
  streak 0, a new database inode, and an empty pending queue. Controlled data
  is excluded from the real-shop top-signature and seven-day gates, so EX-15
  and EX-16 remain open.

---

## 2026-09-12 - T2 benchmark foundation

- Added a versioned JSON voice corpus with product fixtures and labelled cases
  carrying utterance, expected product ID, locale, noise condition, and privacy
  mode. Strict validation rejects schema drift and invalid references.
- Added a reusable ProductRanker evaluator for top-1, top-3, and MRR plus a
  17-candidate one-at-a-time sweep covering all eight `ScoringWeights` fields.
  Sweep results rank by top-1, MRR, then top-3.
- The five synthetic smoke cases report top-1 1.0000, top-3 1.0000, MRR 1.0000.
  These values validate the harness and are explicitly not a representative
  baseline or a production tuning signal.
- Android full gates passed before each commit: 154 tests for `0dae865` (corpus,
  loader, metrics) and 156 tests for `c7fc250` (weight sweep), with zero failures
  or errors. Linear EX-18, EX-20, and EX-22 are Done.
- EX-19 remains blocked on the corpus privacy policy. EX-21 and EX-23 depend on
  a meaningful real corpus and remain open. See `VOICE-BENCHMARK.md`.

---

## 2026-09-12 - T1 crash instrumentation and recovery gate

- Added stable server-generated crash signatures from exception type and the
  top eight normalized Android frames. Alembic revision `c4d5e6f7a801` adds the
  indexed nullable field and backfills old reports with the same algorithm.
- Added the admin-only `/api/admin/crashes/groups` endpoint and grouped admin
  view. Groups expose count, first/last seen, affected versions/devices, recent
  instances, affected-user estimate, and `count × affected users` impact score.
- Added backend coverage for signature stability/separation, authorization,
  grouping, impact ordering, migration drift/round-trip/backfill, admin page,
  and the additive route contract. Final backend gate: 251 passed, zero
  failures/errors, 200 warnings, 90.78 seconds; admin JavaScript parses.
- Added a Robolectric forced-crash test that drives three rapid startups and
  crashes through the real `CrashLoopGuard`, then proves the Room database is
  deleted and the streak resets. Final Android gate: 152 tests, zero failures
  or errors. Native Room tests required an unsandboxed runner because Windows
  blocked Robolectric's native DLL only inside the sandbox.
- Commits: `6b3125b` crash grouping and UI, `91103d4` crash-loop recovery test,
  and `a65b9ab` impact ranking. Linear EX-12, EX-13, EX-14, and EX-17 are Done.
- No crash reports exist in either local backend database, either S25 app's
  pending directory, or the S25 crash buffer. Production was not inspected
  because this checkout has no admin credentials. See `T1-CRASH-TRIAGE.md`.
  EX-15, EX-16, and the required seven-day real-shop observation remain open.

---

## 2026-09-12 - Linear M1: Cut the seams

- Split the former 1,464-line FastAPI module into domain routers for auth,
  catalog, sessions, admin, crashes, presence, speech, and stats; the existing
  teams router remains part of the composition. `app/main.py` is now 159 lines
  and contains app construction, middleware/limiter setup, startup work, router
  registration, and the WebSocket endpoint.
- Added an exact public-route contract covering 47 HTTP method/path pairs and
  `/ws`. Added an architecture guard requiring the domain router modules and a
  sub-200-line composition root with no HTTP route decorators.
- Added `docs/TRACK-OWNERSHIP.md` for EX-11. It assigns exclusive paths to T1
  crash work, T2 voice matching, T3 sync, and T4 experience work, and records
  the shared-file, append-only schema/entity, Alembic-slot, worktree,
  `ApiConfig.kt`, and path-compatibility rules.
- Backend gates passed after extraction and again at the final M1 boundary:
  245 tests, zero failures/errors, 196 warnings, 96.04 seconds. Route extraction
  is `7e33f3b`; route contract is `1537ebd`; architecture guard is `b63c9a2`.
- Linear M1 implementation is complete locally. M0 remains open on its separate
  real-device, Android 16 native-library, and remote-publication gates.

---

## 2026-09-12 - M0 spoken quantity and segmentation corrections

- The corrected catalog build recognized "două lapte Zuzu", but segmented it
  into "două lapte" plus "Zuzu". That created one review item and auto-added
  the same product with quantity 1.
- Spoken quantities are now parsed conservatively from a phrase prefix and used
  by both automatic additions and confirmed review suggestions (c7e8a49).
- Quantity prefixes now stay attached to the following product segment, including
  multi-product phrases such as "două lapte trei ouă" (ef8b3cf).
- New quantity and segmentation tests failed before their fixes. Full preview
  gate now passes: 151 tests, zero failures/errors, and APK assembly succeeds.
- Current local candidate: `m0-candidate-2026-09-12.4`; APK SHA-256
  `F3D304FD0D8949C14F335BB60AEFBF6C89DEAB5E0B5CB873710EFD11BDDC36B8`.
  It is installed on the S25. Clean voice/quantity retest awaits phone unlock.
- The isolated server session contains no items. No cleanup deletion was applied.
  Completion/PDF, restart persistence, GitHub publication, and the Android 16
  native 16 KB compatibility warning remain open.

---

## 2026-09-11 - M0 catalog regression correction

- Real S25 dictation correctly transcribed the milk request but returned Unknown.
  Products fetched successfully, then Room rejected them because distributor
  parents were absent on first use.
- Product refresh now fetches both catalog halves and upserts parents before
  products in one transaction. Distributor refresh no longer clears parents.
- Four native Room regressions failed before the product fix. Full preview
  suite passed afterwards: 145 tests, zero failures/errors, APK build successful.
  Tests cover first-run search, preserved session/local rows, parent-fetch failure,
  and transaction rollback for invalid parent references.
- Commit bf9736b; local tag m0-candidate-2026-09-11.2. APK SHA-256:
  CD48CAE8C5833423B2E628B26281B09930E26E87F69774B91C168CFE6B3677A2.
- No schema/API changes. Server-removed rows stay cached until explicit deletion
  or reconciliation. Drawer bottom entries are reachable at S25 display settings.
- Device voice retest, completion/PDF, and native 16 KB warning remain open.

---

## 2026-09-11 — M0 first real-device run

- Samsung S25 SM-S931B connected and authorized, Android 16 / API 36.
- Installed the tested tagged preview alongside the daily app. Started a separate
  loopback-only local backend/database and forwarded port 8000 over USB.
- Backend health and actual preview login pass; main recording screen renders.
- Device revealed a startup 16 KB compatibility warning for ONNX/tokenizer native
  libraries and graphics-path. Not fixed or suppressed; M0 remains open.
- User requested to perform real dictation. Match, drawer, restart persistence,
  completion/PDF and remote publication remain pending; no full acceptance claim.

---

## 2026-09-11 — M0 trunk integration candidate

- Development now includes both admin branch histories at 7a5edc7; local tag
  m0-candidate-2026-09-11 identifies the device candidate. Fresh preview build
  and all 141 preview unit tests pass (zero failures/errors).
- User connected the S25, but ADB and Windows device detection still show no
  Samsung/Android device. Preview APK is ready; installation and real-device
  acceptance are pending. USB localhost endpoint is configured only locally.
- GitHub publication is blocked by missing Git credentials; the device login
  attempt returned no code and was stopped. No remote cleanup or tag push occurred.
- Reapplied admin CRUD commits 733b807, 45c46cc, 4f0a1d6 onto the reliability
  branch; resolved the progress-log conflict by retaining both histories.
- Full backend suite: 241 passed (197 warnings, 313.61s). Android test/build
  gate succeeded using unchanged cached 141-test inputs. Admin JS syntax passes.
- Verified local Git history backup before branch cleanup. N-best and drawer
  fixes already belong to development; reliability M1–M4 stay included.
- Device gate remains pending: user identified Samsung S25, Android version
  unknown; USB debugging connection is not detected. Local USB testing is provisional.
  See `M0-ONE-TRUNK.md` for the exact acceptance script. M0 is not yet done.

---

## 2026-09-11 — Linear implementation audit

- Compared all 43 ex-dario List-Manager App issues with the current local code.
  Report: `LINEAR-IMPLEMENTATION-AUDIT-2026-09-11.md`.
- Six issues have local implementation evidence: EX-5, EX-6, EX-26, EX-27,
  EX-30 and EX-31. All 43 issues still read Backlog in Linear. No statuses changed.
- Fresh targeted backend verification: 54 tests passed plus 3 stats tests.
  Existing last full gates remain 240 backend / 141 Android plus debug build.
- Project is incomplete: original M0/M1 exit gates, crash grouping/field proof,
  voice benchmark, remaining sync convergence/device checks, UX/profile and
  parked roadmap work are not all done. Local reliability M1–M4 are a subset.
- No device/production rollout or visual user-study claim. Stored branch refs
  only were inspected. Next: reconcile the report with the original roadmap.

---

## 2026-09-11 — M4: recover interrupted work and protect pending edits

- Full Android gate: **141 passed**, zero failures/errors/skips; debug build
  succeeded in 65s. 21 new tests. Initial recovery tests failed twice; both
  catalog-refresh regression tests also failed before the fixes.
- Worker processes unfinished operations in insertion order, observes retry
  backoff, and recovers interrupted keyed requests under the shared process lock.
  Immediate work uses KEEP instead of canceling a possibly committed request.
- Permanent rejection, malformed saved data, conflicts and ambiguous clears are
  retained for review. Temporary failures/expired authentication retry; later
  edits cannot overtake unresolved work. Review items remain visible in the dialog.
- WAITING identifies operations that have never been sent. Only these dependent
  updates/deletes can have IDs/versions rebased after an acknowledgement; old
  possibly-sent payloads remain immutable. Acknowledgement and rebase are atomic.
- Snapshots and socket-triggered refreshes share the edit lock and defer while
  session work is unresolved. Null/invalid snapshots preserve cached rows.
  Catalog upserts update parents in place rather than cascade-deleting children.
- No Room schema change or production dependency change. Native SQLite/Room
  tests cover competing workers, cancellation, backoff, replay, dependent edits,
  snapshot races, rollback, rejected operations and catalog refresh. No real
  device instrumentation or two-phone test was run.
- Tradeoffs: queue ordering and snapshot deferral are conservative across all
  session work. Conflicts require review; automatic conflict convergence and
  a dedicated resolution UI remain follow-up work. Legacy ambiguous non-keyed
  requests are held rather than blindly retried.
- Linear is now accessible in ex-dario / List-Manager App. Our reliability M1–M4
  map into T3, not the unrelated Linear M1 router-splitting milestone. No issue
  status changes were applied: automatic review rejected EX-26/EX-27 mutations.
- Next: M5 account/workspace isolation. M6 still needs completed-list policy;
  inventory is currently device-local per Linear EX-44, shared sync is v2.

---

## 2026-09-11 — M3: durable Android item operation identity

- Adds and quantity updates now save a stable UUID and the local edit in one
  Room transaction before sending. Initial sends and background replay use
  the same request builder and key; legacy queued JSON gains a persisted key
  before replay. This cannot deduplicate legacy requests already sent without keys.
- Server acknowledgements and the local server mirror commit together. Network
  errors, cancellation, empty responses and HTTP failures retain the initial
  operation. Repository instances and replay share a process-level write lock.
- Added 12 tests covering serialization, persistence before send, cancellation,
  distinct identities, lost responses, legacy JSON, HTTP failure, real Room
  rollback and database close/reopen. Initial regressions reproduced two failures.
- Added test-only Robolectric 4.14.1, pinned for native SQLite/Room verification.
  Tests target Android 35: the Android 34 compatibility runtime failed native
  SQLite linkage on this Windows host. No production dependency/schema change.
- Final gate: **120 tests passed**, debug build passed (32s), diff check passed.
  No device instrumentation was run; the M3 persistence
  gate explicitly uses actual generated Room DAOs and native SQLite locally.
- SDK license accepted by user; SDK/JDK and local API configuration are ignored.
  M2 was committed as `e54800b`. No push or deployment.
- Next: M4 worker recovery, ordering/conflicts and snapshot reconciliation.
  Interrupted IN_PROGRESS operations and destructive refresh remain unresolved;
  M3 establishes durable identity, not complete offline synchronization.
- Open product questions remain: completed-list editing and inventory ownership.
  Linear comparison still needs access to the project.

---

## 2026-09-07 — M2: session input and arithmetic limits

- Added 13 regression cases in `tests/test_session_validation.py`; the pre-fix
  run reproduced 12 failures. Full post-fix gate: **240 passed**, 195 warnings,
  137.87s, using the isolated SQLite application database.
- Add quantities must be 1..2147483647; PUT quantities retain zero compatibility
  and must be 0..2147483647. Versions must be 0..2147483647 and operation keys
  at most 64 characters. Existing response fields and DB schema are unchanged.
- Reject increments that overflow the stored total before mutating the item or
  consuming its idempotency key; reject exhausted version counters with 409.
- Compatibility note: negative/overflow quantities, zero additions, invalid
  versions, and overlong keys now return 422. Android already removes items via
  DELETE and sends positive additions. Zero PUT semantics remain unchanged.
  No historical rows are removed or rewritten by this change.
- M1 is committed as `72d70df`; M2 will be committed only after the staged diff
  check. M3 still needs a working Android test/build environment.
- Prepared a checksum-verified JDK in ignored local tooling; SDK license
  acceptance is pending. Nothing has been pushed or deployed.

---

## Milestone implementation — M1

- Branch: `codex/reliability-milestones`.
- Added `docs/IMPLEMENTATION-MILESTONES.md` with explicit regression, full-suite,
  build, and migration gates for each milestone. Missing tools block commits.
- M1 session integrity changes passed the full backend suite again immediately
  before committing: **227 passed**, 176 warnings, 149.01s; diff check clean.
- Next: M2 quantity/key validation; Android milestones require Java/SDK setup.

---

## 2026-09-05 — Current-code audit and session integrity fixes

- Added `docs/ISSUES-2026-09-05.md`: 12 prioritized findings with evidence,
  implementation steps, acceptance criteria, and explicit verification limits.
  Linear comparison is pending project URL/authentication; no tickets were changed.
- Reject nonexistent products before writing session items or sync records.
- Return the enriched item DTO consistently on initial writes and retries, and
  preserve `item_uuid` on snapshot and completion responses.
- Acknowledge successful add retries after completion, while still authorizing
  the caller first and rejecting new mutations to inactive sessions.
- Added 4 regression tests: 3 reproduced failures before the fixes; full backend
  suite now **227 passed** (SQLite, Python 3.12; 176 existing/dependency warnings).
  Direct dependency versions and database schema were unchanged. Tests used an
  isolated in-memory application database and temporary migration databases.
- Android tests cannot start without Java/JAVA_HOME; Android findings remain
  implementation proposals. No commit, push, deployment, or Linear mutation.

Next: obtain Linear access, reconcile duplicates, and implement the Android
outbox/account-isolation work with Android test tooling. Open product questions:
completed-list editing policy and device-shared vs. account/store-owned inventory.

---

## 2026-07-14 — Nav drawer wasn't scrollable (fix/drawer-scroll)

Reported: the navigation drawer (Home / Current Session / …) didn't move up and down — static.

**Root cause**

`DrawerContent`'s `ModalDrawerSheet` laid its children in a plain ColumnScope with **no**
`Modifier.verticalScroll`, and used `Spacer(Modifier.weight(1f))` to pin Settings/About to
the bottom. A weight spacer forces the column to exactly the sheet height and is incompatible
with scrolling, so once the content (workspace switcher + connection status + the variable
"Online acum" presence list + 7 nav items) exceeded the screen, the bottom got clipped with
no way to scroll.

**Fix**

Standard "scrollable body + pinned footer": an outer `Column(fillMaxHeight)`, a
`Column(weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))` holding the header
through the primary nav items, and Settings/About pinned **outside** the scroll (replacing the
old weight spacer). Matches the app's existing verticalScroll idiom (AccountScreen /
LoginScreen). The weight+verticalScroll nesting is correct because the sheet content scope is
height-bounded (Surface `fillMaxHeight`) — the crash case is the reverse (a weight child
*inside* a scroll). Verified: builds; whitespace-only-vs-content diff confirms nothing else
changed; adversarial Compose review (high confidence, no blockers).

**Same bug elsewhere (found by a sweep — NOT fixed here)**

The identical `Column + Spacer(weight(1f)) footer + no scroll` idiom clips on small screens /
long content in `HomeScreen` (data-driven content) and `EditProductScreen` (Save button pushed
under the soft keyboard); non-scrolling forms `EditProductBottomSheet` and `SettingsScreen` are
latent. Aside: `AccountScreen` has a `weight(1f)` *inside* its verticalScroll column — a no-op
that doesn't actually pin the logout button. Each deserves its own fix + verification.

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
*Resolved 2026-07-08 — see the fix/crash-loop-stale-streak entry above.*

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
## 2026-07-05 — Admin catalog CRUD on the page (feat/admin-catalog-crud)

Follow-on to the CSV import page: full CRUD for products + distributors directly in the
`/admin` web page, so one-off edits don't require editing and re-uploading a CSV. Front-end
work in `app/static/admin.html` calling the existing admin-gated catalog endpoints — plus one
backend bug fix surfaced by the review.

**What changed**

- **Distributors card:** add, rename (`prompt`), delete (`confirm`) — wired to
  `POST/PUT/DELETE /api/catalog/distributors`.
- **Products:** an add/edit form (name, distributor `<select>`, aliases, price) + per-row
  Edit/Delete — wired to `POST/PUT/DELETE /api/catalog/products`. Create/update toggled via
  `editingProductId`; `price` sent as number-or-null.
- **XSS-safe throughout:** all new DOM built with `createElement`/`textContent`/`addEventListener`;
  no `innerHTML` with catalog data. Mutations go through one `apiMutate()` helper (401/403 →
  re-login, errors surfaced in a `#status` line).
- **Backend fix:** `update_product` (`app/main.py`) never assigned `price` — editing a product
  silently dropped the price. Added `product.price = product_update.price` + a regression test
  (`test_update_product_persists_price`). `create_product` was unaffected (uses `model_dump()`).
- **Honest delete copy:** deleting a distributor CASCADES (deletes all its products); the confirm
  dialog now says so explicitly.

**Tests:** `test_product_price.py` (4), `test_admin_page.py` (1); full suite green.

**What's next**

- Ships as a follow-on PR stacked on the CSV-import PR; merge that first.
- Deferred: a duplicate-in-file CSV warning; `price` on the Android phone/PDF.

**Open questions**

- Distributor delete cascade is intentional backend behavior; revisit only if accidental
  mass-deletes become a concern (could add a "distributor not empty" guard server-side).

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
