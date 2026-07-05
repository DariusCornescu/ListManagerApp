# Admin Catalog Manager + CSV Import — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a backend-served admin web page and a CSV upsert importer so the whole product catalog (distributor, product name, aliases, price) can be loaded and updated in one upload.

**Architecture:** All work is in the FastAPI backend (`backend-fastapi/`). A pure CSV parser + a DB upsert service power a new admin-only endpoint `POST /api/admin/catalog/import` (two-step: `dry_run` preview → commit). A single self-contained HTML page served at `GET /admin` drives it from the browser. A nullable `price` column is added to `Product` (model + schema + Alembic migration). **No Android changes** — Gson ignores the new JSON field, so the phone is unaffected.

**Tech Stack:** FastAPI, SQLAlchemy, Alembic (autogenerate + batch mode), Pydantic v2, pytest, vanilla HTML/JS.

**Working directory for all commands:** `backend-fastapi/`
**Python invocation (broken pip in venv — always use the module form):** `.\venv\Scripts\python.exe -m <tool>`

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `backend-fastapi/app/models.py` | Add nullable `price` to `Product` | Modify |
| `backend-fastapi/app/schemas.py` | Add `price` to `ProductBase`; add import result DTOs | Modify |
| `backend-fastapi/alembic/versions/<rev>_add_product_price.py` | DB migration for `price` | Create (autogenerate) |
| `backend-fastapi/app/services/__init__.py` | Package marker | Create |
| `backend-fastapi/app/services/catalog_import.py` | Pure CSV parse + DB upsert logic | Create |
| `backend-fastapi/app/main.py` | `POST /api/admin/catalog/import` + `GET /admin` | Modify |
| `backend-fastapi/app/static/admin.html` | Self-contained admin page | Create |
| `backend-fastapi/tests/test_product_price.py` | `price` roundtrip through the API | Create |
| `backend-fastapi/tests/test_catalog_import_parser.py` | Parser unit tests (no DB) | Create |
| `backend-fastapi/tests/test_catalog_import_apply.py` | Upsert unit tests (db_session) | Create |
| `backend-fastapi/tests/test_admin_import_endpoint.py` | Endpoint + auth tests | Create |
| `backend-fastapi/tests/test_admin_page.py` | `GET /admin` served | Create |

---

## Task 1: Add `price` to Product (model + schema + migration)

**Files:**
- Modify: `backend-fastapi/app/models.py` (Product class + imports)
- Modify: `backend-fastapi/app/schemas.py` (ProductBase + imports)
- Create: `backend-fastapi/tests/test_product_price.py`
- Create: `backend-fastapi/alembic/versions/<rev>_add_product_price.py` (via autogenerate)

- [ ] **Step 1: Write the failing test**

Create `backend-fastapi/tests/test_product_price.py`:

```python
from fastapi import status


def test_create_product_with_price(client, admin_headers, sample_distributor):
    resp = client.post(
        "/api/catalog/products",
        json={
            "name": "Coca-Cola 2L",
            "distributor_id": sample_distributor.id,
            "aliases": "cola|coke",
            "price": 8.50,
        },
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_200_OK
    data = resp.json()
    assert data["name"] == "Coca-Cola 2L"
    # Pydantic v2 serializes Decimal to a JSON string (exact, 2-decimal).
    assert data["price"] == "8.50"


def test_create_product_without_price_defaults_null(client, admin_headers, sample_distributor):
    resp = client.post(
        "/api/catalog/products",
        json={"name": "Lapte", "distributor_id": sample_distributor.id, "aliases": None},
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_200_OK
    assert resp.json()["price"] is None


def test_negative_price_rejected(client, admin_headers, sample_distributor):
    resp = client.post(
        "/api/catalog/products",
        json={"name": "Bad", "distributor_id": sample_distributor.id, "price": -1},
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_product_price.py -v`
Expected: FAIL — `price` is not returned (KeyError / `data["price"]` missing) and `-1` is accepted (no 422).

- [ ] **Step 3: Add the `price` column to the model**

In `backend-fastapi/app/models.py`, add `Numeric` to the SQLAlchemy import line (it currently imports `Column, Integer, String, Boolean, ForeignKey, DateTime, Text, func, UniqueConstraint, CheckConstraint, Index`):

```python
from sqlalchemy import Column, Integer, String, Boolean, ForeignKey, DateTime, Text, func, UniqueConstraint, CheckConstraint, Index, Numeric
```

