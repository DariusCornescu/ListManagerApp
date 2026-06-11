# Alembic Migrations Adoption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `Base.metadata.create_all()` with Alembic migrations so schema changes have a versioned, reversible path, and ship the two deferred idempotency schema fixes as the first real migration.

**Architecture:** Alembic lives in `backend-fastapi/alembic/`. A constraint **naming convention** on `Base.metadata` makes constraint names deterministic across SQLite and Postgres. `env.py` sources the DB URL from `app.database` (already-normalized) with an override hook for tests. App startup runs `alembic upgrade head` in-process instead of `create_all`. Unit tests keep the fast in-memory `create_all` from models; one new test guards against model/migration drift.

**Tech Stack:** Alembic 1.13.x, SQLAlchemy 2.0, FastAPI, pytest. SQLite (dev/tests) + Postgres (prod). `render_as_batch=True` so SQLite gets table-recreate semantics for ALTERs it can't do natively.

**Spec:** `docs/superpowers/specs/2026-06-11-alembic-migrations-design.md`

**Conventions for all tasks:**
- Run from `backend-fastapi/`. The venv's pip launcher is broken; always invoke Python tools as modules via the venv interpreter:
  - Tests: `.\venv\Scripts\python.exe -m pytest -q`
  - Alembic: `.\venv\Scripts\python.exe -m alembic <args>`
  - Install: `.\venv\Scripts\python.exe -m pip install <pkg>` (pip-as-module works even though the `pip.exe` shim is broken)
- Commit after every task. No Claude co-author trailer.
- Do NOT commit `android-native/.settings/org.eclipse.buildship.core.prefs`.
- The full suite is currently **163 passing** — it must stay green.

---

### Task 1: Dependency + constraint naming convention

Add Alembic and give every constraint a deterministic name. The naming convention must be set BEFORE any model is defined (it is applied at table-definition time), so it goes on the `MetaData` passed to `declarative_base()`.

**Files:**
- Modify: `backend-fastapi/requirements.txt`
- Modify: `backend-fastapi/app/database.py`

- [ ] **Step 1: Add the Alembic dependency**

In `backend-fastapi/requirements.txt`, add this line in the runtime-deps block (e.g. right after `slowapi==0.1.9`):

```
alembic==1.13.1
```

- [ ] **Step 2: Install it**

Run: `cd backend-fastapi; .\venv\Scripts\python.exe -m pip install alembic==1.13.1`
Expected: installs alembic and its deps (Mako, etc.); ends with "Successfully installed".

- [ ] **Step 3: Add a naming convention to Base.metadata**

In `backend-fastapi/app/database.py`, replace:

```python
from sqlalchemy import create_engine
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
from .config import settings
```

with:

```python
from sqlalchemy import create_engine, MetaData
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
from .config import settings

# Deterministic constraint names so Alembic migrations (and batch-mode table
# recreation on SQLite) can reference constraints by a predictable name on both
# SQLite and Postgres.
NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}
```

and replace:

```python
Base = declarative_base()
```

with:

```python
Base = declarative_base(metadata=MetaData(naming_convention=NAMING_CONVENTION))
```

- [ ] **Step 4: Verify the suite still builds the schema cleanly**

Run: `cd backend-fastapi; .\venv\Scripts\python.exe -m pytest -q`
Expected: **163 passed**. (The naming convention only renames constraints; `create_all` from models still works, so existing tests are unaffected.)

- [ ] **Step 5: Commit**

```powershell
git add backend-fastapi/requirements.txt backend-fastapi/app/database.py
git commit -m "build: add alembic; deterministic constraint naming on Base.metadata"
```

---

### Task 2: Alembic scaffolding + baseline migration (0001)

Wire Alembic to the app and generate the baseline from the current models. `env.py` must prefer a URL set on the Alembic `Config` (so tests can target a throwaway DB) and fall back to the app's configured URL.

