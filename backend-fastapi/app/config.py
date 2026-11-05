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
    REFRESH_TOKEN_EXPIRE_DAYS: int = 30

    DATABASE_URL: str = "sqlite:///./listmanager.db"

    RATE_LIMIT_ENABLED: bool = True

    # Comma-separated browser origins allowed by CORS, e.g.
    # "https://app.example.com,http://localhost:5000". Empty means no browser
    # origin is allowed; native mobile clients are unaffected by CORS.
    CORS_ORIGINS: str = ""

    # ===== Speech transcription (server-side, audio -> text only) =====
    # Groq API key for the OpenAI-compatible transcription endpoint. Optional:
    # absent at startup is fine; the GroqTranscriber raises a clear error only
    # when an actual transcription is attempted without it.
    GROQ_API_KEY: Optional[str] = None
    # Model used for transcription (Groq whisper-large-v3-turbo by default).
    TRANSCRIPTION_MODEL: str = "whisper-large-v3-turbo"
    # Maximum accepted audio upload size in bytes (default: 25 MB).
    MAX_AUDIO_BYTES: int = 25 * 1024 * 1024

    # Maximum accepted catalog CSV upload size in bytes (default: 5 MB).
    MAX_IMPORT_BYTES: int = 5 * 1024 * 1024

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


def parse_cors_origins(raw: str) -> list[str]:
    """Split the comma-separated setting, refusing the "*" wildcard."""
    origins = [origin.strip().rstrip("/") for origin in raw.split(",") if origin.strip()]
    if "*" in origins:
        raise ValueError("CORS_ORIGINS must list explicit origins, not '*'")
    return origins


# Singleton settings instance. Importing this module with no SECRET_KEY set
# (env or .env) will raise, which is the intended fail-fast behavior.
settings = Settings()
