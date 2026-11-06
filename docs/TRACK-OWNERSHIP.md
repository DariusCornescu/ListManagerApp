# Parallel track ownership

This map applies after M1 (`Cut the seams`) and is the working agreement for
the four parallel Linear tracks. A path listed under a track has one writer at
a time: that track. Tests beside an owned path follow the same owner.

## Ownership map

### T1 - Reliability: crash-free

Linear scope: EX-12 through EX-17. T1 owns crash capture, crash grouping, crash
triage, and the crash-loop recovery guard.

- `backend-fastapi/app/routers/crashes.py`
- crash-specific code in `backend-fastapi/app/routers/admin.py`
- `backend-fastapi/app/static/admin.html` while implementing the grouped crash view
- `android-native/app/src/main/java/com/darius/listmanager/util/CrashReporter.kt`
- `android-native/app/src/main/java/com/darius/listmanager/util/CrashLoopGuard.kt`
- crash-focused backend and Android tests

T1 requests an Alembic revision slot from T3 for the nullable crash signature
column and its backfill.

### T2 - Voice: matching you can trust

Linear scope: EX-18 through EX-23. T2 owns the benchmark corpus, matching
metrics, weight sweeps, and the production voice/ranking pipeline.

- `android-native/app/src/main/java/com/darius/listmanager/data/embedding/`
- `android-native/app/src/main/java/com/darius/listmanager/data/speech/`
- `android-native/app/src/main/java/com/darius/listmanager/data/usecase/ResolveSpokenProductUseCase.kt`
- voice and matching files under `android-native/app/src/main/java/com/darius/listmanager/util/`
  (`EmbeddingSearch.kt`, `PhraseSegmenter.kt`, `ProductRanker.kt`,
  `QueryVariants.kt`, `SimilarityEngine.kt`, `SpokenQuantity.kt`, `TextNorm.kt`,
  and `VectorMath.kt`)
- voice benchmark assets and the corresponding Android tests
- `backend-fastapi/app/routers/speech.py`
- `backend-fastapi/app/transcription.py`
- speech-focused backend tests

### T3 - Sync: convergence you can trust

Linear scope: EX-24 through EX-31. T3 owns session convergence, pending-operation
replay, WebSocket scoping, sync diagnostics, and all migration ordering.

- `backend-fastapi/app/routers/sessions.py`
- `backend-fastapi/app/routers/presence.py`
- `backend-fastapi/app/routers/stats.py`
- `backend-fastapi/app/sync_ops.py`
- `backend-fastapi/app/websocket_manager.py`
- `backend-fastapi/alembic/` and every new Alembic revision
- `android-native/app/src/main/java/com/darius/listmanager/sync/`
- `android-native/app/src/main/java/com/darius/listmanager/data/websocket/`
- `android-native/app/src/main/java/com/darius/listmanager/data/workspace/`
- `android-native/app/src/main/java/com/darius/listmanager/network/SessionItemOperationSender.kt`
- `android-native/app/src/main/java/com/darius/listmanager/data/repository/PendingOperationRepository.kt`
- `android-native/app/src/main/java/com/darius/listmanager/data/repository/SessionRepository.kt`
- `android-native/app/src/main/java/com/darius/listmanager/data/repository/SessionTransaction.kt`
- `android-native/app/src/main/java/com/darius/listmanager/data/repository/ProductRepository.kt`
- `android-native/app/src/main/java/com/darius/listmanager/data/repository/DistributorRepository.kt`
- `android-native/app/src/main/java/com/darius/listmanager/ui/screens/SyncDebugScreen.kt`
- sync-focused backend and Android tests

### T4 - Experience: usable by someone who isn't you

Linear scope: EX-32 through EX-37. T4 owns the observed UX fixes, Compose screen
decomposition, the big-REC home, save-state presentation, and user profile UI.

- `android-native/app/src/main/java/com/darius/listmanager/ui/screens/`
  except `SyncDebugScreen.kt`
- `android-native/app/src/main/java/com/darius/listmanager/ui/components/`
- `android-native/app/src/main/java/com/darius/listmanager/ui/navigation/`
- `android-native/app/src/main/java/com/darius/listmanager/ui/theme/`
- UI-facing view models changed by EX-32 through EX-37
- `backend-fastapi/app/routers/auth.py` for the profile endpoint
- profile and UI-focused backend and Android tests

T4 requests an Alembic revision slot from T3 for the append-only user profile
columns.

### T5 - Catalog admin: the real catalog, kept private

Scope: the admin catalog read and search API, moving `/admin` onto it, and
keeping purchase prices out of public responses. T5 owns catalog import too.

- `backend-fastapi/app/routers/catalog.py`
- `backend-fastapi/app/services/catalog_import.py`
- catalog-specific code in `backend-fastapi/app/routers/admin.py`
- `backend-fastapi/app/static/admin.html`, except T1's crash view
- catalog and admin-page backend tests

Getting catalog data onto phones belongs to T3. Matching against it belongs to
T2. `GET /api/catalog/products` stays a full list for the mobile clients.

## Shared integration surfaces

The following paths are shared and are not silently claimed by a track:

- `backend-fastapi/app/main.py`, `backend-fastapi/app/routers/__init__.py`, and
  `backend-fastapi/tests/test_route_contract.py`
- `backend-fastapi/app/models.py` and `backend-fastapi/app/schemas*.py`
- Android Room entities and DAOs under `data/local/`, plus network DTOs
- `android-native/app/src/main/java/com/darius/listmanager/ListManagerApp.kt`,
  `MainActivity.kt`, `AndroidManifest.xml`, and Gradle build files
- this document and cross-track progress/release documentation

Models, backend schemas, Android DTOs, Room entities, and DAOs are append-only
during parallel track work. A track may add a nullable field, table, DTO, or
method needed by its issue; it must not rename, remove, or reinterpret an
existing member without an integration change agreed by every affected track.

## Collision rules

1. Use one Git worktree and one branch per track. Do not run two tracks from the
   same checkout.
2. A fresh worktree must receive the ignored local
   `android-native/app/src/main/java/com/darius/listmanager/network/ApiConfig.kt`
   from the main checkout before any Gradle gate is run. Never commit that file.
3. T3 assigns and orders all Alembic revisions. Another track describes its
   append-only model change and waits for a revision slot before generating or
   editing a migration.
4. Public HTTP methods, HTTP paths, and `/ws` never change as part of a track.
   Additive endpoints are allowed when the issue requires one. Renames and
   removals require a separate compatibility plan and an update to the route
   contract test.
5. Before editing a shared integration surface, the track records the intended
   change in its issue and coordinates with every track that touches the same
   surface. Keep the edit additive and as small as possible.
6. New files inherit the owner of their domain. If ownership is ambiguous, stop
   before editing and assign the path in this document.
7. Each track keeps its focused tests with its code and runs the full relevant
   suite before commit. A merge candidate must pass backend `pytest -q` and
   Android `gradlew testDebug` when Android code is affected.