Then in the `Product` class, add the `price` column (after `aliases`):

```python
class Product(Base):
    __tablename__ = "products"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(200), nullable=False, index=True)
    distributor_id = Column(Integer, ForeignKey("distributors.id"), nullable=False)
    aliases = Column(String, nullable=True)
    price = Column(Numeric(10, 2), nullable=True)
    created_at = Column(DateTime, default=func.now())
    distributor = relationship("Distributor", back_populates="products")
```

- [ ] **Step 4: Add `price` to the product schema**

In `backend-fastapi/app/schemas.py`, ensure these imports exist at the top (add `Field` to the pydantic import if missing, and add the `Decimal` import):

```python
from decimal import Decimal
from pydantic import BaseModel, ConfigDict, Field
```

Add `price` to `ProductBase` (this flows into both `ProductCreate` and `ProductDTO`, which inherit it):

```python
class ProductBase(BaseModel):
    name: str
    distributor_id: int
    aliases: Optional[str] = None
    price: Optional[Decimal] = Field(default=None, ge=0)
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_product_price.py -v`
Expected: PASS (3 passed). Tests use `Base.metadata.create_all`, so the new column exists without a migration.

- [ ] **Step 6: Generate and apply the Alembic migration (for real DBs)**

Run: `.\venv\Scripts\python.exe -m alembic revision --autogenerate -m "add product price"`
Expected: a new file in `alembic/versions/` whose `upgrade()` adds the `price` column, e.g.:

```python
def upgrade():
    with op.batch_alter_table('products', schema=None) as batch_op:
        batch_op.add_column(sa.Column('price', sa.Numeric(precision=10, scale=2), nullable=True))


def downgrade():
    with op.batch_alter_table('products', schema=None) as batch_op:
        batch_op.drop_column('price')
```

Open the generated file and confirm it contains ONLY the `price` add/drop (delete any unrelated autogenerated churn). Then apply it:

Run: `.\venv\Scripts\python.exe -m alembic upgrade head`
Expected: `Running upgrade ... add product price`, no errors.

- [ ] **Step 7: Commit**

```bash
git add app/models.py app/schemas.py alembic/versions/ tests/test_product_price.py
git commit -m "feat: add nullable price to product catalog"
```

---

## Task 2: CSV parser (pure, no DB)

**Files:**
- Create: `backend-fastapi/app/services/__init__.py`
- Create: `backend-fastapi/app/services/catalog_import.py`
- Create: `backend-fastapi/tests/test_catalog_import_parser.py`

- [ ] **Step 1: Write the failing test**

Create `backend-fastapi/tests/test_catalog_import_parser.py`:

```python
from decimal import Decimal
from app.services.catalog_import import parse_catalog_csv


def test_parses_valid_rows():
    csv_text = "distributor,product_name,aliases,price\nMetro,Coca-Cola 2L,cola|coke,8.50\n"
    parsed = parse_catalog_csv(csv_text)
    assert parsed.header_error is None
    assert parsed.errors == []
    assert len(parsed.rows) == 1
    row = parsed.rows[0]
    assert row.distributor == "Metro"
    assert row.product_name == "Coca-Cola 2L"
    assert row.aliases == "cola|coke"
    assert row.price == Decimal("8.50")


def test_missing_required_header_is_header_error():
    parsed = parse_catalog_csv("product_name,price\nX,1\n")
    assert parsed.header_error is not None
    assert "distributor" in parsed.header_error
    assert parsed.rows == []


def test_empty_file_is_header_error():
    parsed = parse_catalog_csv("   ")
    assert parsed.header_error is not None


def test_blank_price_becomes_none():
    parsed = parse_catalog_csv("distributor,product_name,aliases,price\nMetro,Milk,,\n")
    assert parsed.rows[0].price is None


def test_invalid_price_is_row_error_line_2():
    parsed = parse_catalog_csv("distributor,product_name,price\nMetro,Milk,abc\n")
    assert parsed.rows == []
    assert len(parsed.errors) == 1
    assert parsed.errors[0].line == 2
    assert "invalid price" in parsed.errors[0].reason


def test_missing_product_name_is_row_error():
    parsed = parse_catalog_csv("distributor,product_name\nMetro,\n")
    assert parsed.rows == []
    assert parsed.errors[0].reason == "missing product_name"


def test_quoted_name_with_comma():
    parsed = parse_catalog_csv('distributor,product_name\nMetro,"Salt, coarse"\n')
    assert parsed.rows[0].product_name == "Salt, coarse"


def test_reordered_and_extra_columns_ignored():
    parsed = parse_catalog_csv("price,product_name,distributor,notes\n5,Milk,Metro,ignore-me\n")
    assert parsed.header_error is None
    assert parsed.rows[0].distributor == "Metro"
    assert parsed.rows[0].price == Decimal("5.00")


def test_bom_prefix_is_stripped():
    parsed = parse_catalog_csv("﻿distributor,product_name\nMetro,Milk\n")
    assert parsed.header_error is None
    assert parsed.rows[0].product_name == "Milk"
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_catalog_import_parser.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.services.catalog_import'`.

