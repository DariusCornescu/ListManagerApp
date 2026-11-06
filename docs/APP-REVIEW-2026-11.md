# List Manager application review, November 2026

List Manager does its core job well on paper: dictate items in Romanian, match them to a catalog, and turn the list into one PDF per distributor. The engineering under it is careful, especially session sync. The gaps now are at the two ends of the workflow: trusting what voice added, and what happens to the list after the PDF.

The most urgent item was not in the code. Production was down because its free database had been deleted, and everything in it is lost. Merging PR 22 restored the service on paid plans (2026-11-06), but the repo still has no backup job.

Everything here was checked against the code at commit `7f9c52d`. It comes from reading and from automated tests. No real phone, shop or user was involved, and the production system was not inspected.

## Open findings, most urgent first

These were found by review and checked against the current code. Defects in code written during this work were fixed rather than listed.

| Severity | Finding | Evidence | Suggested fix |
| --- | --- | --- | --- |
| Critical | No backups, after production data was lost | The free database was deleted, which took production down and lost its data. The service is restored on paid plans, but no backup job exists. | Confirm the paid plan's backup retention, and schedule `pg_dump` if it is too short. |
| High | Production logs are switched off | `alembic/env.py:23` calls `fileConfig` during import, which disables existing loggers, including uvicorn's and the app's. The startup line was missing from a real server log. 500 errors leave no traceback, which also blocks crash work. | Pass `disable_existing_loggers=False`, configure root logging, and redact `token=` first, since the socket URL holds the token. |
| High | A PDF can miss items, then close the list | `SessionViewModel:129-150` neither sends queued edits nor pulls first, and does not warn about items waiting in review or unknown. A teammate's unseen item is left out of the order. With anything queued, completion waits and `refresh()` can reactivate the old list. | Sync first, then show a pre-PDF check. See feature 1. |
| High | Wrong products can be added without asking | `ProductRanker:110,132`: an exact alias scores 1.36 and an exact name 1.07, with no margin check before auto-add. "lapte capra" can become "Lapte" plus an unknown "capra". Users see only a count, with no name or undo. | Normalize the scales, require a gap to second place, and show what was added. See feature 2. |
| High | Crash recovery can delete unsent work | `CrashLoopGuard:89` wipes the database, including the outbox, unsent inventory, unknowns and review items, which exist nowhere else. `allowBackup` with empty rules (`AndroidManifest:22`) may restore encrypted prefs without their key, which could itself cause the crash loop (not run). | Keep those tables through recovery. Exclude `sync_prefs` from backup and catch the failure. |
| High | The microphone keeps listening | Home and Inventory have no lifecycle stop, so dictation can keep adding overheard speech after leaving the screen. Offline recognizer errors retry every 300 ms while showing Listening (`AndroidSpeechProvider:126`). Background behaviour on a real phone is untested. | Stop on `ON_STOP`, and surface repeated failures. |
| High | Uploads have no size limit before reading | `routers/admin.py:41` and `routers/speech.py:41` read the whole file before checking its size, and no body limit exists. On a 512 MB instance a large request can exhaust memory. | Add a body-size middleware and check the size first. |
| High | A CSV import can erase data | `services/catalog_import.py:103`: a file without a price or aliases column nulls them on every matched product, degrading voice matching. "1,234" becomes 1.23. The dry run shows only a count. | Only overwrite columns present in the file, and show changes in the dry run. |
| High | One phone can retire a team's live list | `routers/sessions.py:91` deactivates other team sessions on create. A phone with no cached active team session dictates, and its offline create retires the list a teammate is using, with no PDF. | On create, return the team's active session instead of replacing it. |
| Medium | Deletes return 500 on real data | Distributor, product, team and user deletes hit foreign keys on PostgreSQL (`catalog.py:122,249`, `teams.py:117`, `admin.py:201`). The "CASCADE" comment at `catalog.py:121` is false. | Choose a policy per entity (soft delete or block) and answer 409. |
| Medium | Presence shows every online user to anyone | Registration is open, and `websocket_manager.py:40` and `presence.py` give any account the name and id of everyone online. | Scope presence to teammates, and decide whether registration stays open. |
| Medium | A stolen token can take over the account | `PUT /api/auth/me` changes email and password without the current password, and catalog reads with prices need no login at all. | Require the current password. Decide whether prices are public. |
| Medium | Database work blocks the event loop | Writes are `async def` over a synchronous session, so a CSV import stalls every socket and `/health`. Lock timeouts now limit the worst case, but the design stays fragile. | Run the database work in a thread pool. |
| Medium | First login can file work under the wrong account | User id is null until `/auth/me` returns (`AuthViewModel:126`), so edits made in that window go to guest scope and are never sent. | Hold writes until identity is known. |
| Medium | Live updates are fragile | The socket reconnects only on some screens (`WebSocketService`), a `StateFlow` conflates bursts and replays the last event on rotation, and an old listener can mark a new socket disconnected. | Use a `SharedFlow`, reconnect with backoff, and pull after reconnecting. |
| Medium | PDF layout | Row 20 overprints the footer (`PdfRepository:29,218`), long names truncate beside an empty Size column, a `/` in a distributor name fails generation, and orders are in English. | Compute from the real position, sanitize file names, drop Size, translate. |
| Medium | Text matching misfires | `TextNorm:36` turns "paine pe gratar" into "painexgratar", `:40` turns "2 100 cuie" into a 2x100 size, and the FTS tokenizer can miss "Pâine" when queried without diacritics. Ranking runs on the main thread. | Anchor the rules to digits, use `unicode61`, and move ranking off Main. |
| Medium | The debug screen can delete everyone's work | Anyone can reach Settings, Sync Debug, "Clear All Pending Operations", with no confirmation, and it covers every account (`SyncDebugScreen:184`). | Hide it in release builds, or scope and confirm it. |
| Medium | Build and release setup | `minSdk` 34 excludes older shop phones with no visible need, R8 is off, and no release signing is configured. There was no CI until this branch. | Lower `minSdk` after checking, then enable R8 with keep rules. |
| Low | Smaller items | Crash ingest is anonymous with no retention (about 144 MB/day per IP). Rate limits are in memory and shared across one shop's IP. `init_db.py:18` defaults to `admin123`. Test packages install in production. Unused: `SyncStatusBar`, `ErrorCard`, `AppCard`, `PDFCard`. Strings are hard-coded and languages are mixed. | Clean up as these areas are touched. |