**Files:**
- Create: `backend-fastapi/alembic.ini`
- Create: `backend-fastapi/alembic/env.py`
- Create: `backend-fastapi/alembic/script.py.mako`
- Create: `backend-fastapi/alembic/versions/` (directory; the 0001 file is generated into it)

- [ ] **Step 1: Create `backend-fastapi/alembic.ini`**

```ini
[alembic]
script_location = alembic
prepend_sys_path = .
# Left blank on purpose: env.py fills this in (from the app config, or a
# per-invocation override used by tests).
sqlalchemy.url =

[loggers]
keys = root,sqlalchemy,alembic

[handlers]
keys = console

[formatters]
keys = generic

[logger_root]
level = WARN
handlers = console
qualname =

[logger_sqlalchemy]
level = WARN
handlers =
qualname = sqlalchemy.engine

[logger_alembic]
level = INFO
handlers =
qualname = alembic

[handler_console]
class = StreamHandler
args = (sys.stderr,)
level = NOTSET
formatter = generic

[formatter_generic]
format = %(levelname)-5.5s [%(name)s] %(message)s
datefmt = %H:%M:%S
```

- [ ] **Step 2: Create `backend-fastapi/alembic/script.py.mako`**

```mako
"""${message}

Revision ID: ${up_revision}
Revises: ${down_revision | comma,n}
Create Date: ${create_date}

"""
from alembic import op
import sqlalchemy as sa
${imports if imports else ""}

revision = ${repr(up_revision)}
down_revision = ${repr(down_revision)}
branch_labels = ${repr(branch_labels)}
depends_on = ${repr(depends_on)}


def upgrade():
    ${upgrades if upgrades else "pass"}


def downgrade():
    ${downgrades if downgrades else "pass"}
```

- [ ] **Step 3: Create `backend-fastapi/alembic/env.py`**

```python
"""Alembic environment for the ListManager backend.

URL resolution: prefer a URL explicitly set on the Alembic Config
(`config.set_main_option("sqlalchemy.url", ...)`), so tests and the in-process
startup call can target a specific database. Fall back to the app's configured
(already normalized) DATABASE_URL.
"""
from logging.config import fileConfig

from sqlalchemy import engine_from_config, pool

from alembic import context

# Importing app.database gives us the normalized DATABASE_URL and the Base
# whose metadata Alembic compares against. Importing app.models ensures every
# table is registered on Base.metadata before autogenerate/upgrade runs.
from app.database import Base, DATABASE_URL
import app.models  # noqa: F401  (registers all models on Base.metadata)

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

# Prefer an explicitly-provided url (tests / startup); else the app's url.
_url = config.get_main_option("sqlalchemy.url") or DATABASE_URL
config.set_main_option("sqlalchemy.url", _url)

target_metadata = Base.metadata


def run_migrations_offline() -> None:
    context.configure(
        url=_url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        render_as_batch=True,
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            render_as_batch=True,
            compare_type=True,
        )
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
```

- [ ] **Step 4: Create the empty versions directory**

Create `backend-fastapi/alembic/versions/.gitkeep` (empty file) so the directory exists before autogenerate writes into it.

- [ ] **Step 5: Generate the baseline migration against an EMPTY database**

The baseline must capture the full current schema. Generate it against a throwaway EMPTY SQLite DB — NOT the dev `listmanager.db` (which already has tables; autogenerate against a populated DB would diff to empty and produce a useless migration). Override `DATABASE_URL` to a temp file for this one command, then delete it.

Run (PowerShell, from `backend-fastapi/`):

```powershell
$env:SECRET_KEY="placeholder-for-tooling"
$env:DATABASE_URL="sqlite:///./_baseline_gen.db"
.\venv\Scripts\python.exe -m alembic revision --autogenerate -m "baseline schema" --rev-id 0001
Remove-Item .\_baseline_gen.db
Remove-Item Env:\DATABASE_URL
```