- [ ] **Step 3: Create the package and parser**

Create `backend-fastapi/app/services/__init__.py` (empty file):

```python
```

Create `backend-fastapi/app/services/catalog_import.py`:

```python
"""CSV catalog import — pure parsing + DB upsert.

The parser is DB-free and fully unit-testable. `apply_catalog_import` performs
an upsert keyed on (distributor, product_name), case-insensitive and trimmed,
auto-creating distributors. A dry run makes no persistent writes.
"""
from __future__ import annotations

import csv
import io
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from typing import Optional

REQUIRED_COLUMNS = ("distributor", "product_name")


@dataclass
class ParsedRow:
    line: int
    distributor: str
    product_name: str
    aliases: Optional[str]
    price: Optional[Decimal]


@dataclass
class RowError:
    line: int
    reason: str


@dataclass
class ParsedCsv:
    rows: list[ParsedRow] = field(default_factory=list)
    errors: list[RowError] = field(default_factory=list)
    header_error: Optional[str] = None


def parse_catalog_csv(content: str) -> ParsedCsv:
    result = ParsedCsv()
    text = content.lstrip("﻿")  # strip UTF-8 BOM if present
    if not text.strip():
        result.header_error = "File is empty"
        return result

    reader = csv.DictReader(io.StringIO(text))
    if reader.fieldnames is None:
        result.header_error = "File is empty"
        return result

    headers = {(h or "").strip().lower() for h in reader.fieldnames}
    missing = [c for c in REQUIRED_COLUMNS if c not in headers]
    if missing:
        result.header_error = f"Missing required column(s): {', '.join(missing)}"
        return result

    # DictReader: header is line 1, so first data row is line 2.
    for line, raw in enumerate(reader, start=2):
        row = {(k or "").strip().lower(): (v or "").strip() for k, v in raw.items()}
        distributor = row.get("distributor", "")
        product_name = row.get("product_name", "")
        if not distributor:
            result.errors.append(RowError(line, "missing distributor"))
            continue
        if not product_name:
            result.errors.append(RowError(line, "missing product_name"))
            continue

        price: Optional[Decimal] = None
        price_raw = row.get("price", "")
        if price_raw:
            try:
                price = Decimal(price_raw.replace(",", "."))
            except (InvalidOperation, ValueError):
                result.errors.append(RowError(line, f"invalid price '{price_raw}'"))
                continue
            if price < 0:
                result.errors.append(RowError(line, f"negative price '{price_raw}'"))
                continue
            price = price.quantize(Decimal("0.01"))

        aliases = row.get("aliases", "") or None
        result.rows.append(ParsedRow(line, distributor, product_name, aliases, price))

    return result
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_catalog_import_parser.py -v`
Expected: PASS (9 passed).

- [ ] **Step 5: Commit**

```bash
git add app/services/__init__.py app/services/catalog_import.py tests/test_catalog_import_parser.py
git commit -m "feat: add CSV catalog parser"
```

---

## Task 3: Upsert apply (DB)

**Files:**
- Modify: `backend-fastapi/app/services/catalog_import.py` (add `ImportResult`, `apply_catalog_import`, `import_catalog_csv`, `CatalogImportError`)
- Create: `backend-fastapi/tests/test_catalog_import_apply.py`

- [ ] **Step 1: Write the failing test**

Create `backend-fastapi/tests/test_catalog_import_apply.py`:

