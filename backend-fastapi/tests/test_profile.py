"""EX-37: profile fields persist through /api/auth/me."""


def _me(client, headers):
    response = client.get("/api/auth/me", headers=headers)
    assert response.status_code == 200
    return response.json()


def test_profile_fields_start_empty_and_existing_fields_are_unchanged(client, auth_headers):
    me = _me(client, auth_headers)
    assert me["full_name"] is None
    assert me["company"] is None
    assert me["address"] is None
    assert me["username"] == "testuser"
    assert me["role"] == "USER"


def test_profile_fields_persist(client, auth_headers):
    response = client.put(
        "/api/auth/me",
        headers=auth_headers,
        json={"full_name": "Ana Popescu", "company": "Magazin SRL", "address": "Str. Lungă 1, Cluj"},
    )
    assert response.status_code == 200
    assert response.json()["company"] == "Magazin SRL"

    me = _me(client, auth_headers)
    assert me["full_name"] == "Ana Popescu"
    assert me["company"] == "Magazin SRL"
    assert me["address"] == "Str. Lungă 1, Cluj"


def test_a_missing_field_is_left_alone(client, auth_headers):
    client.put("/api/auth/me", headers=auth_headers, json={"full_name": "Ana", "company": "Magazin"})
    client.put("/api/auth/me", headers=auth_headers, json={"company": "Alt Magazin"})

    me = _me(client, auth_headers)
    assert me["full_name"] == "Ana"
    assert me["company"] == "Alt Magazin"


def test_an_empty_field_clears_the_value(client, auth_headers):
    client.put("/api/auth/me", headers=auth_headers, json={"company": "Magazin"})
    client.put("/api/auth/me", headers=auth_headers, json={"company": "   "})
    assert _me(client, auth_headers)["company"] is None


def test_values_are_trimmed(client, auth_headers):
    client.put("/api/auth/me", headers=auth_headers, json={"full_name": "  Ana  "})
    assert _me(client, auth_headers)["full_name"] == "Ana"


def test_over_long_values_are_rejected(client, auth_headers):
    for field, limit in (("full_name", 100), ("company", 100), ("address", 255)):
        response = client.put(
            "/api/auth/me", headers=auth_headers, json={field: "x" * (limit + 1)}
        )
        assert response.status_code == 422, field


def test_profile_change_does_not_revoke_the_session(client, auth_headers):
    client.put("/api/auth/me", headers=auth_headers, json={"address": "Cluj"})
    assert client.get("/api/auth/me", headers=auth_headers).status_code == 200


def test_old_client_payload_still_works(client, auth_headers):
    response = client.put(
        "/api/auth/me", headers=auth_headers, json={"email": "new@example.com"}
    )
    assert response.status_code == 200
    assert response.json()["email"] == "new@example.com"


def test_one_user_cannot_see_anothers_profile(client, auth_headers, second_user_headers):
    client.put("/api/auth/me", headers=auth_headers, json={"company": "Magazin"})
    assert _me(client, second_user_headers)["company"] is None
