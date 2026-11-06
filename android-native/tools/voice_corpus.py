"""Safely append operator-confirmed, text-only cases to the voice corpus."""

from __future__ import annotations

import argparse
import json
import os
import tempfile
from pathlib import Path
from typing import Any


PRIVACY_MODE = "operator-confirmed-text-only-no-audio"
NOISE_CONDITIONS = ("clean", "background", "shop-floor", "unspecified")


class CorpusError(ValueError):
    pass


def _required_text(value: str, field: str) -> str:
    cleaned = value.strip()
    if not cleaned:
        raise CorpusError(f"{field} is required")
    if "\n" in cleaned or "\r" in cleaned:
        raise CorpusError(f"{field} must be one line")
    return cleaned


def append_confirmed_case(
    corpus_path: Path,
    *,
    case_id: str,
    utterance: str,
    expected_product_id: int,
    locale: str,
    noise_condition: str,
    confirmed_text_only: bool,
) -> dict[str, Any]:
    if not confirmed_text_only:
        raise CorpusError("operator confirmation is required")

    case_id = _required_text(case_id, "id")
    utterance = _required_text(utterance, "utterance")
    locale = _required_text(locale, "locale")
    if not case_id.startswith("real-"):
        raise CorpusError("real dictation IDs must start with 'real-'")
    if noise_condition not in NOISE_CONDITIONS:
        raise CorpusError(
            "noise_condition must be one of: " + ", ".join(NOISE_CONDITIONS)
        )

    original = corpus_path.read_bytes()
    try:
        corpus = json.loads(original.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise CorpusError("corpus is not valid UTF-8 JSON") from exc

    if corpus.get("schema_version") != 1:
        raise CorpusError("only schema_version 1 is supported")
    if corpus.get("privacy_mode") != PRIVACY_MODE:
        raise CorpusError(f"privacy_mode must be '{PRIVACY_MODE}'")

    products = corpus.get("products")
    cases = corpus.get("cases")
    if not isinstance(products, list) or not isinstance(cases, list):
        raise CorpusError("corpus products and cases must be arrays")
    if expected_product_id not in {product.get("id") for product in products}:
        raise CorpusError(f"unknown expected product {expected_product_id}")
    if case_id in {case.get("id") for case in cases}:
        raise CorpusError(f"duplicate case id '{case_id}'")

    case = {
        "id": case_id,
        "utterance": utterance,
        "expected_product_id": expected_product_id,
        "locale": locale,
        "noise_condition": noise_condition,
    }
    corpus["cases"] = [*cases, case]
    payload = (json.dumps(corpus, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    temp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", prefix=f".{corpus_path.name}.", dir=corpus_path.parent, delete=False
        ) as temp_file:
            temp_file.write(payload)
            temp_file.flush()
            os.fsync(temp_file.fileno())
            temp_path = Path(temp_file.name)
        os.replace(temp_path, corpus_path)
    finally:
        if temp_path is not None and temp_path.exists():
            temp_path.unlink()

    return case


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", required=True, type=Path)
    parser.add_argument("--id", required=True)
    parser.add_argument("--utterance", required=True)
    parser.add_argument("--expected-product-id", required=True, type=int)
    parser.add_argument("--locale", default="ro-RO")
    parser.add_argument("--noise-condition", choices=NOISE_CONDITIONS, required=True)
    parser.add_argument(
        "--confirmed-text-only",
        action="store_true",
        help="Confirm that this deliberate sample retains text only and no audio.",
    )
    args = parser.parse_args()
    try:
        case = append_confirmed_case(
            args.corpus,
            case_id=args.id,
            utterance=args.utterance,
            expected_product_id=args.expected_product_id,
            locale=args.locale,
            noise_condition=args.noise_condition,
            confirmed_text_only=args.confirmed_text_only,
        )
    except (CorpusError, OSError) as exc:
        parser.error(str(exc))
    print(f"Added {case['id']} for product {case['expected_product_id']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