## What is done well

- Session sync: durable operation ids, an idempotency ledger, row locks, and tests that reproduce lost replies and restarts.
- Authorization is centralized, hides what callers should not know, and has a matrix test.
- Fail-fast secrets, no default credentials, and Alembic with a drift guard.
- Pure, testable policies such as `ListeningLoopPolicy`, `CrashLoopPolicy` and `PhraseSegmenter`.
- A habit of reproducing a bug with a failing test before fixing it, which is visible throughout `PROGRESS.md`.

## Recommended features

### Build these first

| Feature | Size | Why | Builds on |
| --- | --- | --- | --- |
| 1. Pre-PDF check | S | Before closing a list: sync, then show totals per distributor and anything still in review or unknown, so nothing silently misses the order. It also fixes the high finding above. | `SessionViewModel.generatePdfs`, `WorkspaceSessionResolver`, `SaveState`, `NeedsReviewRepository`, `UnknownRepository` |
| 2. Last-added strip with undo | S | After each dictation, show "Lapte Zuzu ×2" with Undo and a short vibration. People walking a shop aisle can trust voice without opening the list. | `HomeViewModel.processSpokenText`, `HomeSpeechStatus`, `SessionRepository.deleteItem` |
| 3. Typed add | S | A search box on the list. Today only voice and review picks can add an item, so when recognition fails there is no fallback at all. | `SessionScreen`, `ProductFts`, `AddProductUseCase`; Flutter already has this picker |
| 4. Unknown items stay on the order | L | A phrase the catalog does not know is lost from the order today: it stays on one phone with no quantity, and cannot be saved by non-admins. Let it travel as a free-text line, printed on the PDF, with an admin inbox to add it to the catalog. | `UnknownRepository`, `SaveProductToCatalogUseCase`, `PdfRepository`; needs a nullable product on session items |
| 5. Send per distributor, with sent status | M | Share each PDF to the right supplier from a stored contact, and record that it went. Depends on your answer to the first question below. | `createShareIntent`, `PDFsScreen`, `DistributorEntity`; needs a contact field and a send log |

### Then

| Feature | Size | Why | Builds on |
| --- | --- | --- | --- |
| Learn aliases from corrections | M | When someone picks a suggestion, keep what they said as an alias, so the same mishearing stops recurring. Cheaper than embeddings, and likely to raise accuracy most for the effort. | `NeedsReviewViewModel`, `Product.aliases` |
| Order history and repeat order | M | Completed lists are stored, but no endpoint or screen shows them. Repeat last order is the first payoff, and the base for the two below. | `GlobalSession.completed_at`, `GlobalSessionItem` |
| Reorder suggestions | M | "You usually order this every two weeks." Worth building only after months of history exist. | Order history, `SessionOp` |
| Prices and order value | M | `Product.price` exists on the server but not on Android, and each CSV import overwrites it. Add an estimated order total and price history. | `catalog_import.py`, `ProductDTO` |
| Spend and supplier reports | M | The admin dashboard shows counts only. Show spend by supplier and month. | `/api/admin/dashboard`, `admin.html` |
| Delivery check | M | Tick off what actually arrived against what was ordered, and see shortages. | Order history, PDF check-off column from the approved spec |
| Inventory price autofill | S | Fill the price from the catalog when a counted product matches it. | `InventoryViewModel`, `Product.price` |
| SAGA import preset | M | Your catalog comes from SAGA, but each export is mapped by hand. A preset removes that step. Needs a real export file. | `catalog_import.py` |
| Match-quality metrics | S | Count auto-adds, undos, review picks and unknowns, so thresholds are tuned from real use. No transcripts need to be stored. | `/api/crashes` pattern, `ResolveSpokenProductUseCase` |