Expected: creates `alembic/versions/0001_baseline_schema.py` containing `op.create_table(...)` for every model (users, distributors, products, teams, team_members, team_invites, global_sessions, global_session_items, applied_ops, session_ops) with `revision = "0001"` and `down_revision = None`.

- [ ] **Step 6: Sanity-check the generated baseline**

Open `alembic/versions/0001_baseline_schema.py` and verify:
- `revision = "0001"`, `down_revision = None`.
- There is a `create_table` for ALL ten tables listed above.
- `applied_ops` has `key` as a single-column primary key and a nullable `session_id` (the pre-fix state — 0002 changes this).
- `session_ops.idempotency_key` carries a unique constraint (pre-fix state).
- Constraint names follow the convention (e.g. `pk_users`, `fk_products_distributor_id_distributors`, `uq_session_ops_idempotency_key`). If any constraint is unnamed, the naming convention from Task 1 was not applied — stop and fix Task 1 before continuing.

- [ ] **Step 7: Verify the baseline builds a database**

Run (from `backend-fastapi/`):

```powershell
$env:SECRET_KEY="placeholder-for-tooling"
$env:DATABASE_URL="sqlite:///./_verify.db"
.\venv\Scripts\python.exe -m alembic upgrade head
.\venv\Scripts\python.exe -m alembic downgrade base
Remove-Item .\_verify.db
```

Expected: `upgrade head` creates all tables (logs `Running upgrade  -> 0001`), `downgrade base` drops them, no errors.

- [ ] **Step 8: Commit**

```powershell
git add backend-fastapi/alembic.ini backend-fastapi/alembic/
git commit -m "feat(db): alembic scaffolding + baseline migration (0001)"
```

---

### Task 3: Run migrations at startup instead of create_all

Replace the `PHASE2_CUTOVER` block and `create_all` in `main.py` with an in-process `alembic upgrade head`. Resolve the `alembic.ini` path from the module location so it works regardless of the process working directory.

**Files:**
- Modify: `backend-fastapi/app/main.py:42-49`

- [ ] **Step 1: Read the current block**

In `backend-fastapi/app/main.py`, the current code (around lines 42-49) is:

```python
# Optional Phase 2 clean-cutover migration, gated behind an env flag so normal
# startup is unaffected. Must run BEFORE create_all so the recreated session
# tables have the new owner_user_id / team_id columns.
if os.getenv("PHASE2_CUTOVER") == "run":
    from .migrations.phase2_cutover import run_phase2_cutover
    run_phase2_cutover(engine)

models.Base.metadata.create_all(bind=engine)
```

- [ ] **Step 2: Replace it with an Alembic upgrade**

Replace the entire block above with:

```python
# Schema is managed by Alembic (see backend-fastapi/alembic/). On startup we
# bring the configured database up to the latest revision. Fresh database ->
# applies all migrations; already-current database -> no-op. This replaces the
# former create_all() + PHASE2_CUTOVER cutover (the baseline migration already
# includes the owner_user_id / team_id columns, so the cutover is obsolete).
def _run_migrations_to_head() -> None:
    from alembic.config import Config
    from alembic import command
    from .database import DATABASE_URL

    backend_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    alembic_cfg = Config(os.path.join(backend_root, "alembic.ini"))
    alembic_cfg.set_main_option("sqlalchemy.url", DATABASE_URL)
    command.upgrade(alembic_cfg, "head")


_run_migrations_to_head()
```

(Leave the `import os` and `from .database import engine` / `models` imports already at the top of the file as they are; this function imports what it needs locally.)

- [ ] **Step 3: Verify the app starts and serves against a fresh DB**

Run (from `backend-fastapi/`, using a throwaway DB so the dev DB is untouched):

```powershell
$env:SECRET_KEY="dev-secret"
$env:DATABASE_URL="sqlite:///./_startup.db"
.\venv\Scripts\python.exe -c "import app.main; print('startup migrations OK')"
Remove-Item .\_startup.db
```

