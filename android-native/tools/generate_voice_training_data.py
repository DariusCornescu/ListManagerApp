"""Generate deterministic Romanian text-product pairs with hard negatives."""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
import sqlite3
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


SCHEMA_VERSION = 1
GENERATOR_VERSION = "1.0.0"
DEFAULT_SEED = 240513

QUANTITIES = (
    "un", "doua", "trei", "patru", "cinci", "sase", "sapte", "opt", "noua", "zece",
    "unsprezece", "doisprezece", "treisprezece", "paisprezece", "cincisprezece",
    "saisprezece", "saptesprezece", "optsprezece", "nouasprezece", "douazeci",
)

TEMPLATES = (
    ("bare", "{product}"),
    ("quantity", "{quantity} {product}"),
    ("put", "pune {quantity} {product}"),
    ("polite_put", "te rog pune {quantity} {product}"),
    ("add", "adauga {quantity} {product}"),
    ("want", "vreau {quantity} {product}"),
    ("would_like", "as vrea {quantity} {product}"),
    ("add_more", "mai adauga {quantity} {product}"),
    ("need", "avem nevoie de {quantity} {product}"),
    ("need_personal", "imi trebuie {quantity} {product}"),
    ("pieces", "{quantity} bucati de {product}"),
    ("boxes", "{quantity} cutii de {product}"),
    ("note", "noteaza {quantity} {product}"),
    ("can_put", "poti sa pui {quantity} {product}"),
    ("list", "pentru lista {quantity} {product}"),
)

PROFILES = ("clean", "accentless", "digit_quantity", "asr_spacing")


class DatasetError(ValueError):
    pass


@dataclass(frozen=True)
class Product:
    id: int
    name: str
    aliases: tuple[str, ...]

    @property
    def candidate_text(self) -> str:
        return " | ".join((self.name, *self.aliases))


def _text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise DatasetError(f"{field} must be non-empty text")
    return value.strip()


def _product(row: dict[str, Any]) -> Product:
    try:
        product_id = int(row["id"])
    except (KeyError, TypeError, ValueError) as exc:
        raise DatasetError("each product needs an integer id") from exc
    name = _text(row.get("name"), f"product {product_id} name")
    raw_aliases = row.get("aliases") or ""
    if isinstance(raw_aliases, str):
        aliases = tuple(a.strip() for a in raw_aliases.split(",") if a.strip())
    elif isinstance(raw_aliases, list):
        aliases = tuple(_text(a, "alias") for a in raw_aliases)
    else:
        raise DatasetError(f"product {product_id} aliases must be text or an array")
    return Product(product_id, name, aliases)


def load_catalog(path: Path) -> list[Product]:
    if path.suffix.lower() in {".db", ".sqlite", ".sqlite3"}:
        uri = f"file:{path.resolve().as_posix()}?mode=ro"
        db = sqlite3.connect(uri, uri=True)
        try:
            rows = db.execute("SELECT id, name, aliases FROM products ORDER BY id").fetchall()
        finally:
            db.close()
        products = [_product({"id": i, "name": n, "aliases": a}) for i, n, a in rows]
    else:
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise DatasetError(f"cannot read catalog: {exc}") from exc
        rows = payload.get("products") if isinstance(payload, dict) else payload
        if not isinstance(rows, list):
            raise DatasetError("JSON catalog must be an array or contain a products array")
        products = [_product(row) for row in rows]
    if len(products) < 2:
        raise DatasetError("at least two products are required for hard negatives")
    if len({p.id for p in products}) != len(products):
        raise DatasetError("product ids must be unique")
    return products


def normalize(text: str) -> str:
    plain = "".join(
        char for char in unicodedata.normalize("NFD", text.lower())
        if unicodedata.category(char) != "Mn"
    )
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9]+", " ", plain)).strip()


def _augment(text: str, profile: str, quantity_index: int) -> str:
    if profile == "clean":
        return re.sub(r"\s+", " ", text).strip()
    if profile == "accentless":
        return normalize(text)
    if profile == "digit_quantity":
        words = text.split()
        return " ".join(str(quantity_index + 1) if word == QUANTITIES[quantity_index] else word for word in words)
    if profile == "asr_spacing":
        return re.sub(r"(?<=\d)\s*[x×]\s*(?=\d)", " pe ", normalize(text))
    raise DatasetError(f"unknown augmentation profile {profile}")


def _split_by_product(products: list[Product], seed: int) -> dict[int, str]:
    ids = [product.id for product in products]
    random.Random(seed).shuffle(ids)
    if len(ids) == 2:
        return {ids[0]: "train", ids[1]: "test"}
    validation_count = max(1, round(len(ids) * 0.1))
    test_count = max(1, round(len(ids) * 0.1))
    train_count = max(1, len(ids) - validation_count - test_count)
    return {
        product_id: (
            "train" if index < train_count
            else "validation" if index < train_count + validation_count
            else "test"
        )
        for index, product_id in enumerate(ids)
    }


def _negative_score(target: Product, candidate: Product) -> tuple[float, str]:
    left = normalize(target.candidate_text)
    right = normalize(candidate.candidate_text)
    left_tokens, right_tokens = set(left.split()), set(right.split())
    left_name = re.sub(r"\d+", "", normalize(target.name))
    right_name = re.sub(r"\d+", "", normalize(candidate.name))
    shared = len(left_tokens & right_tokens)
    union = len(left_tokens | right_tokens) or 1
    numeric_variant = (
        left_name == right_name
        and re.findall(r"\d+", normalize(target.name))
        != re.findall(r"\d+", normalize(candidate.name))
    )
    same_family = bool(left_tokens and right_tokens and left.split()[0] == right.split()[0])
    score = shared / union + (2.0 if numeric_variant else 0.0) + (1.0 if same_family else 0.0)
    reason = "numeric_variant" if numeric_variant else "same_family" if same_family else "lexical_neighbor"
    return score, reason


