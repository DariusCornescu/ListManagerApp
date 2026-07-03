# app/config.py
"""Application settings loaded from environment / .env via pydantic-settings.

SECRET_KEY is REQUIRED and has no default: instantiating Settings without it
raises a ValidationError, which fails the app fast at startup instead of
silently falling back to an insecure key.
"""
from typing import Optional

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    # REQUIRED — no default. Missing SECRET_KEY raises ValidationError at startup.
    SECRET_KEY: str

    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 1440

    DATABASE_URL: str = "sqlite:///./listmanager.db"

    RATE_LIMIT_ENABLED: bool = True

    # ===== Speech transcription (server-side, audio -> text only) =====
    # Groq API key for the OpenAI-compatible transcription endpoint. Optional:
    # absent at startup is fine; the GroqTranscriber raises a clear error only
    # when an actual transcription is attempted without it.
    GROQ_API_KEY: Optional[str] = None
    # Model used for transcription (Groq whisper-large-v3-turbo by default).
    TRANSCRIPTION_MODEL: str = "whisper-large-v3-turbo"
    # Maximum accepted audio upload size in bytes (default: 25 MB).
    MAX_AUDIO_BYTES: int = 25 * 1024 * 1024

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


# Singleton settings instance. Importing this module with no SECRET_KEY set
# (env or .env) will raise, which is the intended fail-fast behavior.
settings = Settings()
