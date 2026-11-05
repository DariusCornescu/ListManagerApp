"""EX-46: CORS is limited to explicitly configured origins."""
import pytest


def test_cors_allows_only_configured_origins():
    from fastapi import FastAPI
    from fastapi.middleware.cors import CORSMiddleware
    from fastapi.testclient import TestClient

    from app.config import parse_cors_origins

    api = FastAPI()
    api.add_middleware(
        CORSMiddleware,
        allow_origins=parse_cors_origins("https://app.example.com/, http://localhost:5000"),
        allow_methods=["GET"],
        allow_headers=["Authorization"],
    )
    api.get("/ping")(lambda: {"ok": True})
    browser = TestClient(api)

    allowed = browser.get("/ping", headers={"Origin": "https://app.example.com"})
    assert allowed.headers["access-control-allow-origin"] == "https://app.example.com"

    denied = browser.get("/ping", headers={"Origin": "https://evil.example.com"})
    assert "access-control-allow-origin" not in denied.headers


def test_cors_setting_refuses_wildcard_and_defaults_empty():
    from app.config import parse_cors_origins

    assert parse_cors_origins("") == []
    with pytest.raises(ValueError):
        parse_cors_origins("https://app.example.com,*")


def test_app_has_cors_middleware_with_no_default_origins(client):
    response = client.get("/health", headers={"Origin": "https://evil.example.com"})
    assert "access-control-allow-origin" not in response.headers