Expected: prints `startup migrations OK` with Alembic upgrade logs above it (`Running upgrade -> 0001`), no traceback. (Importing `app.main` runs the module-level `_run_migrations_to_head()`.)

- [ ] **Step 4: Verify the full suite is still green**

Run: `cd backend-fastapi; .\venv\Scripts\python.exe -m pytest -q`
Expected: **163 passed**. (Tests use `conftest`'s in-memory `create_all`; they don't import the startup migration path against a real file DB, and the `TestClient` uses the dependency-overridden in-memory session.)

- [ ] **Step 5: Commit**

```powershell
git add backend-fastapi/app/main.py
git commit -m "feat(db): run alembic upgrade head at startup (replaces create_all + cutover)"
```

---

### Task 4: Migration drift guard + round-trip tests

A failing-first test that proves models and migrations agree, plus a downgrade/upgrade round-trip. These are the safety net that makes Alembic worth adopting.

**Files:**
- Create: `backend-fastapi/tests/test_migrations.py`

- [ ] **Step 1: Write the tests**

Create `backend-fastapi/tests/test_migrations.py`:

```python
"""Alembic migration integrity tests.

- Drift guard: after `upgrade head`, the live schema must match the models
  (autogenerate detects no missing operations). Fails the moment a model is
  changed without a matching migration.
- Round-trip: upgrade -> downgrade base -> upgrade head succeeds (proves every
  migration's downgrade works).
"""
from pathlib import Path

from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from sqlalchemy import create_engine

from app.database import Base
import app.models  # noqa: F401  (register all models on Base.metadata)

# backend-fastapi/tests/test_migrations.py -> parents[1] == backend-fastapi/
_ALEMBIC_INI = Path(__file__).resolve().parents[1] / "alembic.ini"


def _config_for(url: str) -> Config:
    cfg = Config(str(_ALEMBIC_INI))
    cfg.set_main_option("sqlalchemy.url", url)
    return cfg


def test_migrations_match_models(tmp_path):
    """Running all migrations yields a schema identical to the models."""
    url = f"sqlite:///{tmp_path / 'drift.db'}"
    command.upgrade(_config_for(url), "head")

    engine = create_engine(url)
    try:
        with engine.connect() as conn:
            ctx = MigrationContext.configure(
                conn,
                opts={"compare_type": True, "render_as_batch": True},
            )
            diff = compare_metadata(ctx, Base.metadata)
    finally:
        engine.dispose()

    assert diff == [], f"Models and migrations diverged: {diff}"


def test_migrations_downgrade_upgrade_roundtrip(tmp_path):
    """Every migration's downgrade works: head -> base -> head with no error."""
    url = f"sqlite:///{tmp_path / 'roundtrip.db'}"
    cfg = _config_for(url)
    command.upgrade(cfg, "head")
    command.downgrade(cfg, "base")
    command.upgrade(cfg, "head")
```

- [ ] **Step 2: Run the tests — they must pass NOW (0001 was generated from the models, so no drift)**

Run: `cd backend-fastapi; .\venv\Scripts\python.exe -m pytest tests/test_migrations.py -v`
Expected: both tests PASS. If `test_migrations_match_models` FAILS with a non-empty diff, the baseline (Task 2) does not match the models — inspect the printed diff, regenerate 0001, and re-run. (This is the test doing its job.)

- [ ] **Step 3: Run the full suite**

Run: `cd backend-fastapi; .\venv\Scripts\python.exe -m pytest -q`
Expected: **165 passed** (163 + 2 new).

- [ ] **Step 4: Commit**

```powershell
git add backend-fastapi/tests/test_migrations.py
git commit -m "test(db): migration drift guard + downgrade/upgrade round-trip"
```

---

### Task 5: Migration 0002 — idempotency schema fixes

