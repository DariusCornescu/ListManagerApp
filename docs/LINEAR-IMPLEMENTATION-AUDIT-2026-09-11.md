# Linear implementation audit — ListManagerApp

Checked 2026-09-11 against local branch `codex/reliability-milestones`, HEAD `e94e890`, and the live ex-dario List-Manager App project. This is a source/test audit, not a deployment or visual UX audit. No Linear statuses changed.

## Conclusion

The project is not fully implemented. All 43 retrieved issues remain Backlog in Linear, but several have code/test evidence. The local reliability M1–M4 labels are implementation steps under T3, not completion of Linear M0/M1 or the entire roadmap. No top-level milestone has all exit evidence.

Counts (conservative evidence assessment): Implemented locally: 6; Not complete: 5; Partial: 8; Unverified: 5; Not implemented: 10; Not found: 8; Open decision: 1.

## Verification

- Fresh backend checks: 54 tests passed across WebSocket scoping, idempotency, session integrity and authorization; 3 stats tests passed separately.
- Existing last full gates: 240 backend tests; 141 Android tests plus debug build. Android was not rerun for this read-only audit.
- Stored branch ancestry inspected, without fetching remote updates. No device deployment, shop observation, live crash-report collection, or two-phone execution was performed.
- The August 31 redo design referenced by Linear was not found in this checkout.

## Issue-by-issue findings

