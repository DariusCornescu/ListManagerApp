import logging
from typing import Optional

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile

from .. import models
from ..auth import get_current_user
from ..config import settings
from ..limiting import limiter
from ..schemas_speech import TranscriptionResponse
from ..transcription import Transcriber, get_transcriber


logger = logging.getLogger(__name__)


router = APIRouter()


@router.post("/api/speech/transcribe", response_model=TranscriptionResponse)
@limiter.limit("20/minute")
async def transcribe_audio(
    request: Request,
    file: UploadFile = File(...),
    language: Optional[str] = Form(None),
    current_user: models.User = Depends(get_current_user),
    transcriber: Transcriber = Depends(get_transcriber),
):
    """Transcribe an uploaded audio file to text (server-side, audio -> text only).

    Auth required. The provider is injected via `get_transcriber` so it is
    swappable and mockable. Ranking/matching of the text stays on-device.
    """
    # Validate content type (must be some audio/* MIME type).
    if not (file.content_type or "").startswith("audio/"):
        raise HTTPException(
            status_code=415,
            detail="Unsupported media type: an audio/* file is required.",
        )

    audio = await file.read()

    # Empty payload is a client error.
    if not audio:
        raise HTTPException(status_code=400, detail="Empty audio file.")

    # Enforce max size.
    if len(audio) > settings.MAX_AUDIO_BYTES:
        raise HTTPException(
            status_code=413,
            detail=(
                f"Audio file too large (max {settings.MAX_AUDIO_BYTES} bytes)."
            ),
        )

    try:
        result = await transcriber.transcribe(
            audio, file.filename or "audio", language
        )
    except HTTPException:
        raise
    except Exception as exc:
        # Provider/upstream failure (network, non-2xx, missing key, etc.).
        # Never leak the API key or raw upstream internals.
        logger.error("Transcription provider error: %s", type(exc).__name__)
        raise HTTPException(
            status_code=502,
            detail="Transcription provider error.",
        )

    return TranscriptionResponse(
        text=result.text,
        language=result.language,
        model=result.model,
        provider=result.provider,
    )
