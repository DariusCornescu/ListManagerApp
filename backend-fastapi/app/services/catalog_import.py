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

    fieldnames = [(h or "").strip().lower() for h in reader.fieldnames]
    duplicates = sorted({h for h in fieldnames if h and fieldnames.count(h) > 1})
    if duplicates:
        result.header_error = f"Duplicate column(s): {', '.join(duplicates)}"
        return result
    headers = set(fieldnames)
    missing = [c for c in REQUIRED_COLUMNS if c not in headers]
    if missing:
        result.header_error = f"Missing required column(s): {', '.join(missing)}"
        return result

    # DictReader: header is line 1, so first data row is line 2.
    for line, raw in enumerate(reader, start=2):
        try:
            # `k is not None` drops csv.DictReader's overflow bucket (extra cells
            # in a ragged row land under the None key as a list); non-str values
            # are coerced to "" defensively.
            row = {
                (k or "").strip().lower(): (v.strip() if isinstance(v, str) else "")
                for k, v in raw.items()
                if k is not None
            }
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
                if not price.is_finite():  # rejects NaN / Infinity (valid Decimal input)
                    result.errors.append(RowError(line, f"invalid price '{price_raw}'"))
                    continue
                if price < 0:
                    result.errors.append(RowError(line, f"negative price '{price_raw}'"))
                    continue
                price = price.quantize(Decimal("0.01"))

            aliases = row.get("aliases", "") or None
            result.rows.append(ParsedRow(line, distributor, product_name, aliases, price))
        except Exception:
            result.errors.append(RowError(line, "could not parse row"))

    return result


from sqlalchemy.orm import Session  # noqa: E402

from .. import models  # noqa: E402


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
