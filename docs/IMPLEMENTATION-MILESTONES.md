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

Room migration/transaction instrumentation must also pass for milestones that
change Room schema or persistence:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Before committing, inspect the staged diff, run `git diff --check`, and commit
only the milestone's files. Record actual results below; never equate an
unavailable test with a passing test.

## Results

- M1: regression baseline was 3 failures/1 pass; the full pre-commit backend
  rerun passed **227 tests**, 176 warnings, in 149.01s. `git diff --check` passed.
- M2–M7: not complete.
- Android setup: no Java installation or SDK found in the standard local paths;
  test/build gates cannot yet run. SDK license acceptance is required for setup.
- Pending product decisions: completed-list edit policy and inventory ownership.
