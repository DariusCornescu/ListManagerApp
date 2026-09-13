# ListManager — Sync Design (Phase 3: Multi-Writer Hardening)

Status: **implemented and hardened through M7** — scope = Option C (backend
op-log plus durable Android outbox). Alembic now manages the schema; existing
mobile endpoint request/response shapes remain compatible.

## 1. Original mechanism before hardening (historical baseline)
- **Add item** `POST /api/session/{id}/items` (`main.py`): if `(session_id, product_id)` exists,
  increments quantity + bumps `version`; else inserts. Add-add of the same product already
  **merges (sums)** server-side. IDs are server-generated ints; **no client UUIDs, no
  idempotency keys.**
- **Update item** `PUT /api/session/items/{item_id}`: optimistic lock — client sends `version`;
  mismatch → **409** (with `current_version`); match → overwrites quantity (absolute set).
- **Delete** is unconditional (no version check, no tombstone).
- **Android offline queue** (`PendingOperationEntity`/`SyncWorker`/`SyncService`): replays queued
  ops **at-least-once and non-idempotently**; on 409 it drops the edit (no reconcile). Update/
  delete ops reference a **server item id** (can't replay for items created offline).
- **WebSocket** `manager.broadcast` sends every event to **all** connected users (no scoping).

## 2. Failure modes
| # | Scenario | Today | Desired |
|---|----------|-------|---------|
| FM1 | Two members edit same item offline, both replay | 2nd replay 409s → silently dropped | Deterministic + reconcilable; with deltas, both apply |
| FM2 | Two members add same product offline | Sums to 2 (correct) | Preserve |
| FM3 | Add batch replays twice (flaky net) | Double-counts | Replay is a no-op (idempotency key) |
| FM5 | Delete then concurrent update | Update 404s, undocumented | Documented: **delete wins**; re-add resurrects |
| FM6 / F7 | Any team-session event | Broadcast to everyone incl. non-members | Only owner / team members receive it |

## 3. Chosen design — Option C (backend op-log behind adapters)
Build the correctness machinery on the backend where it is pytest-verifiable; keep legacy
endpoints as thin adapters so today's Android binary is unaffected; defer the Android queue
rework to a follow-on verified on a device.

**New additive tables (`models.py`):**
- `AppliedOp(key TEXT PRIMARY KEY/UNIQUE, session_id, item_id NULL, result_json TEXT, created_at)`
  — idempotency ledger. Repeat key → return stored result, do not re-apply (fixes FM3).
- `SessionOp(id PK, session_id FK, seq, op_type, item_uuid, product_id NULL, qty_delta NULL,
  idempotency_key UNIQUE NULL, actor_user_id, created_at)` — append-only log with a
  **per-session monotonic `seq`**. Deletes recorded as a `RemoveItem` op (tombstone).
- `GlobalSessionItem` gains a nullable `item_uuid` (server-generates if the client doesn't
  supply one) so future client-generated UUIDs slot in without a breaking change.

**New module `app/sync_ops.py`:** apply-with-idempotency-key, `seq` assignment, op recording,
delete-wins rule, and `get_ops_since(session_id, seq)`. The item endpoints route through it.

**New endpoint:** `GET /api/sessions/{id}/ops?since=<seq>` (auth + `require_session_access`) →
ops the caller hasn't seen, so a client can fold to converged state. WebSocket stays for
liveness only.

**Optional fields (back-compat):** `idempotency_key` on item create/update schemas; `item_uuid`
in `GlobalSessionItemDTO`. All optional — omitting them preserves today's behavior.

## 4. F7 — WebSocket scoping
Add `broadcast_to_session(message, session_id, db)` to `ConnectionManager`: resolve the
session's recipient set (personal → `{owner_user_id}`; team → all `TeamMember.user_id`, reusing
`authz.get_user_team_ids`) and send only to those connections. Replace the six session/item
`manager.broadcast(...)` calls with it. Catalog broadcasts (global, non-tenant data) may stay a
true broadcast — documented choice. Recompute recipients per send (membership can change).

## 5. Test plan (backend, pytest)
- **Convergence:** two members concurrently add product P → one row, qty 2.
- **409 reconcile:** A updates (→200), B sends stale version (→409 w/ current_version), B
  re-reads + resubmits (→200); final state deterministic, B's intent not silently lost.
- **Idempotency:** same `idempotency_key` twice → single effect + stored result returned; two
  different keys → both apply (no false dedupe).
- **Delete-wins:** delete then replay update referencing it → defined no-op/404; re-add = fresh row.
- **Op pull:** `GET /ops?since=<seq>` returns only newer ops, scoped by access.
- **F7:** non-member's socket receives nothing for a team-session event; a member's does.
- **Matrix-under-concurrency:** the Phase-2 authz matrix still holds with an interleaved op.

## 6. Android follow-on

The Android outbox now persists operation identity, preserves it across retries,
and retains unresolved conflicts for review. Full automatic 409 reconciliation
and the dedicated resolution UI remain follow-up work; the backend stays
backward-compatible with older clients.

**Done when:** the §5 tests are green, the full suite stays green, this doc matches what shipped,
and existing endpoints are unchanged in shape.

## 7. PostgreSQL multi-writer serialization (M7)

The original `MAX(seq) + 1` allocator and read-modify-write endpoints were safe
only in sequential SQLite tests. M7 serializes add, update, delete, clear, and
completion transactions with a `FOR UPDATE` lock on the owning session. A
`next_op_seq` value on `global_sessions` is incremented with one atomic
`UPDATE ... RETURNING`, while a unique `(session_id, seq)` constraint rejects
any allocator regression. The migration backfills each counter from existing
history and refuses to add the constraint if duplicate history already exists.

`tests/test_postgres_concurrency.py` is an opt-in destructive test restricted to
a PostgreSQL database whose name ends in `_test`. Separate connections begin at
a synchronization barrier and verify summed increments, concurrent idempotent
delivery, one-winner optimistic updates, and contiguous unique sequences.
