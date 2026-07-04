# app/schemas_speech.py
"""Pydantic schemas for the speech transcription endpoint."""
from typing import Optional

from pydantic import BaseModel


class TranscriptionResponse(BaseModel):
    """Response body for POST /api/speech/transcribe."""

    text: str
    language: Optional[str] = None
    model: str
    provider: str
