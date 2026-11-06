"""EX-44: a shared inventory with the same convergence guarantees as sessions."""
import random
import uuid
from unittest.mock import AsyncMock, patch

import pytest

from app import models


def _row(name="Lapte", quantity=2.0, price_bani=450, **extra):
    return {
        "item_uuid": str(uuid.uuid4()),
        "name": name,
        "quantity": quantity,
        "price_bani": price_bani,
        **extra,
    }


def _edit(item, **changes):
    return {
        "name": item["name"],
        "product_id": item["product_id"],
        "quantity": item["quantity"],
        "price_bani": item["price_bani"],
        "version": item["version"],
        **changes,
    }


@pytest.fixture
def personal(client, auth_headers):
    return client.get("/api/inventory/active", headers=auth_headers).json()


@pytest.fixture
def shared(client, auth_headers, team):
    return client.get(f"/api/inventory/active?team_id={team.id}", headers=auth_headers).json()


def _add(client, headers, inventory, **fields):
    response = client.post(
        f"/api/inventory/{inventory['id']}/items", headers=headers, json=_row(**fields)
    )
    assert response.status_code == 200, response.text
    return response.json()


def _url(item):
    return f"/api/inventory/{item['inventory_id']}/items/{item['item_uuid']}"


def _items(client, headers, inventory):
    return client.get(f"/api/inventory/{inventory['id']}/items", headers=headers).json()


class TestBasics:
    def test_inventory_is_created_once_per_owner(self, client, auth_headers, personal):
        again = client.get("/api/inventory/active", headers=auth_headers).json()
        assert again["id"] == personal["id"]
        assert again["items"] == []

    def test_personal_and_team_inventories_are_separate(self, personal, shared):
        assert personal["id"] != shared["id"]
        assert shared["owner_user_id"] is None

    def test_row_round_trip(self, client, auth_headers, personal):
        added = _add(client, auth_headers, personal, name="Pâine", quantity=2.5, price_bani=325)
        assert added["version"] == 1
        assert added["deleted"] is False

        row = _items(client, auth_headers, personal)[0]
        assert row["name"] == "Pâine"
        assert row["quantity"] == 2.5
        assert row["price_bani"] == 325

    def test_missing_quantity_and_price_are_allowed(self, client, auth_headers, personal):
        """A spoken line may be incomplete; the blank cell is filled in later."""
        added = _add(client, auth_headers, personal, quantity=None, price_bani=None)
        assert added["quantity"] is None
        assert added["price_bani"] is None

    def test_invalid_values_are_rejected(self, client, auth_headers, personal):
        url = f"/api/inventory/{personal['id']}/items"
        for bad in (
            _row(name=""),
            _row(name="x" * 201),
            _row(quantity=-1),
            _row(price_bani=-1),
            {**_row(), "item_uuid": "x" * 37},
        ):
            assert client.post(url, headers=auth_headers, json=bad).status_code == 422

    def test_unknown_product_is_rejected_before_writing(self, client, auth_headers, personal):
        response = client.post(
            f"/api/inventory/{personal['id']}/items",
            headers=auth_headers,
            json=_row(product_id=999999),
        )
        assert response.status_code == 404
        assert _items(client, auth_headers, personal) == []

    def test_known_product_is_linked(self, client, auth_headers, personal, sample_product):
        added = _add(client, auth_headers, personal, product_id=sample_product.id)
        assert added["product_id"] == sample_product.id


class TestAccess:
    def test_requires_a_token(self, client):
        assert client.get("/api/inventory/active").status_code == 403

    def test_a_stranger_cannot_see_or_change_a_personal_inventory(
        self, client, auth_headers, second_user_headers, personal
    ):
        item = _add(client, auth_headers, personal)
        url = f"/api/inventory/{personal['id']}"
        assert client.get(f"{url}/items", headers=second_user_headers).status_code == 404
        assert client.post(f"{url}/items", headers=second_user_headers, json=_row()).status_code == 404
        assert client.post(f"{url}/clear", headers=second_user_headers, json={}).status_code == 404
        assert client.put(
            _url(item), headers=second_user_headers, json=_edit(item)
        ).status_code == 404
        assert client.delete(
            _url(item), headers=second_user_headers
        ).status_code == 404
        assert _items(client, auth_headers, personal)[0]["name"] == "Lapte"

    def test_a_non_member_cannot_open_a_team_inventory(self, client, non_member_headers, team):
        assert client.get(
            f"/api/inventory/active?team_id={team.id}", headers=non_member_headers
        ).status_code == 404

    def test_team_members_share_one_inventory(
        self, client, auth_headers, team_member_headers, team, shared
    ):
        their_view = client.get(
            f"/api/inventory/active?team_id={team.id}", headers=team_member_headers
        ).json()
        assert their_view["id"] == shared["id"]

        added = _add(client, team_member_headers, shared, name="Ouă")
        assert _items(client, auth_headers, shared)[0]["id"] == added["id"]