```python
from decimal import Decimal
from app import models
from app.services.catalog_import import (
    parse_catalog_csv,
    apply_catalog_import,
    import_catalog_csv,
    CatalogImportError,
)


def _count_products(db):
    return db.query(models.Product).count()


def test_insert_new_creates_distributor_and_product(db_session):
    parsed = parse_catalog_csv("distributor,product_name,aliases,price\nMetro,Milk,lapte,5.00\n")
    result = apply_catalog_import(db_session, parsed, commit=True)
    assert (result.new, result.updated, result.unchanged) == (1, 0, 0)
    assert result.committed is True
    prod = db_session.query(models.Product).one()
    assert prod.name == "Milk"
    assert prod.price == Decimal("5.00")
    assert prod.distributor.distributor_name == "Metro"


def test_reupload_same_file_is_unchanged(db_session):
    csv_text = "distributor,product_name,price\nMetro,Milk,5.00\n"
    apply_catalog_import(db_session, parse_catalog_csv(csv_text), commit=True)
    result = apply_catalog_import(db_session, parse_catalog_csv(csv_text), commit=True)
    assert (result.new, result.updated, result.unchanged) == (0, 0, 1)


def test_price_change_updates(db_session):
    apply_catalog_import(db_session, parse_catalog_csv("distributor,product_name,price\nMetro,Milk,5.00\n"), commit=True)
    result = apply_catalog_import(db_session, parse_catalog_csv("distributor,product_name,price\nMetro,Milk,6.50\n"), commit=True)
    assert (result.new, result.updated, result.unchanged) == (0, 1, 0)
    assert db_session.query(models.Product).one().price == Decimal("6.50")


def test_match_is_case_insensitive(db_session):
    apply_catalog_import(db_session, parse_catalog_csv("distributor,product_name,price\nMetro,Milk,5.00\n"), commit=True)
    result = apply_catalog_import(db_session, parse_catalog_csv("distributor,product_name,price\nmetro,milk,5.00\n"), commit=True)
    assert result.unchanged == 1
    assert _count_products(db_session) == 1


def test_products_absent_from_file_are_untouched(db_session):
    apply_catalog_import(db_session, parse_catalog_csv("distributor,product_name\nMetro,Milk\nMetro,Bread\n"), commit=True)
    result = apply_catalog_import(db_session, parse_catalog_csv("distributor,product_name\nMetro,Milk\n"), commit=True)
    assert _count_products(db_session) == 2  # Bread not deleted
    assert result.new == 0


def test_dry_run_makes_no_writes(db_session):
    parsed = parse_catalog_csv("distributor,product_name,price\nMetro,Milk,5.00\n")
    result = apply_catalog_import(db_session, parsed, commit=False)
    assert result.new == 1
    assert result.committed is False
    assert _count_products(db_session) == 0  # rolled back


def test_parse_errors_are_carried_through(db_session):
    parsed = parse_catalog_csv("distributor,product_name,price\nMetro,Milk,abc\nMetro,Bread,2.00\n")
    result = apply_catalog_import(db_session, parsed, commit=True)
    assert result.new == 1  # Bread imported
    assert len(result.errors) == 1  # Milk's bad price reported


def test_missing_header_raises(db_session):
    import pytest
    with pytest.raises(CatalogImportError):
        import_catalog_csv(db_session, "product_name\nMilk\n", commit=True)
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_catalog_import_apply.py -v`
Expected: FAIL — `ImportError: cannot import name 'apply_catalog_import'`.

- [ ] **Step 3: Add the upsert logic**

Append to `backend-fastapi/app/services/catalog_import.py`:

