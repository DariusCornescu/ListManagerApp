# Alembic Migrations Adoption — Design Spec

**Date:** 2026-06-11
**Status:** Approved by Darius
**Scope:** `backend-fastapi/` only. No Android or Flutter changes.

## Goal

Replace the current `Base.metadata.create_all()` + destructive-fallback schema
management with **Alembic** migrations, so schema changes to an existing
(production) database have a real, versioned, reversible path instead of a
manual wipe. Prove the pipeline end-to-end by shipping the two schema fixes the
security review deferred.

## Context (current state)

- `backend-fastapi/app/main.py` runs, at import time: an env-gated
  `run_phase2_cutover(engine)` (only when `PHASE2_CUTOVER=run`), then
  `models.Base.metadata.create_all(bind=engine)`. Startup also seeds.
- `app/database.py` builds the engine from `settings.DATABASE_URL`, normalizing
  `postgres://` → `postgresql://`. Dev = SQLite file (`listmanager.db`),
  prod = Postgres (Render).
- Tests (`tests/conftest.py`) use an in-memory SQLite DB built directly from
  models via `Base.metadata.create_all` / `drop_all` per test. **No** migrations.
- No Alembic today. 163 tests passing.

## Decisions made

- **No production data to preserve** — clean adoption (baseline = current models).
- **Migrations run automatically at app startup** (`alembic upgrade head`),
  replacing `create_all`. Works on Render free tier with no separate deploy hook.
- **First real migration ships the deferred security schema fixes** (AppliedOp
  composite key + drop dead SessionOp unique constraint).
- **Tests keep the fast in-memory `create_all` from models**, plus one new
  migration-drift guard test.

## Architecture

### Files
- `backend-fastapi/alembic.ini` — Alembic config (root of backend).
- `backend-fastapi/alembic/env.py` — wires Alembic to the app.
- `backend-fastapi/alembic/versions/0001_baseline.py` — full current schema.
- `backend-fastapi/alembic/versions/0002_idempotency_schema_fixes.py` — the fixes.
- `backend-fastapi/requirements.txt` — add `alembic` (pinned).
- `backend-fastapi/app/main.py` — startup runs `alembic upgrade head`; remove the
  `PHASE2_CUTOVER` startup block and the `create_all` call.
- `backend-fastapi/tests/test_migrations.py` — drift guard (new).
- `CLAUDE.md` — document the `alembic revision --autogenerate` workflow; update the
  "No Alembic" stack fact.

The Alembic directory is `backend-fastapi/alembic/`, kept distinct from the
existing `app/migrations/` (which holds the now-obsolete `phase2_cutover.py`).

### env.py
- `target_metadata = app.models.Base.metadata` (import models so all tables
  register).
- URL sourced from `settings.DATABASE_URL` with the same `postgres://` →
  `postgresql://` normalization used in `database.py` (factor it so both share
  one helper, or replicate the two lines — implementer's call, keep DRY).
- `context.configure(..., render_as_batch=True)` in BOTH offline and online
  modes so SQLite gets table-recreate semantics for ALTERs it can't do natively
  (drop/alter constraint, change PK). Postgres uses native ALTERs.

### Migrations
- **0001_baseline:** generated via `alembic revision --autogenerate` against an
  empty database, yielding `create_table` for every model. This is the schema
  source of truth replacing `create_all`. `down_revision = None`.
- **0002_idempotency_schema_fixes** (`down_revision = "0001"`):
  - `AppliedOp` primary key changes from `(key)` to composite `(key, session_id)`.
  - Drop the global `unique=True` constraint/index on `SessionOp.idempotency_key`
    (the column stays; it is currently written as `None` and never read, but a
    per-session-unique future use should not be blocked by a global unique index).
  - Both via `op.batch_alter_table(...)` so they run on SQLite and Postgres.
  - Provide working `downgrade()` (restore single-column PK; re-add the unique
    index) so reversibility is proven.

### Startup wiring (main.py)
Replace:
```python
if os.getenv("PHASE2_CUTOVER") == "run":
    from .migrations.phase2_cutover import run_phase2_cutover
    run_phase2_cutover(engine)
models.Base.metadata.create_all(bind=engine)
```
with a call that runs `alembic upgrade head` programmatically against the app's
configured database (build an Alembic `Config` pointing at `alembic.ini`,
override `sqlalchemy.url` with the runtime `settings.DATABASE_URL`, call
`command.upgrade(cfg, "head")`). Fresh DB → applies 0001+0002; existing DB →
no-op. The `phase2_cutover.py` file is left in place but marked superseded (no
startup invocation); deletion is out of scope for this change.

## Tests

- All 163 existing tests unchanged: `conftest` keeps building the in-memory
  schema from models via `create_all`/`drop_all`. Migrations do NOT run in the
  unit-test path (kept fast and isolated).
- **New `tests/test_migrations.py`:**
  1. **Drift guard:** create a fresh SQLite DB, run `alembic upgrade head`, then
     run Alembic autogenerate comparison against `target_metadata` and assert it
     produces NO operations (models and migrations are in sync). This fails CI
     the moment a model changes without a matching migration.
  2. **Round-trip:** `upgrade head` then `downgrade base` then `upgrade head`
     again on a throwaway SQLite DB succeeds (proves 0002's downgrade works).

## Error handling / edge cases

- Startup `upgrade head` on an already-current DB is a no-op (Alembic checks the
  `alembic_version` table). On a DB that predates Alembic but already has tables
  (none expected, since no prod data) the implementer must NOT blindly upgrade —
  but per the "no data to preserve" decision this case doesn't arise; if a
  pre-existing non-Alembic DB is detected in dev, the documented recovery is to
  drop it and let migrations rebuild.
- Batch mode on SQLite recreates tables; ensure FK-bearing child tables
  (`global_session_items`, `session_ops`, `applied_ops`) migrate without FK
  violations (batch mode handles this, but the 0002 ops touch `applied_ops` and
  `session_ops` specifically — verify the round-trip test covers it).

## Success criteria

- `alembic upgrade head` builds a fresh SQLite and a fresh Postgres schema
  equivalent to the old `create_all` output.
- `alembic downgrade` works for 0002.
- The drift-guard test passes; the full suite stays green (163 + new).
- CLAUDE.md documents the day-to-day workflow:
  `alembic revision --autogenerate -m "..."` → review → commit, and the
  "No Alembic" stack fact is corrected.

## Out of scope

- Deleting `app/migrations/phase2_cutover.py`.
- Any data backfill (no data to preserve).
- Switching the unit-test suite to run via migrations.
- JWT refresh, idempotency-key sending from Android, or other roadmap items.
