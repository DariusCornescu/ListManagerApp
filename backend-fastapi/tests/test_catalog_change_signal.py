"""Phones can cheaply tell whether the catalog changed, and hear about imports."""
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import status

from app import models

LISTS = ["/api/catalog/products", "/api/catalog/distributors"]
CSV = b"distributor,product_name,aliases,price\nMetro,Milk,lapte,5.00\n"


def _import(client, headers, dry_run):
    return client.post(
        "/api/admin/catalog/import",
        files={"file": ("catalog.csv", CSV, "text/csv")},
        params={"dry_run": str(dry_run).lower()},
        headers=headers,
    )


@pytest.fixture
def broadcast():
    with patch("app.routers.admin.manager.broadcast", new_callable=AsyncMock) as mocked:
        yield mocked


@pytest.mark.parametrize("url", LISTS)
class TestConditionalLists:
    def test_list_carries_an_etag(self, client, sample_product, url):
        resp = client.get(url)
        assert resp.status_code == status.HTTP_200_OK
        assert resp.headers["etag"]
        assert resp.headers["cache-control"] == "no-cache"
        assert resp.json()

    def test_same_etag_gets_304_with_no_body(self, client, sample_product, url):
        etag = client.get(url).headers["etag"]
        resp = client.get(url, headers={"If-None-Match": etag})
        assert resp.status_code == status.HTTP_304_NOT_MODIFIED
        assert resp.content == b""
        assert resp.headers["etag"] == etag

    def test_stale_etag_gets_the_full_list(self, client, sample_product, url):
        resp = client.get(url, headers={"If-None-Match": '"old"'})
        assert resp.status_code == status.HTTP_200_OK
        assert resp.json()

    def test_weak_and_listed_etags_match(self, client, sample_product, url):
        etag = client.get(url).headers["etag"]
        for header in (f"W/{etag}", f'"other", {etag}', "*"):
            resp = client.get(url, headers={"If-None-Match": header})
            assert resp.status_code == status.HTTP_304_NOT_MODIFIED, header


def test_product_etag_changes_with_the_catalog(client, db_session, sample_product):
    before = client.get("/api/catalog/products").headers["etag"]

    sample_product.aliases = "alias nou"
    db_session.commit()
    after_edit = client.get("/api/catalog/products").headers["etag"]
    assert after_edit != before

    db_session.delete(sample_product)
    db_session.commit()
    assert client.get("/api/catalog/products").headers["etag"] != after_edit


def test_product_etag_ignores_price_so_it_cannot_reveal_it(client, db_session, sample_product):
    before = client.get("/api/catalog/products").headers["etag"]
    sample_product.price = 999
    db_session.commit()
    assert client.get("/api/catalog/products").headers["etag"] == before


def test_distributor_etag_changes_with_the_list(client, db_session, sample_distributor):
    before = client.get("/api/catalog/distributors").headers["etag"]
    db_session.add(models.Distributor(distributor_name="Furnizor nou"))
    db_session.commit()
    assert client.get("/api/catalog/distributors").headers["etag"] != before


def test_order_is_stable_after_a_row_is_updated(client, db_session, sample_distributor):
    rows = [models.Product(name=f"P{i}", distributor_id=sample_distributor.id) for i in range(3)]
    db_session.add_all(rows)
    db_session.commit()
    ids = [p["id"] for p in client.get("/api/catalog/products").json()]
    assert ids == sorted(ids)


def test_search_still_filters_and_hides_price(client, sample_product):
    resp = client.get("/api/catalog/products", params={"search": sample_product.name})
    assert [p["id"] for p in resp.json()] == [sample_product.id]
    assert "price" not in resp.json()[0]


def test_large_list_is_compressed_for_clients_that_accept_it(client, db_session, sample_distributor):
    db_session.add_all(
        models.Product(name=f"Produs lung {i:04d}", distributor_id=sample_distributor.id)
        for i in range(200)
    )
    db_session.commit()
    resp = client.get("/api/catalog/products", headers={"Accept-Encoding": "gzip"})
    assert resp.headers["content-encoding"] == "gzip"
    assert len(resp.json()) == 200


class TestImportSignal:
    def test_committed_import_announces_a_change(self, client, admin_headers, broadcast):
        assert _import(client, admin_headers, dry_run=False).status_code == status.HTTP_200_OK
        broadcast.assert_awaited_once()
        message = broadcast.await_args.args[0]
        assert message["type"] == "catalog_changed"
        assert message["data"]["change_id"]

    def test_each_change_has_a_new_id(self, client, admin_headers, broadcast):
        """The Android socket keeps messages in a StateFlow, which drops equal values."""
        _import(client, admin_headers, dry_run=False)
        client.post(
            "/api/admin/catalog/import",
            files={"file": ("c.csv", b"distributor,product_name\nMetro,Bread\n", "text/csv")},
            params={"dry_run": "false"},
            headers=admin_headers,
        )
        ids = [call.args[0]["data"]["change_id"] for call in broadcast.await_args_list]
        assert len(ids) == 2 and ids[0] != ids[1]

    def test_dry_run_is_silent(self, client, admin_headers, broadcast):
        _import(client, admin_headers, dry_run=True)
        broadcast.assert_not_awaited()

    def test_import_that_changes_nothing_is_silent(self, client, admin_headers, broadcast):
        _import(client, admin_headers, dry_run=False)
        broadcast.reset_mock()
        _import(client, admin_headers, dry_run=False)
        broadcast.assert_not_awaited()

    def test_signal_carries_no_catalog_data(self, client, admin_headers, broadcast):
        _import(client, admin_headers, dry_run=False)
        assert "5.00" not in str(broadcast.await_args.args[0])