```python
from sqlalchemy.orm import Session

from .. import models


class CatalogImportError(Exception):
    """Structural problem with the file (bad/missing header, empty)."""


@dataclass
class ImportResult:
    new: int = 0
    updated: int = 0
    unchanged: int = 0
    committed: bool = False
    errors: list[RowError] = field(default_factory=list)


def _norm(value: str) -> str:
    return value.strip().lower()


def apply_catalog_import(db: Session, parsed: ParsedCsv, commit: bool) -> ImportResult:
    result = ImportResult(errors=list(parsed.errors))

    dist_by_name = {_norm(d.distributor_name): d for d in db.query(models.Distributor).all()}
    prod_by_key = {(p.distributor_id, _norm(p.name)): p for p in db.query(models.Product).all()}

    for row in parsed.rows:
        dkey = _norm(row.distributor)
        dist = dist_by_name.get(dkey)
        if dist is None:
            dist = models.Distributor(distributor_name=row.distributor.strip())
            db.add(dist)
            db.flush()  # assign id so later rows match this distributor
            dist_by_name[dkey] = dist

        pkey = (dist.id, _norm(row.product_name))
        existing = prod_by_key.get(pkey)
        if existing is None:
            product = models.Product(
                name=row.product_name.strip(),
                distributor_id=dist.id,
                aliases=row.aliases,
                price=row.price,
            )
            db.add(product)
            db.flush()
            prod_by_key[pkey] = product
            result.new += 1
        elif existing.aliases != row.aliases or existing.price != row.price:
            existing.aliases = row.aliases
            existing.price = row.price
            result.updated += 1
        else:
            result.unchanged += 1

    if commit:
        db.commit()
        result.committed = True
    else:
        db.rollback()  # discard all flushed inserts/updates — true dry run
        result.committed = False

    return result


def import_catalog_csv(db: Session, content: str, commit: bool) -> ImportResult:
    parsed = parse_catalog_csv(content)
    if parsed.header_error:
        raise CatalogImportError(parsed.header_error)
    return apply_catalog_import(db, parsed, commit)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_catalog_import_apply.py -v`
Expected: PASS (8 passed).

- [ ] **Step 5: Commit**

```bash
git add app/services/catalog_import.py tests/test_catalog_import_apply.py
git commit -m "feat: add CSV catalog upsert with dry-run"
```

---

## Task 4: Import endpoint + result DTOs

**Files:**
- Modify: `backend-fastapi/app/schemas.py` (add `ImportRowErrorDTO`, `ImportResultDTO`)
- Modify: `backend-fastapi/app/main.py` (add the endpoint + a module constant)
- Create: `backend-fastapi/tests/test_admin_import_endpoint.py`

- [ ] **Step 1: Write the failing test**

Create `backend-fastapi/tests/test_admin_import_endpoint.py`:

```python
from fastapi import status

CSV = b"distributor,product_name,aliases,price\nMetro,Milk,lapte,5.00\nMetro,Bread,,2.00\n"


def _files(content=CSV, name="catalog.csv"):
    return {"file": (name, content, "text/csv")}


def test_requires_admin(client, auth_headers):
    resp = client.post("/api/admin/catalog/import", files=_files(), headers=auth_headers)
    assert resp.status_code == status.HTTP_403_FORBIDDEN


def test_rejects_anonymous(client):
    resp = client.post("/api/admin/catalog/import", files=_files())
    assert resp.status_code in (status.HTTP_401_UNAUTHORIZED, status.HTTP_403_FORBIDDEN)


def test_dry_run_previews_without_writing(client, admin_headers):
    resp = client.post(
        "/api/admin/catalog/import",
        files=_files(),
        params={"dry_run": "true"},
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_200_OK
    body = resp.json()
    assert body["new"] == 2
    assert body["committed"] is False
    # nothing persisted
    assert client.get("/api/catalog/products").json() == []


def test_commit_persists(client, admin_headers):
    resp = client.post(
        "/api/admin/catalog/import",
        files=_files(),
        params={"dry_run": "false"},
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_200_OK
    assert resp.json()["committed"] is True
    names = {p["name"] for p in client.get("/api/catalog/products").json()}
    assert names == {"Milk", "Bread"}


def test_partial_errors_reported(client, admin_headers):
    bad = b"distributor,product_name,price\nMetro,Milk,abc\nMetro,Bread,2.00\n"
    resp = client.post(
        "/api/admin/catalog/import",
        files=_files(content=bad),
        params={"dry_run": "false"},
        headers=admin_headers,
    )
    body = resp.json()
    assert body["new"] == 1
    assert len(body["errors"]) == 1
    assert body["errors"][0]["line"] == 2


def test_missing_header_is_400(client, admin_headers):
    resp = client.post(
        "/api/admin/catalog/import",
        files=_files(content=b"product_name\nMilk\n"),
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_400_BAD_REQUEST


def test_oversize_rejected(client, admin_headers, monkeypatch):
    from app import main
    monkeypatch.setattr(main, "MAX_IMPORT_BYTES", 10)
    resp = client.post(
        "/api/admin/catalog/import",
        files=_files(content=b"distributor,product_name\nMetro,Milk\n"),
        headers=admin_headers,
    )
    assert resp.status_code == status.HTTP_413_REQUEST_ENTITY_TOO_LARGE
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_admin_import_endpoint.py -v`
Expected: FAIL — endpoint returns 404 (route not defined).

