# ListManager — Sync Design (Phase 3: Multi-Writer Hardening)

Status: **accepted** — scope = Option C (backend op-log, backend-first; Android rework deferred).
Constraints: **no Alembic** (additive tables / nullable columns only, picked up by
`Base.metadata.create_all()`); **existing mobile endpoints keep their request/response shapes**
(the shipped Android client must keep working). New behavior is opt-in via optional fields and
new endpoints.

## 1. Current mechanism (cited)
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

## 6. Deferred (separate follow-on, needs device/emulator)
Android queue rework: add `idempotencyKey` (Room migration), send it on replay, switch reads to
the `?since=` pull endpoint, and replace drop-on-409 with a reconcile loop. Not done in this
phase; the backend remains backward-compatible until then.

**Done when:** the §5 tests are green, the full suite stays green, this doc matches what shipped,
and existing endpoints are unchanged in shape.