def _hard_negatives(target: Product, products: list[Product], count: int) -> list[tuple[Product, str]]:
    ranked = sorted(
        ((candidate, *_negative_score(target, candidate)) for candidate in products if candidate.id != target.id),
        key=lambda item: (-item[1], item[0].id),
    )
    return [(candidate, reason) for candidate, _, reason in ranked[:count]]


def _positive_candidates(product: Product, seed: int) -> list[tuple[str, str, str]]:
    variants = tuple(dict.fromkeys((product.name, *product.aliases)))
    candidates: list[tuple[str, str, str]] = []
    seen: set[str] = set()
    for variant in variants:
        for quantity_index, quantity in enumerate(QUANTITIES):
            for template_id, template in TEMPLATES:
                rendered = template.format(product=variant, quantity=quantity)
                for profile in PROFILES:
                    utterance = _augment(rendered, profile, quantity_index)
                    key = normalize(utterance)
                    if not key or key in seen:
                        continue
                    seen.add(key)
                    candidates.append((utterance, template_id, profile))
    random.Random(f"{seed}:{product.id}").shuffle(candidates)
    return candidates


def generate_dataset(
    products: list[Product],
    *,
    positive_count: int = 2500,
    negatives_per_positive: int = 2,
    seed: int = DEFAULT_SEED,
) -> dict[str, Any]:
    if positive_count < 1:
        raise DatasetError("positive_count must be positive")
    if negatives_per_positive < 1 or negatives_per_positive >= len(products):
        raise DatasetError("negatives_per_positive must be between 1 and catalog size - 1")
    splits = _split_by_product(products, seed)
    pools = {product.id: _positive_candidates(product, seed) for product in products}
    if sum(map(len, pools.values())) < positive_count:
        raise DatasetError("catalog and templates cannot produce the requested number of unique pairs")

    selected: list[tuple[Product, tuple[str, str, str]]] = []
    offsets = {product.id: 0 for product in products}
    while len(selected) < positive_count:
        made_progress = False
        for product in products:
            offset = offsets[product.id]
            if offset >= len(pools[product.id]):
                continue
            selected.append((product, pools[product.id][offset]))
            offsets[product.id] += 1
            made_progress = True
            if len(selected) == positive_count:
                break
        if not made_progress:
            raise DatasetError("ran out of unique positive pairs")

    examples: list[dict[str, Any]] = []
    for index, (target, (utterance, template_id, profile)) in enumerate(selected, start=1):
        group_id = f"synthetic-ro-{index:06d}"
        common = {
            "group_id": group_id,
            "split": splits[target.id],
            "utterance": utterance,
            "target_product_id": target.id,
            "template_id": template_id,
            "augmentation": profile,
            "locale": "ro-RO",
            "source": "synthetic",
        }
        examples.append({
            **common,
            "id": f"{group_id}-positive",
            "candidate_product_id": target.id,
            "candidate_text": target.candidate_text,
            "label": 1,
            "pair_type": "positive",
        })
        for negative_index, (negative, reason) in enumerate(
            _hard_negatives(target, products, negatives_per_positive), start=1
        ):
            examples.append({
                **common,
                "id": f"{group_id}-negative-{negative_index}",
                "candidate_product_id": negative.id,
                "candidate_text": negative.candidate_text,
                "label": 0,
                "pair_type": "hard_negative",
                "negative_reason": reason,
            })

    canonical_catalog = [
        {"id": p.id, "name": p.name, "aliases": list(p.aliases)} for p in products
    ]
    catalog_hash = hashlib.sha256(
        json.dumps(canonical_catalog, ensure_ascii=False, sort_keys=True).encode("utf-8")
    ).hexdigest()
    return {
        "schema_version": SCHEMA_VERSION,
        "generator_version": GENERATOR_VERSION,
        "seed": seed,
        "locale": "ro-RO",
        "data_policy": "synthetic-catalog-derived-no-audio-no-user-text",
        "split_strategy": "product-disjoint-80-10-10",
        "catalog_sha256": catalog_hash,
        "positive_count": positive_count,
        "hard_negative_count": positive_count * negatives_per_positive,
        "products": canonical_catalog,
        "examples": examples,
    }


def write_dataset(dataset: dict[str, Any], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(dataset, ensure_ascii=False, indent=2) + "\n"
    output.write_text(payload, encoding="utf-8", newline="\n")


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--positive-count", type=int, default=2500)
    parser.add_argument("--negatives-per-positive", type=int, default=2)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    args = parser.parse_args(argv)
    try:
        products = load_catalog(args.catalog)
        dataset = generate_dataset(
            products,
            positive_count=args.positive_count,
            negatives_per_positive=args.negatives_per_positive,
            seed=args.seed,
        )
        write_dataset(dataset, args.output)
    except (DatasetError, OSError, sqlite3.Error) as exc:
        parser.error(str(exc))
    print(
        f"Generated {dataset['positive_count']} positives and "
        f"{dataset['hard_negative_count']} hard negatives"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