class TestLostResponses:
    def test_a_retried_add_applies_once(self, client, auth_headers, personal):
        row = _row()
        url = f"/api/inventory/{personal['id']}/items"
        first = client.post(url, headers=auth_headers, json=row).json()
        second = client.post(url, headers=auth_headers, json=row).json()
        assert second["id"] == first["id"]
        assert len(_items(client, auth_headers, personal)) == 1

    def test_a_retried_add_does_not_resurrect_a_deleted_row(self, client, auth_headers, personal):
        """Phone A's add succeeds but its reply is lost; phone B deletes; A retries."""
        row = _row()
        url = f"/api/inventory/{personal['id']}/items"
        item = client.post(url, headers=auth_headers, json=row).json()
        client.delete(_url(item), headers=auth_headers)

        retry = client.post(url, headers=auth_headers, json=row)

        assert retry.status_code == 200
        assert retry.json()["deleted"] is True
        assert _items(client, auth_headers, personal) == []

    def test_a_retried_update_applies_once(self, client, auth_headers, personal):
        item = _add(client, auth_headers, personal)
        body = _edit(item, quantity=9.0, idempotency_key="edit-1")
        url = _url(item)
        first = client.put(url, headers=auth_headers, json=body).json()

        retry = client.put(url, headers=auth_headers, json=body)

        assert retry.status_code == 200
        assert retry.json() == first
        assert _items(client, auth_headers, personal)[0]["version"] == 2

    def test_a_retried_delete_succeeds(self, client, auth_headers, personal):
        item = _add(client, auth_headers, personal)
        url = _url(item)
        assert client.delete(url, headers=auth_headers).status_code == 200
        assert client.delete(url, headers=auth_headers).status_code == 200

    def test_a_retried_clear_cannot_erase_later_rows(self, client, auth_headers, personal):
        _add(client, auth_headers, personal, name="Old")
        url = f"/api/inventory/{personal['id']}/clear"
        first = client.post(url, headers=auth_headers, json={"idempotency_key": "clear-1"})
        assert first.json()["count"] == 1

        _add(client, auth_headers, personal, name="New")
        retry = client.post(url, headers=auth_headers, json={"idempotency_key": "clear-1"})

        assert retry.json() == first.json()
        assert [row["name"] for row in _items(client, auth_headers, personal)] == ["New"]

    def test_keys_are_scoped_to_one_inventory(self, client, auth_headers, personal, shared):
        """A key from one inventory must not return another's stored result."""
        client.post(
            f"/api/inventory/{personal['id']}/clear",
            headers=auth_headers,
            json={"idempotency_key": "same"},
        )
        _add(client, auth_headers, shared, name="Kept apart")
        response = client.post(
            f"/api/inventory/{shared['id']}/clear",
            headers=auth_headers,
            json={"idempotency_key": "same"},
        )
        assert response.json()["count"] == 1


class TestAddressing:
    def test_rows_of_another_inventory_cannot_be_reached_through_mine(
        self, client, auth_headers, second_user_headers, personal
    ):
        item = _add(client, auth_headers, personal)
        theirs = client.get("/api/inventory/active", headers=second_user_headers).json()
        wrong_url = f"/api/inventory/{theirs['id']}/items/{item['item_uuid']}"

        assert client.put(wrong_url, headers=second_user_headers, json=_edit(item)).status_code == 404
        assert client.delete(wrong_url, headers=second_user_headers).status_code == 200  # A no-op.
        assert _items(client, auth_headers, personal)[0]["id"] == item["id"]

    def test_deleting_a_row_that_was_never_added_is_harmless(self, client, auth_headers, personal):
        response = client.delete(
            f"/api/inventory/{personal['id']}/items/{uuid.uuid4()}", headers=auth_headers
        )
        assert response.status_code == 200


