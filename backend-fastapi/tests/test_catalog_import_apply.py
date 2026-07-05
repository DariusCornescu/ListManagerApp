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


def test_in_file_duplicate_is_last_wins(db_session):
    parsed = parse_catalog_csv(
        "distributor,product_name,price\nMetro,Milk,5.00\nMetro,Milk,6.50\n"
    )
    result = apply_catalog_import(db_session, parsed, commit=True)
    assert _count_products(db_session) == 1
    assert db_session.query(models.Product).one().price == Decimal("6.50")
    assert result.new == 1
    assert result.updated == 1