- [ ] **Step 3: Add the result DTOs**

In `backend-fastapi/app/schemas.py`, append:

```python
class ImportRowErrorDTO(BaseModel):
    line: int
    reason: str


class ImportResultDTO(BaseModel):
    new: int
    updated: int
    unchanged: int
    committed: bool
    errors: list[ImportRowErrorDTO]
```

- [ ] **Step 4: Add the endpoint**

In `backend-fastapi/app/main.py`, add this import near the other `.services`/local imports:

```python
from .services.catalog_import import import_catalog_csv, CatalogImportError
```

Add a module-level constant near the top of the file (after `app = FastAPI(...)` block is fine):

```python
MAX_IMPORT_BYTES = 5 * 1024 * 1024  # 5 MB cap on catalog CSV uploads
```

Add the endpoint (place it near the other `/api/catalog/*` handlers):

```python
@app.post("/api/admin/catalog/import", response_model=schemas.ImportResultDTO)
async def import_catalog(
    file: UploadFile = File(...),
    dry_run: bool = True,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user),
):
    """Upsert the product catalog from a CSV (admin only).

    dry_run=True (default) returns a preview without writing; dry_run=False commits.
    """
    raw = await file.read()
    if len(raw) > MAX_IMPORT_BYTES:
        raise HTTPException(status_code=413, detail="File too large (max 5 MB)")
    try:
        content = raw.decode("utf-8")
    except UnicodeDecodeError:
        raise HTTPException(status_code=400, detail="File must be UTF-8 encoded CSV")

    try:
        result = import_catalog_csv(db, content, commit=not dry_run)
    except CatalogImportError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    return schemas.ImportResultDTO(
        new=result.new,
        updated=result.updated,
        unchanged=result.unchanged,
        committed=result.committed,
        errors=[schemas.ImportRowErrorDTO(line=e.line, reason=e.reason) for e in result.errors],
    )
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_admin_import_endpoint.py -v`
Expected: PASS (7 passed).

- [ ] **Step 6: Commit**

```bash
git add app/schemas.py app/main.py tests/test_admin_import_endpoint.py
git commit -m "feat: add admin catalog CSV import endpoint"
```

---

## Task 5: Admin web page (`GET /admin`)

**Files:**
- Create: `backend-fastapi/app/static/admin.html`
- Modify: `backend-fastapi/app/main.py` (add the `/admin` route + import)
- Create: `backend-fastapi/tests/test_admin_page.py`

- [ ] **Step 1: Write the failing test**

Create `backend-fastapi/tests/test_admin_page.py`:

```python
from fastapi import status


def test_admin_page_served(client):
    resp = client.get("/admin")
    assert resp.status_code == status.HTTP_200_OK
    assert "text/html" in resp.headers["content-type"]
    body = resp.text
    assert "Catalog" in body
    assert "Import CSV" in body
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_admin_page.py -v`
Expected: FAIL — 404 (route not defined).

- [ ] **Step 3: Create the admin page**

