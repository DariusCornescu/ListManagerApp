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
