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
