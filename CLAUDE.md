# CLAUDE.md — ListManager

## What this project is
Shopping/list manager built around a single **shared, global** shopping session that
multiple devices view and edit in real time. Voice input is matched to a product catalog
by a custom weighted on-device similarity ranker (Android, Kotlin). Completing a session
groups items by distributor for PDF generation.

Monorepo layout:
- `backend-fastapi/` — FastAPI backend (Python). App package in `backend-fastapi/app/`.
- `android-native/` — Android app (Kotlin, Jetpack Compose, Room). The primary, most
  complete client. Holds the voice + similarity-ranking code.
- `flutter_ui/` — Flutter client. Currently an early prototype: product CRUD screens
  backed by `lib/services/fake_product_service.dart` (no real API/auth/sync wiring yet).

> There is **no** `vendor/listmanager-speech/` package and **no** backend similarity
> ranker. The ranker and speech handling live on-device in `android-native/`. See
> `docs/UPGRADE_PROMPT.md` — its phase plan was written against an earlier/imagined design
> and is being reconciled with this reality.

## Commands
PowerShell on Windows. Run each from its own subdirectory.

- Backend run: `cd backend-fastapi; .\venv\Scripts\Activate.ps1; uvicorn app.main:app --reload`
- Backend tests: `cd backend-fastapi; .\venv\Scripts\Activate.ps1; pytest -q` (config in `pytest.ini`)
- Android build: `cd android-native; .\gradlew assembleDebug`
- Android tests: `cd android-native; .\gradlew testDebug` (instrumented: `connectedAndroidTest`)
- Flutter: `cd flutter_ui; flutter test` / `flutter run`

## Stack facts (do not guess — these are the truth)
- DB: SQLite in dev (`backend-fastapi/listmanager.db`), PostgreSQL in prod via
  `DATABASE_URL`. SQLAlchemy 2.0 ORM. **Alembic** manages schema: migrations live
  in `backend-fastapi/alembic/versions/`, and the app runs `alembic upgrade head`
  at startup (`app/main.py`), then auto-seeds (`app/seed.py`, `app/seed_admin.py`).
  To change schema: edit the models, then from `backend-fastapi/`:
  `.\venv\Scripts\python.exe -m alembic revision --autogenerate -m "..."`, review
  the generated migration (autogenerate does NOT detect primary-key changes —
  hand-author those), then run the suite (the drift-guard test in
  `tests/test_migrations.py` fails if models and migrations diverge). SQLite can't
  ALTER constraints, so migrations use
  `op.batch_alter_table(..., recreate="always")`; pass
  `naming_convention=NAMING_CONVENTION` (from `app/database.py`) to a batch op
  when it must DROP a constraint by name. A constraint naming convention on
  `Base.metadata` keeps names deterministic.
  Adopting on a DB created by the OLD `create_all()` (it has tables but no
  `alembic_version`): run `alembic stamp 0001` ONCE to mark the baseline, then
  `upgrade head` for later migrations. On a FAILED SQLite migration, drop any
  leftover `_alembic_tmp_*` table before retrying (Postgres DDL is transactional,
  so it's unaffected).
- Auth: JWT (HS256, `python-jose`) via `Authorization: Bearer` header; passwords hashed
  with `bcrypt`; roles `USER` / `ADMIN`. Token expiry 24h (`ACCESS_TOKEN_EXPIRE_MINUTES`),
  **no refresh or revocation**. `SECRET_KEY` from env with an **insecure dev fallback** in
  `app/auth.py`. Several catalog/distributor/product endpoints are deliberately
  unauthenticated ("no auth required for demo") — see `app/main.py`.
- Sessions model: **one global shared session at a time** (`GlobalSession` +
  `GlobalSessionItem` in `app/models.py`), not per-user. No ownership/team concept exists.
- Sync mechanism today: real-time fan-out over WebSocket (`/ws?token=<jwt>`,
  `app/websocket_manager.py`); writes use **optimistic locking** via a `version` column
  (mismatch → HTTP 409). Android holds an **offline queue** (`PendingOperationEntity` +
  `SyncWorker`/`SyncService`) replayed on reconnect. There is no op-log / CRDT sync.
- Similarity ranker location: **Android, not backend** —
  `android-native/app/src/main/java/com/darius/listmanager/util/ProductRanker.kt`
  (+ `SimilarityEngine.kt`, `TextNorm.kt`, `QueryVariants.kt`). Weighted blend of
  Levenshtein / Jaccard / token / phonetic / prefix / contains / number-sequence scores.

## Standing rules
- Read the relevant code before changing it. Never invent endpoints, models, or fields —
  if something referenced doesn't exist, stop and say so.
- Plan mode for any task touching >3 files or any DB schema change. Show the plan first.
- One concern per commit; conventional messages (`feat:`, `fix:`, `sec:`, `refactor:`, `test:`).
- Run the test suite after every task. A task is not done with red tests.
- No drive-by refactors, no reformatting unrelated files, no dependency upgrades unless
  required by a fix (and then pinned + noted in the commit message).
- Ask before: destructive schema changes, deleting files, changing public API shapes the
  mobile clients already depend on.
- Mobile compatibility rule: existing client endpoints keep working. New behavior goes in
  new endpoints or versioned routes; breaking changes need an explicit migration note in
  `docs/PROGRESS.md`.
- Update `docs/PROGRESS.md` at the end of every working session: what changed, what's next,
  open questions.
- Secrets never in code or commits. `.env` + `.env.example` pattern.