| Issue | Assessment | Evidence / remaining work |
| --- | --- | --- |
| [EX-5: Merge feat/nbest-semantic-matching into development](https://linear.app/ex-dario/issue/EX-5/merge-featnbest-semantic-matching-into-development) | Implemented locally | Stored nbest branch tip is an ancestor of development; merge 498a698 is present. Remote freshness/device deployment not verified. |
| [EX-6: Cherry-pick the drawer-scroll fix (f9f2b98)](https://linear.app/ex-dario/issue/EX-6/cherry-pick-the-drawer-scroll-fix-f9f2b98) | Implemented locally | f9f2b98 exists in development and current HEAD; last full Android gate passed. No fresh visual device test. |
| [EX-7: Rebase and merge feat/admin-catalog-crud](https://linear.app/ex-dario/issue/EX-7/rebase-and-merge-featadmin-catalog-crud) | Not complete | Stored admin-catalog-crud tip is not an ancestor of development; merge/rebase still outstanding. |
| [EX-8: Delete the 13 already-merged branches (local + remote)](https://linear.app/ex-dario/issue/EX-8/delete-the-13-already-merged-branches-local-remote) | Partial | Only development and the reliability branch remain locally, but numerous remote-tracking branches remain. Live remote deletion not verified. |
| [EX-9: Tag a build, install on the real phones, smoke-test the daily loop](https://linear.app/ex-dario/issue/EX-9/tag-a-build-install-on-the-real-phones-smoke-test-the-daily-loop) | Unverified | No recorded tagged two-phone daily-loop acceptance run. |
| [EX-10: Split main.py into app/routers/*](https://linear.app/ex-dario/issue/EX-10/split-mainpy-into-approuters) | Not implemented | main.py is 1,480 lines; app/routers contains only teams.py and __init__.py. |
| [EX-11: Write the track ownership map + collision rules to docs](https://linear.app/ex-dario/issue/EX-11/write-the-track-ownership-map-collision-rules-to-docs) | Not found | No dedicated track ownership map found in docs; the August 31 redo spec referenced by Linear is absent from this checkout. |
| [EX-12: Add a crash stack signature + server-side grouping](https://linear.app/ex-dario/issue/EX-12/add-a-crash-stack-signature-server-side-grouping) | Not implemented | CrashReport has no signature column; no grouping migration found. |
| [EX-13: GET /api/admin/crashes/groups endpoint](https://linear.app/ex-dario/issue/EX-13/get-apiadmincrashesgroups-endpoint) | Not implemented | No /api/admin/crashes/groups route found. |
| [EX-14: Admin page: grouped crash view](https://linear.app/ex-dario/issue/EX-14/admin-page-grouped-crash-view) | Not implemented | Grouped crash endpoint/model absent; existing admin crash reporting does not meet this grouped-view requirement. |
| [EX-15: Triage the real crash reports](https://linear.app/ex-dario/issue/EX-15/triage-the-real-crash-reports) | Unverified | No real-shop signature ranking/root-cause triage evidence found. |
| [EX-16: Fix the top-3 crash signatures](https://linear.app/ex-dario/issue/EX-16/fix-the-top-3-crash-signatures) | Unverified | No evidence tying three ranked real crash signatures to tested fixes and device rollout. |
| [EX-17: Forced-crash test for CrashLoopGuard self-heal](https://linear.app/ex-dario/issue/EX-17/forced-crash-test-for-crashloopguard-self-heal) | Partial | CrashLoopPolicyTest covers thresholds/counting; it does not force startup crashes and verify actual cache clearing/recovery. |
| [EX-18: Define the benchmark corpus format + storage](https://linear.app/ex-dario/issue/EX-18/define-the-benchmark-corpus-format-storage) | Not found | No labelled benchmark corpus/format fixture found. |
| [EX-19: Seed the corpus from real dictations](https://linear.app/ex-dario/issue/EX-19/seed-the-corpus-from-real-dictations) | Not found | No labelled real-dictation corpus found; privacy decision remains required. |
| [EX-20: ProductRankerBenchmarkTest — top-1 / top-3 / MRR](https://linear.app/ex-dario/issue/EX-20/productrankerbenchmarktest-top-1-top-3-mrr) | Not implemented | ProductRankerNBestTest/EmbeddingTest exist; no ProductRankerBenchmarkTest or top-1/top-3/MRR corpus gate found. |
| [EX-21: Publish the baseline + set the accuracy target](https://linear.app/ex-dario/issue/EX-21/publish-the-baseline-set-the-accuracy-target) | Not found | No published corpus baseline or accuracy target found. |
| [EX-22: Weight-sweep harness over ScoringWeights](https://linear.app/ex-dario/issue/EX-22/weight-sweep-harness-over-scoringweights) | Not found | ScoringWeights exists, but no benchmark-driven sweep harness found. |
| [EX-23: Tune weights + wire the benchmark as a regression gate](https://linear.app/ex-dario/issue/EX-23/tune-weights-wire-the-benchmark-as-a-regression-gate) | Not complete | Ranking tests exist; benchmark corpus/baseline/target regression gate is missing. |
| [EX-24: Property test over randomised op interleavings](https://linear.app/ex-dario/issue/EX-24/property-test-over-randomised-op-interleavings) | Not implemented | test_sync_convergence.py uses a sequential two-member example, not randomized/property-based concurrent interleavings. |
| [EX-25: Two-writer conflict / 409-storm test](https://linear.app/ex-dario/issue/EX-25/two-writer-conflict-409-storm-test) | Partial | Version conflict tests and retained Android conflicts exist; no deterministic concurrent 409-storm convergence test. M4 pauses conflicts for review. |
| [EX-26: Dropped-connection replay of the pending-op queue](https://linear.app/ex-dario/issue/EX-26/dropped-connection-replay-of-the-pending-op-queue) | Implemented locally | M3/M4 test lost replies, Room reopen, interrupted worker recovery and queued dependent writes with fake remote responses. Real two-phone validation remains EX-28. |
| [EX-27: Duplicate-delivery test against the idempotency ledger](https://linear.app/ex-dario/issue/EX-27/duplicate-delivery-test-against-the-idempotency-ledger) | Implemented locally | Idempotency ledger tests verify duplicate keys apply once; Android durable identities survive lost replies and reopen. Concurrent PostgreSQL proof remains future work. |
| [EX-28: Scripted two-phone scenario on real devices](https://linear.app/ex-dario/issue/EX-28/scripted-two-phone-scenario-on-real-devices) | Unverified | No actual two-phone scenario execution recorded. |
| [EX-29: Audit and simplify SyncService / SyncDebugScreen](https://linear.app/ex-dario/issue/EX-29/audit-and-simplify-syncservice-syncdebugscreen) | Partial | SyncService replay/recovery simplified in M4; SyncDebugScreen has not received the requested refactor. M4 also changes behavior, unlike this mechanical-refactor ticket. |
| [EX-30: Close F7: global WebSocket broadcast cross-team leak](https://linear.app/ex-dario/issue/EX-30/close-f7-global-websocket-broadcast-cross-team-leak) | Implemented locally | broadcast_to_session resolves owner/team recipients; session mutation routes call it. Five scoped-delivery tests pass. |
| [EX-31: Auth-gate / scope the /api/stats active-session leak](https://linear.app/ex-dario/issue/EX-31/auth-gate-scope-the-apistats-active-session-leak) | Implemented locally | /api/stats returns active_session=null, retaining aggregate counts. Three stats tests pass, including tenant name/id non-disclosure. |
| [EX-32: Observe a real session end to end, record confusion points](https://linear.app/ex-dario/issue/EX-32/observe-a-real-session-end-to-end-record-confusion-points) | Unverified | No observed non-technical user journey/confusion-point report found. |
| [EX-33: Split CatalogScreen.kt (677 lines)](https://linear.app/ex-dario/issue/EX-33/split-catalogscreenkt-677-lines) | Not complete | CatalogScreen.kt remains 678 lines; requested decomposition not found. |
| [EX-34: Split HomeScreen.kt (476 lines)](https://linear.app/ex-dario/issue/EX-34/split-homescreenkt-476-lines) | Not complete | HomeScreen.kt remains 477 lines; requested decomposition not found. |
| [EX-35: Make saved / unsaved state unmistakable](https://linear.app/ex-dario/issue/EX-35/make-saved-unsaved-state-unmistakable) | Partial | SyncStatusBar and pending/error dialog exist; no user validation against confusion points, nor dedicated conflict-resolution UI. |
| [EX-36: Big-REC home layout](https://linear.app/ex-dario/issue/EX-36/big-rec-home-layout) | Partial | Home already has a prominent 160dp microphone control. Decomposed redesign and first-user validation are not demonstrated; not safe to call the complete ticket done. |
| [EX-37: Profile page + User profile columns (needs T3 migration slot)](https://linear.app/ex-dario/issue/EX-37/profile-page-user-profile-columns-needs-t3-migration-slot) | Not implemented | User lacks full-name/company/address columns; no complete requested profile screen/migration found. |
| [EX-38: Experiment: retry on-device embeddings on a different runtime](https://linear.app/ex-dario/issue/EX-38/experiment-retry-on-device-embeddings-on-a-different-runtime) | Not complete | ONNX embedding attempt exists but was reverted for native crash; no alternative-runtime benchmark delta/keep decision. |
| [EX-39: Experiment: phonetic weight tuning for Romanian](https://linear.app/ex-dario/issue/EX-39/experiment-phonetic-weight-tuning-for-romanian) | Not found | Phonetic scoring exists; no Romanian benchmark-tuning result found. |
| [EX-40: Experiment: sweep N-best hypothesis depth](https://linear.app/ex-dario/issue/EX-40/experiment-sweep-n-best-hypothesis-depth) | Not found | N-best tests exist; no depth-sweep benchmark result found. |
| [EX-41: Experiment: alternative STT backends](https://linear.app/ex-dario/issue/EX-41/experiment-alternative-stt-backends) | Not found | No comparative STT benchmark report/recommendation found. |
| [EX-42: Experiment: quantity + number parsing accuracy](https://linear.app/ex-dario/issue/EX-42/experiment-quantity-number-parsing-accuracy) | Partial | InventoryLineParser tests exist; no quantity-labelled corpus accuracy metric or measured delta found. |
| [EX-43: Decision: finish or delete the Flutter client](https://linear.app/ex-dario/issue/EX-43/decision-finish-or-delete-the-flutter-client) | Open decision | flutter_ui remains a prototype using fake_product_service.dart; finish-or-delete decision not found. |
| [EX-44: Inventory v2: multi-phone shared sync](https://linear.app/ex-dario/issue/EX-44/inventory-v2-multi-phone-shared-sync) | Not implemented | Inventory remains device-local; shared two-phone inventory v2 is not implemented. |
| [EX-45: F4: token refresh / revocation](https://linear.app/ex-dario/issue/EX-45/f4-token-refresh-revocation) | Not implemented | No refresh-token/revocation flow found. |
| [EX-46: F11: CORS configuration](https://linear.app/ex-dario/issue/EX-46/f11-cors-configuration) | Not implemented | No CORSMiddleware/configuration for intended web origins found. |
| [EX-47: F12: pip-audit dependency scan (repair venv pip first)](https://linear.app/ex-dario/issue/EX-47/f12-pip-audit-dependency-scan-repair-venv-pip-first) | Partial | A working .venv supports tests; no completed pip-audit report or triaged dependency findings found. |

## Remaining local reliability milestones

M5 account/workspace isolation, M6 offline lifecycle/completed-list policy, and M7 real PostgreSQL concurrent writes are still open. Completing them would not, by itself, complete Linear T1/T2/T4 or the branch-consolidation/router gates.

## Suggested sequencing

Reconcile the six locally implemented issues with Linear; finish M0 integration/device acceptance and the M1 router/ownership gate; then scope the remaining T3 account/lifecycle/concurrency work and the independent crash, voice and UX tracks. Preserve real-device and user-study gates rather than substituting unit tests for them.
