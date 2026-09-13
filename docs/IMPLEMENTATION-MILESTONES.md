# Reliability implementation milestones

Each milestone adds regression coverage that demonstrates the defect, applies
the fix, and runs its complete relevant suite before a commit. A missing tool,
failed test, or unverified required integration test blocks that milestone's
commit. No automatic push or deployment is part of this workflow.

| Milestone | Scope | Required acceptance gate |
| --- | --- | --- |
| M1 | A01–A03: backend session integrity and acknowledgement replay | New integrity regression tests and full backend suite pass; `git diff --check` passes |
| M2 | A12: reject negative/overflow quantities and oversized operation keys | Invalid requests leave item, version and ledger unchanged; summed quantities cannot overflow; legacy zero PUT behavior remains; full backend suite passes |
| M3 | A04: Android durable operation identity | Commit-then-disconnect/restart/replay changes quantity once; request serialization and Room transaction tests pass; full Android unit suite and debug build pass |
| M4 | A05–A06: worker recovery and snapshot reconciliation | Transient retry, interrupted worker, competing workers, pending edits during refresh, and conflict tests pass; full Android suite/build pass |
| M5 | A07: account/workspace isolation | Room migration retains offline work; cross-account offline read/replay tests pass; full Android suite/build pass |
| M6 | A08–A09/A11: offline session creation and completion | Restart/reconnect promotion and completion tests pass; completed-list policy confirmed; backend/Android suites and integration tests pass |
| M7 | A10: concurrent backend writes | Real PostgreSQL concurrent connection tests prove exactly-once increments, unique sequences, and version conflicts; migrations and full backend suite pass |

M2 preserves zero-quantity PUT compatibility until the product contract is
confirmed. It rejects negative quantities and zero additions; Android already
uses DELETE for removing an item. No cleanup of historical rows is included.

## Commands

From `backend-fastapi` (PowerShell, isolated application DB):

```powershell
$env:DATABASE_URL = 'sqlite:///:memory:'
.\.venv\Scripts\python.exe -m pytest -q --tb=short
```

From `android-native`, with Java and the licensed Android SDK configured:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

M3 keeps Room schema version 7 unchanged. Its persistence gate uses generated
Room DAOs and native SQLite under Robolectric (file reopen and transaction
rollback), in the local Android suite. This verifies storage behavior without
a device; it does not claim device instrumentation coverage. Milestones that
change Room schema still require migration instrumentation:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Before committing, inspect the staged diff, run `git diff --check`, and commit
only the milestone's files. Record actual results below; never equate an
unavailable test with a passing test.

## Results

- M1: regression baseline was 3 failures/1 pass; the full pre-commit backend
  rerun passed **227 tests**, 176 warnings, in 149.01s. `git diff --check` passed.
- M1 commit: `72d70df`.
- M2: 13 new regression cases (12 failed/1 passed before the fix). Full backend
  gate on 2026-09-07: **240 passed**, 195 warnings, 137.87s. No schema change.
  Invalid requests do not write items or operation history; boundary values,
  replay after a rejected increment, and legacy zero PUT behavior are covered.
- M2 commit: `e54800b`.
- M3: **120 tests passed**, zero failures/skips; `testDebugUnitTest assembleDebug`
  succeeded on 2026-09-11 in 32s. Includes 12 new regression/persistence tests.
  No device instrumentation run. `git diff --check` passed.
- M3 commit: `80b82b2`.
- M4: **141 tests passed**, no failures/skips; Android debug build succeeded
  in 65s on 2026-09-11. 21 new tests; recovery and catalog regressions were
  reproduced before fixes. Room schema unchanged; native SQLite tests, not
  device instrumentation. Diff check passed.
- M5: **173 Debug tests and 173 Release tests passed**, with no failures or
  skips, and Preview assembly succeeded on 2026-09-13. Three new native SQLite
  tests cover the 7→8 migration, personal-session isolation, shared team cache,
  and cross-account outbox replay. Installing Preview over the existing S25
  app preserved the database and launched without Room/SQLite/runtime errors.
- M6–M7: not complete on this branch. M7 is complete separately on
  `fix/postgres-concurrent-op-sequences`.
- Android setup: verified Microsoft OpenJDK 17.0.20.1 was extracted into the
  ignored `android-native/.tooling/jdk` folder, without system configuration
  changes. The user accepted the Android SDK license; platform 36, build tools
  35.0.0 and platform tools are installed in ignored local tooling.
- Pending product decision: completed-list edit policy. Inventory remains
  device-local per the existing EX-44 scope.