Ship the two deferred fixes as the first real migration. The model change and the migration land together so the drift guard stays green. `AppliedOp` gains a composite primary key `(key, session_id)` (which requires `session_id` to become NOT NULL); `SessionOp.idempotency_key` loses its global unique constraint.

**Files:**
- Modify: `backend-fastapi/app/models.py:128-129` (AppliedOp), `:147` (SessionOp)
- Create: `backend-fastapi/alembic/versions/0002_idempotency_schema_fixes.py`

- [ ] **Step 1: Change the models**

In `backend-fastapi/app/models.py`, in `class AppliedOp`, replace:

```python
    key = Column(String(64), primary_key=True)  # the idempotency key
    session_id = Column(Integer, ForeignKey("global_sessions.id"))
```

with:

```python
    # Composite PK (key, session_id): an idempotency key is unique PER SESSION,
    # not globally. Prevents a key reused across sessions from returning another
    # session's stored result (cross-tenant disclosure).
    key = Column(String(64), primary_key=True)  # the idempotency key
    session_id = Column(
        Integer, ForeignKey("global_sessions.id"), primary_key=True, nullable=False
    )
```

In `class SessionOp`, replace:

```python
    idempotency_key = Column(String(64), unique=True, nullable=True)
```

with:

```python
    # No longer globally unique: record_op writes None today, and a future
    # per-session use must not be blocked by a global unique constraint.
    idempotency_key = Column(String(64), nullable=True)
```

- [ ] **Step 2: Confirm the drift guard now FAILS (models changed, no migration yet)**

Run: `cd backend-fastapi; .\venv\Scripts\python.exe -m pytest tests/test_migrations.py::test_migrations_match_models -v`
Expected: FAIL — the diff is non-empty (the live schema from 0001 no longer matches the changed models). This confirms the guard works. (If it PASSES, the model edits did not take — re-check Step 1.)

- [ ] **Step 3: Hand-author migration 0002**

Autogenerate does NOT reliably detect primary-key changes, so write this migration by hand. Create `backend-fastapi/alembic/versions/0002_idempotency_schema_fixes.py`:

```python
"""idempotency schema fixes: composite AppliedOp PK; drop SessionOp unique

Revision ID: 0002
Revises: 0001
Create Date: 2026-06-11

"""
from alembic import op
import sqlalchemy as sa

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade():
    # applied_ops: session_id becomes NOT NULL and part of a composite PK.
    # recreate="always" makes this work on SQLite (which can't ALTER a PK).
    with op.batch_alter_table("applied_ops", recreate="always") as batch_op:
        batch_op.alter_column(
            "session_id", existing_type=sa.Integer(), nullable=False
        )
        batch_op.create_primary_key("pk_applied_ops", ["key", "session_id"])

    # session_ops: drop the global unique constraint on idempotency_key.
    with op.batch_alter_table("session_ops", recreate="always") as batch_op:
        batch_op.drop_constraint(
            "uq_session_ops_idempotency_key", type_="unique"
        )


def downgrade():
    with op.batch_alter_table("session_ops", recreate="always") as batch_op:
        batch_op.create_unique_constraint(
            "uq_session_ops_idempotency_key", ["idempotency_key"]
        )

    with op.batch_alter_table("applied_ops", recreate="always") as batch_op:
        batch_op.create_primary_key("pk_applied_ops", ["key"])
        batch_op.alter_column(
            "session_id", existing_type=sa.Integer(), nullable=True
        )
```

- [ ] **Step 4: Verify the drift guard PASSES again (migration now matches models)**