class TestConflicts:
    def test_a_stale_version_is_refused_and_changes_nothing(
        self, client, auth_headers, team_member_headers, shared
    ):
        item = _add(client, auth_headers, shared)
        url = _url(item)
        assert client.put(url, headers=auth_headers, json=_edit(item, quantity=3.0)).status_code == 200

        stale = client.put(url, headers=team_member_headers, json=_edit(item, quantity=99.0))

        assert stale.status_code == 409
        assert stale.json()["detail"]["current_version"] == 2
        assert _items(client, auth_headers, shared)[0]["quantity"] == 3.0

    def test_updating_a_deleted_row_is_not_found(self, client, auth_headers, personal):
        item = _add(client, auth_headers, personal)
        url = _url(item)
        client.delete(url, headers=auth_headers)
        assert client.put(url, headers=auth_headers, json=_edit(item)).status_code == 404

    @pytest.mark.parametrize("seed", range(6))
    def test_two_writers_converge(
        self, client, auth_headers, team_member_headers, shared, db_session, seed
    ):
        """A seeded mix of adds, edits, deletes and replays, with retry on 409.

        Each writer keeps its own copy of each row, refreshed only by its own requests,
        so it really does write from stale versions, as a second phone would.
        """
        rng = random.Random(seed)
        writers = [auth_headers, team_member_headers]
        views = [{}, {}]
        latest = {}
        applied_edits = 0
        conflicts = 0

        for step in range(60):
            index = rng.randrange(2)
            headers, view = writers[index], views[index]
            action = rng.choice(["add", "edit", "edit", "edit", "delete"]) if view else "add"
            if action == "add":
                row = _row(name=f"row-{step}", quantity=float(step))
                url = f"/api/inventory/{shared['id']}/items"
                item = client.post(url, headers=headers, json=row).json()
                # The other phone delivers the same add: a retry finds the same row.
                other = client.post(url, headers=writers[1 - index], json=row).json()
                assert other["id"] == item["id"]
                view[item["id"]] = item
                views[1 - index][item["id"]] = other
                latest[item["id"]] = item
            elif action == "edit":
                stale = view[rng.choice(sorted(view))]
                if stale["id"] not in latest:  # Removed by the other phone.
                    assert client.put(
                        _url(stale), headers=headers, json=_edit(stale)
                    ).status_code == 404
                    del view[stale["id"]]
                    continue
                url = _url(stale)
                key = f"e-{seed}-{step}"
                response = client.put(
                    url, headers=headers, json=_edit(stale, quantity=float(step), idempotency_key=key)
                )
                if response.status_code == 409:
                    conflicts += 1
                    current = next(
                        row for row in _items(client, headers, shared) if row["id"] == stale["id"]
                    )
                    response = client.put(
                        url, headers=headers, json=_edit(current, quantity=float(step), idempotency_key=key)
                    )
                assert response.status_code == 200, response.text
                applied_edits += 1
                view[stale["id"]] = response.json()
                latest[stale["id"]] = response.json()
            else:
                gone = rng.choice(sorted(view))
                assert client.delete(_url(view[gone]), headers=headers).status_code == 200
                del view[gone]
                latest.pop(gone, None)

        assert conflicts > 0

        first_view = _items(client, auth_headers, shared)
        second_view = _items(client, team_member_headers, shared)
        assert first_view == second_view

        # Every row matches its last acknowledged write, so no update was lost.
        by_id = {row["id"]: row for row in first_view}
        assert set(by_id) == set(latest)
        for item_id, item in latest.items():
            assert by_id[item_id]["quantity"] == item["quantity"]
            assert by_id[item_id]["version"] == item["version"]

        versions = sum(
            row.version - 1 for row in db_session.query(models.InventoryItem).all()
        )
        assert versions == applied_edits


class TestLiveEvents:
    def _capture(self):
        return patch("app.routers.inventory.manager.send_to_users", new_callable=AsyncMock)

    def test_a_change_is_announced_to_the_team_only(
        self, client, auth_headers, sample_user, team_member_user, non_member_user, shared
    ):
        with self._capture() as send:
            _add(client, auth_headers, shared)

        message, recipients = send.await_args.args
        assert message["type"] == "inventory_changed"
        assert message["data"]["inventory_id"] == shared["id"]
        assert recipients == {sample_user.id, team_member_user.id}

    def test_a_personal_change_goes_only_to_its_owner(
        self, client, auth_headers, sample_user, personal
    ):
        with self._capture() as send:
            _add(client, auth_headers, personal)
        assert send.await_args.args[1] == {sample_user.id}

    def test_replays_and_conflicts_announce_nothing(self, client, auth_headers, personal):
        row = _row()
        url = f"/api/inventory/{personal['id']}/items"
        item = client.post(url, headers=auth_headers, json=row).json()
        client.put(
            _url(item), headers=auth_headers, json=_edit(item, quantity=5.0)
        )

        with self._capture() as send:
            client.post(url, headers=auth_headers, json=row)
            client.put(
                _url(item), headers=auth_headers, json=_edit(item)
            )

        send.assert_not_awaited()