Create `backend-fastapi/app/static/admin.html`:

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Catalog Admin</title>
  <style>
    :root { --gold:#DDA92B; --charcoal:#2A2A2A; --cream:#F6F2E9; --sage:#71805C; }
    * { box-sizing: border-box; }
    body { font-family: system-ui, sans-serif; margin:0; background:var(--cream); color:var(--charcoal); }
    header { background:var(--charcoal); color:var(--cream); padding:14px 20px; font-weight:700; }
    main { max-width:900px; margin:0 auto; padding:20px; }
    .card { background:#fff; border:1px solid #DAD3C4; border-radius:12px; padding:16px; margin-bottom:16px; }
    input, button { font-size:15px; padding:8px 10px; border-radius:8px; border:1px solid #DAD3C4; }
    button { background:var(--gold); color:var(--charcoal); border:none; cursor:pointer; font-weight:600; }
    button.secondary { background:var(--sage); color:#fff; }
    button:disabled { opacity:.5; cursor:not-allowed; }
    table { width:100%; border-collapse:collapse; }
    th, td { text-align:left; padding:6px 8px; border-bottom:1px solid #EDE8DC; }
    .hidden { display:none; }
    .err { color:#C4553F; }
    .muted { color:#6E6A60; font-size:13px; }
    #preview { white-space:pre-wrap; }
  </style>
</head>
<body>
  <header>List Manager — Catalog Admin</header>
  <main>
    <div id="loginCard" class="card">
      <h3>Sign in</h3>
      <div><input id="username" placeholder="username" /></div>
      <div style="margin-top:8px"><input id="password" type="password" placeholder="password" /></div>
      <div style="margin-top:8px"><button onclick="login()">Login</button></div>
      <div id="loginErr" class="err"></div>
    </div>

    <div id="appCard" class="hidden">
      <div class="card">
        <h3>Import CSV</h3>
        <p class="muted">Columns: <code>distributor, product_name, aliases, price</code>. First two required. Re-upload is an upsert (updates + adds, never deletes).</p>
        <input type="file" id="csvFile" accept=".csv" />
        <button onclick="preview()">Preview</button>
        <button class="secondary" id="confirmBtn" onclick="commit()" disabled>Confirm import</button>
        <div id="preview" class="muted"></div>
      </div>
      <div class="card">
        <h3>Catalog</h3>
        <input id="search" placeholder="filter…" oninput="renderTable()" />
        <button onclick="loadProducts()">Refresh</button>
        <table><thead><tr><th>Product</th><th>Distributor</th><th>Aliases</th><th>Price</th></tr></thead>
          <tbody id="rows"></tbody></table>
      </div>
    </div>
  </main>

  <script>
    let token = localStorage.getItem("lm_token") || null;
    let products = [], distributors = {};

    function authHeaders() { return { "Authorization": "Bearer " + token }; }
    function showApp(on) {
      document.getElementById("loginCard").classList.toggle("hidden", on);
      document.getElementById("appCard").classList.toggle("hidden", !on);
    }

    async function login() {
      const username = document.getElementById("username").value;
      const password = document.getElementById("password").value;
      const r = await fetch("/api/auth/login", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password })
      });
      if (!r.ok) { document.getElementById("loginErr").textContent = "Login failed"; return; }
      token = (await r.json()).access_token;
      localStorage.setItem("lm_token", token);
      showApp(true); loadProducts();
    }

    async function importCsv(dryRun) {
      const f = document.getElementById("csvFile").files[0];
      if (!f) { alert("Choose a CSV file first"); return null; }
      const fd = new FormData(); fd.append("file", f);
      const r = await fetch("/api/admin/catalog/import?dry_run=" + dryRun, {
        method: "POST", headers: authHeaders(), body: fd
      });
      if (r.status === 401 || r.status === 403) { showApp(false); return null; }
      const body = await r.json();
      if (!r.ok) { document.getElementById("preview").innerHTML = '<span class="err">' + (body.detail || "Error") + '</span>'; return null; }
      return body;
    }

    async function preview() {
      const b = await importCsv(true);
      if (!b) return;
      let html = `New: ${b.new} · Updated: ${b.updated} · Unchanged: ${b.unchanged}`;
      if (b.errors.length) html += `<br><span class="err">${b.errors.length} row(s) skipped: ` +
        b.errors.map(e => `line ${e.line}: ${e.reason}`).join("; ") + "</span>";
      document.getElementById("preview").innerHTML = html;
      document.getElementById("confirmBtn").disabled = false;
    }

    async function commit() {
      const b = await importCsv(false);
      if (!b) return;
      document.getElementById("preview").innerHTML =
        `<b>Imported.</b> New: ${b.new} · Updated: ${b.updated} · Unchanged: ${b.unchanged}`;
      document.getElementById("confirmBtn").disabled = true;
      loadProducts();
    }

    async function loadProducts() {
      const [pr, dr] = await Promise.all([
        fetch("/api/catalog/products").then(r => r.json()),
        fetch("/api/catalog/distributors").then(r => r.json())
      ]);
      products = pr;
      distributors = {};
      dr.forEach(d => distributors[d.id] = d.distributor_name);
      renderTable();
    }

    function renderTable() {
      const q = (document.getElementById("search").value || "").toLowerCase();
      const tbody = document.getElementById("rows");
      tbody.innerHTML = "";
      products
        .filter(p => !q || p.name.toLowerCase().includes(q))
        .forEach(p => {
          const tr = document.createElement("tr");
          tr.innerHTML = `<td>${p.name}</td><td>${distributors[p.distributor_id] || ""}</td>` +
            `<td>${p.aliases || ""}</td><td>${p.price == null ? "" : p.price}</td>`;
          tbody.appendChild(tr);
        });
    }

    if (token) { showApp(true); loadProducts(); } else { showApp(false); }
  </script>
</body>
</html>
```

- [ ] **Step 4: Add the `/admin` route**

In `backend-fastapi/app/main.py`, add the import:

```python
from fastapi.responses import HTMLResponse
```

Add the route (near the other top-level routes):

```python
_ADMIN_HTML_PATH = os.path.join(os.path.dirname(__file__), "static", "admin.html")


@app.get("/admin", response_class=HTMLResponse)
def admin_page():
    """Serve the self-contained catalog admin page."""
    with open(_ADMIN_HTML_PATH, encoding="utf-8") as f:
        return f.read()
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_admin_page.py -v`
Expected: PASS (1 passed).

- [ ] **Step 6: Commit**

```bash
git add app/static/admin.html app/main.py tests/test_admin_page.py
git commit -m "feat: add catalog admin web page"
```

---

## Task 6: Full-suite verification + manual check

**Files:** none (verification only)

- [ ] **Step 1: Run the entire backend test suite**

Run: `.\venv\Scripts\python.exe -m pytest -q`
Expected: all tests pass (existing suite + the new files). If any pre-existing test broke, investigate before proceeding.

- [ ] **Step 2: Manual smoke test of the page**

Run the server locally (`.\venv\Scripts\python.exe -m uvicorn app.main:app --reload`), open `http://localhost:8000/admin`, log in as admin, upload a small CSV:

```csv
distributor,product_name,aliases,price
Metro,Coca-Cola 2L,cola|coke,8.50
Selgros,Lapte Zuzu,lapte,5.20
```

Verify: Preview shows `New: 2`; Confirm imports; the Catalog table lists both with prices. Re-upload the same file → Preview shows `Unchanged: 2`.

- [ ] **Step 3: Confirm the migration is current**

Run: `.\venv\Scripts\python.exe -m alembic upgrade head` (idempotent) and `.\venv\Scripts\python.exe -m alembic current` — confirm head includes the `add product price` revision.

- [ ] **Step 4: Commit any final touch-ups, then hand off**

The feature is complete when: full suite green, `/admin` works end-to-end locally, and the migration is at head. Deployment rides the normal backend deploy (Render runs `alembic upgrade head` at startup via `_run_migrations_to_head()`).

---

## Notes for the implementer

- **DRY:** the endpoint reuses `import_catalog_csv`; the page reuses the existing `/api/catalog/products` and `/api/catalog/distributors` GET endpoints — no new list endpoints.
- **YAGNI / no Android:** do not touch `android-native/`. Gson ignores the new `price` JSON field, so the phone is unaffected; a Room version bump would destructively wipe the local cache for zero v1 benefit.
- **Decimal:** with a `response_model`, Pydantic v2 serializes `Decimal` to a JSON **string** (e.g. `"8.50"`), not a float. This is the intended price contract — exact and 2-decimal; the admin table displays it directly and a future Gson client parses it fine. Tests assert the string form.
- **Dry-run safety:** `apply_catalog_import` flushes to assign ids during matching, then `rollback()`s when `commit=False`; nothing persists.

## Post-review hardening (applied during execution)

Code-quality review surfaced fixes that were folded in beyond the base task code:
- **Parser (Task 2):** wrapped per-row processing in try/except → `RowError` (a malformed row never crashes the batch); drop `csv.DictReader`'s `None` overflow bucket (ragged rows); reject non-finite prices (`NaN`/`Infinity`) via `is_finite()`; reject duplicate header columns.
- **Upsert (Task 3):** documented the in-file duplicate = last-wins behavior on `ImportResult` and pinned it with a test; moved imports to the top.
- **Endpoint (Task 4):** `MAX_IMPORT_BYTES` moved into `Settings` (`settings.MAX_IMPORT_BYTES`, interpolated in the 413 message); added `@limiter.limit("5/minute")`; added an `except Exception → rollback + 500` guard so DB failures return a clean response.
- **Page (Task 5):** render catalog cells via `textContent` and escape error/detail strings (closes stored-XSS from operator CSV free-text); capture the previewed file so Confirm can't upload a different file; visible message on catalog-load failure; Enter-to-submit on login.
