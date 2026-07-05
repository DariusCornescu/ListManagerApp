# Admin Catalog Manager + CSV Import — Design

**Date:** 2026-07-05
**Status:** Approved (design), pending implementation plan
**Scope:** Single-tenant (one shared catalog — dad's company). No multi-tenant/companies.

## Goal

A lightweight web page, **served by the existing FastAPI backend**, where the admin logs in and manages the product catalog. The headline feature is **CSV import**, so dad's entire product list can be loaded — and later updated — in one upload instead of adding products one at a time.

## Why

- The current catalog is populated one product at a time (voice → unknown → manual add, or the catalog dialog). Loading dad's real product list this way is impractical.
- Barcodes don't exist for this data, so scanning is out. CSV is the natural bulk-load mechanism and matches how supplier/product lists are usually kept (spreadsheets).
- Single-tenant keeps the data model simple: catalog stays global, roles stay the existing `ADMIN`/`USER`.

## Data source & curation

The real catalog data originates from **SAGA** (Romanian accounting software). SAGA's article
nomenclator uses its own columns (cod, denumire, U.M., preț, TVA, …) and generally has **no
distributor per article** (the supplier lives on the purchase document, not the article). There is
no ready-made spreadsheet yet.

Therefore a **human curation step** sits upstream of this feature: someone exports from SAGA and
maps it into the importer's CSV contract (§2) — assigning each product to a distributor, adding
`aliases`, and setting `price`. This feature deliberately consumes only the clean CSV contract; it
does **not** parse SAGA's native export format in v1. A "SAGA preset" column-mapping could be added
later once a real SAGA export sample is available (clean follow-on, out of scope here).

## Non-goals (v1)

- Multi-tenant / companies / per-firm catalogs / director role. (Explicitly deferred; clean to add later.)
- `size` / `SKU` product fields.
- Price shown on the exported PDF (price is stored/edited only for now).
- User management in the web page (dad's account handled separately).
- Catalog export.

---

## 1. Data model change (net-new: `price`)

Add a nullable price field to **Product** on both sides. `aliases` already exists; no other new fields.

- **Backend** (`backend-fastapi/app/models.py`): `price = Column(Numeric(10, 2), nullable=True)` on `Product`. Alembic migration (additive, nullable — safe on existing rows).
- **DTOs** (`backend-fastapi/app/schemas.py`): add optional `price: Optional[Decimal]` to product create/update/read schemas, with `Field(ge=0)` validation.
- **Android:** **deferred — no change in v1.** Retrofit uses Gson, which ignores unknown JSON fields, so the backend returning `price` does not affect the phone. The phone also uses `fallbackToDestructiveMigration()`, so a DB version bump would wipe the local cache — no benefit for a field v1 never displays. Adding `price` to the phone (entity + DTO + display) is a clean follow-on if/when price needs to appear on the phone or PDF.

Currency is RON, stored as a plain decimal number (no currency logic). Catalog remains **global** (no tenant scoping).

## 2. CSV format

```csv
distributor,product_name,aliases,price
Metro,Coca-Cola 2L,cola|coke|coca,8.50
Selgros,Lapte Zuzu 1.5%,lapte zuzu|lapte,5.20
```

- **Required columns:** `distributor`, `product_name`.
- **Optional columns:** `aliases` (pipe-separated synonyms), `price` (decimal; blank allowed).
- Header row **required**. UTF-8. Comma-separated. Standard CSV quoting so names containing commas work (parsed with Python `csv` module, not naive `split`).
- Column order is by header name, not position (tolerant to reordering / extra unknown columns, which are ignored).

## 3. Import behavior — upsert with a safety preview

**Match key:** `(distributor, product_name)`, both **case-insensitive and trimmed**.

- Distributor **auto-created** if its name is not already present.
- **Existing** product → update `aliases` and `price`.
- **New** product → insert.
- Products **absent from the file → left untouched** (import never deletes).
- **Idempotent:** re-uploading the same file produces 0 changes.

**Two-step commit (prevents accidents):**
1. Upload → server does a **dry run**: parse + validate, compute the effect, return a summary — e.g. `{ new: 12, updated: 5, unchanged: 40, errors: [{line: 7, reason: "missing product_name"}, {line: 9, reason: "invalid price 'abc'"}] }`. No DB writes.
2. Admin reviews the preview → clicks **Confirm** → server re-runs and **commits** in a transaction.

**Row-level errors:** invalid rows are **skipped and reported by line number**; valid rows still import (partial success). Whole-file rejection only for structural problems (missing required header, not a CSV, empty file).

**Limits:** file-size cap (e.g. 5 MB) and row-count cap (e.g. 10,000) to bound resource use.

## 4. Backend

- **Import endpoint:** `POST /api/admin/catalog/import` (admin JWT via `get_current_admin_user`), `multipart/form-data` CSV upload, query param `dry_run: bool = true`. Returns the preview (dry run) or commit result.
- **Page route:** `GET /admin` returns the admin HTML page. Static assets (JS/CSS) inlined or served from `/static`.
- **Table CRUD:** reuse the **existing admin-gated** catalog endpoints (list / create / update / delete products & distributors) — no new CRUD endpoints needed.
- **CORS:** none needed — the page is **same-origin** (served by the same FastAPI app).

**Module boundaries (backend):**
- `app/services/catalog_import.py` — pure-ish import logic: parse CSV → rows, validate, diff against DB, and (on commit) apply upserts. Returns a structured result. Unit-testable without HTTP.
- Endpoint in `app/main.py` (or a small router) is a thin wrapper: auth, read upload, call the service, return JSON.

## 5. The web page (v1)

Plain HTML + vanilla JS (no framework, no build step) served by FastAPI. Kept small and focused.

- **Login screen:** username/password → `POST /api/auth/login` → store JWT (in-memory + `localStorage` so a refresh stays logged in). All API calls send `Authorization: Bearer`.
- **Catalog table:** products grouped by distributor — columns: product name, aliases, price — with a client-side search box.
- **Import CSV:** file picker → calls import (dry run) → renders the **preview panel** (new / updated / unchanged / errors) → **Confirm** button commits → success toast + table refresh.
- The catalog table is **read-only in v1**. Adding products and updating price/aliases is done through **CSV upsert**, which covers the common cases. Inline edit / delete / manual add is a clean follow-on (reusing the existing admin CRUD endpoints) if needed later.

## 6. Android — deferred (no v1 change)

No Android change ships in v1. Gson ignores the new `price` field in the product JSON, so the phone keeps working unchanged; and the destructive-migration setup means a version bump would wipe the local cache for no user-visible gain. When `price` is later needed on the phone/PDF, adding it (entity + DTO + `toEntity()` mapping + display) is a self-contained follow-on that rides its own destructive migration.

## 7. Error handling summary

| Situation | Behavior |
|---|---|
| Missing required header (`distributor`/`product_name`) | Reject whole file, 400 with clear message |
| Not a CSV / empty file | Reject whole file, 400 |
| Row missing `product_name` | Skip row, report line number |
| Row with unparseable `price` | Skip row, report line + bad value |
| Unknown distributor | Auto-create |
| Duplicate rows within the same file | Last one wins; the second occurrence is counted as an update (a dedicated duplicate-in-file warning is a follow-on) |
| File too large / too many rows | Reject with limit message |
| Not admin | 403 (endpoint is admin-gated) |

## 8. Testing

- **Importer unit tests** (`catalog_import.py`, pytest, no HTTP): upsert updates existing, inserts new, leaves others untouched; case-insensitive/trimmed match key; distributor auto-create; malformed rows skipped+reported; dry-run makes no writes; commit is transactional; idempotency (same file twice = no change).
- **CSV parser tests:** quoted fields with commas, missing optional columns, blank price, invalid price, reordered/extra columns, in-file duplicates.
- **Endpoint test:** admin required (403 without/for non-admin), dry-run vs commit paths, partial-success response shape.
- **Migration:** backend Alembic upgrade applies cleanly on an existing DB (verified via the app's startup `alembic upgrade head`). No Android migration in v1.

## 9. Rollout notes

- Additive, nullable migrations → safe on the live Render DB and existing phones.
- Deployed as part of the existing backend service (no new hosting). `/admin` and `/api/admin/catalog/import` ship with the next backend deploy.
