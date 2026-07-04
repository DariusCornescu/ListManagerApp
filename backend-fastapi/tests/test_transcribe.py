# tests/test_transcribe.py
"""Tests for POST /api/speech/transcribe.

A FAKE transcriber is injected via dependency override so NO real Groq call is
ever made. Each test cleans up its override.
"""
from typing import Optional

import pytest

from app.main import app
from app.config import settings
from app.transcription import (
    Transcriber,
    TranscriptionResult,
    get_transcriber,
)


class FakeTranscriber(Transcriber):
    """Returns a fixed transcription result without any network call."""

    async def transcribe(
        self, audio: bytes, filename: str, language: Optional[str] = None
    ) -> TranscriptionResult:
        return TranscriptionResult(
            text="lapte doi litri",
            language="ro",
            model="fake",
            provider="fake",
        )


class FailingTranscriber(Transcriber):
    """Simulates a provider/upstream failure."""

    async def transcribe(
        self, audio: bytes, filename: str, language: Optional[str] = None
    ) -> TranscriptionResult:
        raise RuntimeError("provider exploded")


@pytest.fixture
def fake_transcriber():
    """Override get_transcriber with FakeTranscriber for the test, then clear."""
    app.dependency_overrides[get_transcriber] = lambda: FakeTranscriber()
    yield
    app.dependency_overrides.pop(get_transcriber, None)


@pytest.fixture
def failing_transcriber():
    """Override get_transcriber with FailingTranscriber for the test, then clear."""
    app.dependency_overrides[get_transcriber] = lambda: FailingTranscriber()
    yield
    app.dependency_overrides.pop(get_transcriber, None)


SMALL_AUDIO = b"RIFF....WAVEfmt "


def test_transcribe_requires_auth(client, fake_transcriber):
    """No token -> 403 (HTTPBearer rejects missing credentials)."""
    response = client.post(
        "/api/speech/transcribe",
        files={"file": ("a.wav", SMALL_AUDIO, "audio/wav")},
    )
    assert response.status_code == 403


def test_transcribe_success(client, auth_headers, fake_transcriber):
    """Valid token + small audio -> 200 with the fake transcription text."""
    response = client.post(
        "/api/speech/transcribe",
        headers=auth_headers,
        files={"file": ("a.wav", SMALL_AUDIO, "audio/wav")},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["text"] == "lapte doi litri"
    assert body["language"] == "ro"
    assert body["model"] == "fake"
    assert body["provider"] == "fake"


def test_transcribe_passes_language_form_field(client, auth_headers, fake_transcriber):
    """Optional language form field is accepted (still 200)."""
    response = client.post(
        "/api/speech/transcribe",
        headers=auth_headers,
        files={"file": ("a.wav", SMALL_AUDIO, "audio/wav")},
        data={"language": "ro"},
    )
    assert response.status_code == 200
    assert response.json()["text"] == "lapte doi litri"


def test_transcribe_rejects_non_audio_content_type(client, auth_headers, fake_transcriber):
    """Non-audio content type -> 415."""
    response = client.post(
        "/api/speech/transcribe",
        headers=auth_headers,
        files={"file": ("a.txt", b"hello world", "text/plain")},
    )
    assert response.status_code == 415


def test_transcribe_rejects_empty_file(client, auth_headers, fake_transcriber):
    """Empty audio payload -> 400."""
    response = client.post(
        "/api/speech/transcribe",
        headers=auth_headers,
        files={"file": ("a.wav", b"", "audio/wav")},
    )
    assert response.status_code == 400


def test_transcribe_rejects_oversized_payload(
    client, auth_headers, fake_transcriber, monkeypatch
):
    """Payload larger than MAX_AUDIO_BYTES -> 413."""
    monkeypatch.setattr(settings, "MAX_AUDIO_BYTES", 4)
    response = client.post(
        "/api/speech/transcribe",
        headers=auth_headers,
        files={"file": ("a.wav", SMALL_AUDIO, "audio/wav")},
    )
    assert response.status_code == 413


def test_transcribe_provider_error_returns_502(client, auth_headers, failing_transcriber):
    """Provider raising during transcription -> 502."""
    response = client.post(
        "/api/speech/transcribe",
        headers=auth_headers,
        files={"file": ("a.wav", SMALL_AUDIO, "audio/wav")},
    )
    assert response.status_code == 502