### Would not build

| Idea | Reason |
| --- | --- |
| On-device embeddings now | A model adds an estimated 90 to 120 MB, and alias learning is far cheaper to try first. |
| Barcode scanning | The catalog spec says this data has no barcodes. |
| Multi-tenant SaaS | The design documents say single company, and nothing in the repo shows demand. |
| Supplier portal or EDI | Too heavy for one business; a prefilled message covers it. |
| Demand forecasting | There is no sales data, so the output would be guesses. |
| CRDT sync | Few writers, and versions plus idempotency keys already converge. |

## Linear status, 43 issues

28 Done, 3 In Review, 2 In Progress, 10 Backlog. Nine were already Done (EX-10 to EX-14, EX-17, EX-18, EX-20, EX-22).

### Done

| Issue | State |
| --- | --- |
| EX-5, EX-6 | Merged, and on `origin/development` |
| EX-7 | Merged into local `development` only |
| EX-26 | Replay tests and the trigger fix, `4c97cde`, are in local `development`. The tests call `SyncService` directly, so they missed the broken trigger; `origin/development` still has it |
| EX-27 | The backend duplicate-delivery test is on `origin/development`; the Android side is in local `development` |
| EX-30, EX-31 | WebSocket scoping and the stats leak are fixed on `origin/development` |

### Done on the local merge

These meet their own "Done when" and are in local `development` at `05d767d`. They reached `origin/development` through PR 22 and are deployed.

| Issue | What landed |
| --- | --- |
| EX-25 | Two-writer storm, and Android's way out of a 409. PostgreSQL variant passes on CI |
| EX-29 | `SyncService` 563 to 335 lines, `SyncDebugScreen` 607 to 221 |
| EX-33, EX-34 | Catalog and Home screens split. Not checked on a real phone |
| EX-37 | Profile fields end to end. Android screen not run on a real phone |
| EX-39, EX-40 | Results on synthetic noise: keep phonetic 0.8, keep N-best 5 |
| EX-42 | Session 25/36 to 36/36, inventory 11/17 to 17/17, on a hand-written slice |
| EX-43 | Decided: finish. Flutter runs on the real API; widget tests pass and a debug APK builds, but it has not run on a device |
| EX-45, EX-46, EX-47 | Token refresh and revocation, CORS (no web origin deployed yet), dependency audit at zero findings |

### In Review, merged locally, with a real-world check left

| Issue | What landed |
| --- | --- |
| EX-35 | Save-state chip. Its "verified against confusion points" step waits on EX-32 |
| EX-36 | Big-REC home. "Obvious to a first-time user" needs a real user |
| EX-44 | Shared inventory on server and Android; real phones still needed |

### Still open, and what each needs

| Issue | Needs |
| --- | --- |
| EX-24 | Yours to accept. A test in local `development` meets the original criterion, and the separate Talos candidate awaits your acceptance as its note asks |
| EX-8 | Needs your go-ahead to publish `development` and delete remote branches |
| EX-9, EX-28 | Needs a second real phone and a two-phone run |
| EX-15, EX-16 | Needs real shop crash reports over the seven-day window |
| EX-19, EX-21, EX-23 | Needs tens of real dictations per noise condition. A synthetic floor is in place toward EX-23's gate |
| EX-32 | Needs someone watching a real non-technical user |
| EX-38 | Needs a `model.onnx` and a real corpus. The runtime side is ready |
| EX-41 | Needs consented recorded shop audio. A provisional comparison is in `VOICE-BENCHMARK.md` |

## Documents that no longer match the code

- `CLAUDE.md`: still says one global session, no teams, no op-log, an insecure `SECRET_KEY` fallback and open catalog writes. Only the Flutter and auth lines were corrected.
- `README-Project_Ideea.md`, `APP_DESCRIPTION.md`: "100% offline", a distributor live-view role, and multi-location collaboration are not in the code.
- `Server-side.md`: routes, auth and database description are out of date.
- `How_it_works.md`: "No data loss" conflicts with `fallbackToDestructiveMigration` for old versions and with crash recovery.
- `docs/UPGRADE_PROMPT.md`: says no Alembic, and much else is superseded.

## Questions for you

1. Do the per-distributor PDFs go to suppliers, or are they pick lists for your own staff? Metro, Auchan and Dedeman in the seed data suggest pick lists. This decides whether sending (feature 5) or a delivery check matters more.
2. Did the deleted production database hold anything you need? If so, ask Render support whether it can be recovered.
3. Should registration stay open? It is what makes the presence finding and public-write surface matter.