Run: `cd backend-fastapi; .\venv\Scripts\python.exe -m pytest tests/test_migrations.py -v`
Expected: both tests PASS. If `test_migrations_match_models` still reports a diff, the migration doesn't fully match the model change — read the diff and adjust 0002. Common causes: the unique constraint's actual reflected name differs from `uq_session_ops_idempotency_key` (verify against the 0001 baseline file's `session_ops` constraint name and use that exact name), or `compare_type` flags the `session_id` nullability.

- [ ] **Step 5: Verify the full suite (idempotency behavior must still hold under the composite PK)**

Run: `cd backend-fastapi; .\venv\Scripts\python.exe -m pytest -q`
Expected: **165 passed**. Pay attention to `tests/test_sync_idempotency.py` — those tests exercise `store_idempotent` / `check_idempotent`, which now write/read `applied_ops` under the composite key. They use `conftest`'s `create_all` from the updated models, so the composite PK is in effect. If any fail, the composite PK broke an assumption in `sync_ops.py` — investigate before proceeding.

- [ ] **Step 6: Commit**

```powershell
git add backend-fastapi/app/models.py backend-fastapi/alembic/versions/0002_idempotency_schema_fixes.py
git commit -m "sec(db): composite AppliedOp PK + drop dead SessionOp unique (migration 0002)"
```

---

### Task 6: Document the workflow + final verification

Correct the "No Alembic" stack fact and document the day-to-day migration workflow so future schema changes have a path.

**Files:**
- Modify: `CLAUDE.md` (the "Stack facts" DB bullet)
- Modify: `docs/PROGRESS.md`

- [ ] **Step 1: Update the DB stack fact in CLAUDE.md**

In `CLAUDE.md`, find the bullet under "Stack facts" that begins "DB: SQLite in dev ... **No Alembic** — tables are created with `Base.metadata.create_all()`...". Replace the "No Alembic" sentence and its migration caveat with:

```markdown
- DB: SQLite in dev (`backend-fastapi/listmanager.db`), PostgreSQL in prod via
  `DATABASE_URL`. SQLAlchemy 2.0 ORM. **Alembic** manages schema: migrations live
  in `backend-fastapi/alembic/versions/`, and the app runs `alembic upgrade head`
  at startup (`app/main.py`). To change schema: edit the models, then
  `cd backend-fastapi; .\venv\Scripts\python.exe -m alembic revision --autogenerate -m "..."`,
  review the generated migration (autogenerate does NOT detect primary-key
  changes — hand-author those), then run the suite (the drift-guard test in
  `tests/test_migrations.py` fails if models and migrations diverge). SQLite
  can't ALTER constraints, so migrations use `op.batch_alter_table(...,
  recreate="always")`; a constraint naming convention on `Base.metadata`
  (`app/database.py`) keeps names deterministic. Seeding still runs on startup
  (`app/seed.py`, `app/seed_admin.py`).
```

- [ ] **Step 2: Add a PROGRESS.md entry**

In `docs/PROGRESS.md`, add a dated entry at the top (newest-first, matching the file's format) summarizing: Alembic adopted (baseline 0001 + migration 0002), startup now runs `upgrade head` instead of `create_all`, the `PHASE2_CUTOVER` startup hook removed (and `app/migrations/phase2_cutover.py` left as obsolete/superseded, not deleted), migration 0002 shipped the composite `AppliedOp` PK + dropped the dead `SessionOp` unique constraint, and the new drift-guard test. Note the new schema-change workflow for future sessions.

- [ ] **Step 3: Full verification sweep**

Run the whole suite once more:

Run: `cd backend-fastapi; .\venv\Scripts\python.exe -m pytest -q`
Expected: **165 passed**.

Then verify a clean end-to-end build from nothing:

```powershell
$env:SECRET_KEY="dev-secret"
$env:DATABASE_URL="sqlite:///./_final.db"
.\venv\Scripts\python.exe -m alembic upgrade head
.\venv\Scripts\python.exe -m alembic downgrade base
.\venv\Scripts\python.exe -m alembic upgrade head
Remove-Item .\_final.db
```

Expected: upgrades through `0001` then `0002`, downgrades to base cleanly, upgrades again — no errors.

- [ ] **Step 4: Commit**

```powershell
git add CLAUDE.md docs/PROGRESS.md
git commit -m "docs: document alembic workflow; correct No-Alembic stack fact"
```
