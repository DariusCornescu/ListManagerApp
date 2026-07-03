# app/transcription.py
"""Server-side speech transcription (audio -> text).

The server performs transcription ONLY; any ranking/matching of the resulting
text stays on-device (see docs/SPEECH_DESIGN.md §0).

The provider is intentionally behind a small abstract `Transcriber` interface
so it is swappable (e.g. a different vendor) and mockable in tests via the
`get_transcriber` FastAPI dependency override.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional

import httpx

from .config import settings

# Groq's OpenAI-compatible audio transcription endpoint.
GROQ_TRANSCRIPTION_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
# Sane upper bound for a single transcription request.
TRANSCRIPTION_TIMEOUT_SECONDS = 60.0


@dataclass
class TranscriptionResult:
    """Outcome of a transcription: the recognized text plus provenance."""

    text: str
    language: Optional[str]
    model: str
    provider: str


class TranscriptionError(Exception):
    """Raised when a provider fails to produce a transcription.

    Carries an optional upstream HTTP `status_code`. Never include the API key
    or other secrets in the message.
    """

    def __init__(self, message: str, status_code: Optional[int] = None):
        super().__init__(message)
        self.status_code = status_code


class Transcriber(ABC):
    """Abstract transcription provider interface."""

    @abstractmethod
    async def transcribe(
        self, audio: bytes, filename: str, language: Optional[str] = None
    ) -> TranscriptionResult:
        """Transcribe `audio` bytes (named `filename`) into text."""
        raise NotImplementedError


class GroqTranscriber(Transcriber):
    """Transcriber backed by Groq's OpenAI-compatible Whisper endpoint."""

    provider_name = "groq"

    async def transcribe(
        self, audio: bytes, filename: str, language: Optional[str] = None
    ) -> TranscriptionResult:
        if not settings.GROQ_API_KEY:
            raise RuntimeError(
                "GROQ_API_KEY is not configured; cannot perform transcription."
            )

        files = {"file": (filename, audio)}
        data = {
            "model": settings.TRANSCRIPTION_MODEL,
            "response_format": "json",
        }
        if language:
            data["language"] = language

        headers = {"Authorization": f"Bearer {settings.GROQ_API_KEY}"}

        try:
            async with httpx.AsyncClient(
                timeout=TRANSCRIPTION_TIMEOUT_SECONDS
            ) as client:
                response = await client.post(
                    GROQ_TRANSCRIPTION_URL,
                    headers=headers,
                    files=files,
                    data=data,
                )
        except httpx.HTTPError as exc:
            # Network/timeout failure — do not leak headers/key.
            raise TranscriptionError(
                f"Transcription provider request failed: {type(exc).__name__}"
            ) from exc

        if not (200 <= response.status_code < 300):
            raise TranscriptionError(
                f"Transcription provider returned status {response.status_code}",
                status_code=response.status_code,
            )

        try:
            payload = response.json()
            text = payload["text"]
        except (ValueError, KeyError, TypeError) as exc:
            raise TranscriptionError(
                "Transcription provider returned an unexpected response."
            ) from exc

        return TranscriptionResult(
            text=text,
            language=language,
            model=settings.TRANSCRIPTION_MODEL,
            provider=self.provider_name,
        )


def get_transcriber() -> Transcriber:
    """FastAPI dependency returning the active transcription provider.

    Tests override this via `app.dependency_overrides[get_transcriber]` to inject
    a fake, so no real network call is made.
    """
    return GroqTranscriber()
