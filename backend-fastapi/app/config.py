# app/config.py
"""Application settings loaded from environment / .env via pydantic-settings.

SECRET_KEY is REQUIRED and has no default: instantiating Settings without it
raises a ValidationError, which fails the app fast at startup instead of
silently falling back to an insecure key.
"""
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    # REQUIRED — no default. Missing SECRET_KEY raises ValidationError at startup.
    SECRET_KEY: str

    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 1440

    DATABASE_URL: str = "sqlite:///./listmanager.db"

    RATE_LIMIT_ENABLED: bool = True

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


# Singleton settings instance. Importing this module with no SECRET_KEY set
# (env or .env) will raise, which is the intended fail-fast behavior.
settings = Settings()
