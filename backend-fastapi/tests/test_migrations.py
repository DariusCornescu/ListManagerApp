"""Alembic migration integrity tests.

- Drift guard: after `upgrade head`, the live schema must match the models
  (autogenerate detects no missing operations). Fails the moment a model is
  changed without a matching migration.
- Round-trip: upgrade -> downgrade base -> upgrade head succeeds (proves every
  migration's downgrade works).
"""
from pathlib import Path

from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from sqlalchemy import create_engine

from app.database import Base
import app.models  # noqa: F401  (register all models on Base.metadata)

# backend-fastapi/tests/test_migrations.py -> parents[1] == backend-fastapi/
_ALEMBIC_INI = Path(__file__).resolve().parents[1] / "alembic.ini"


def _config_for(url: str) -> Config:
    cfg = Config(str(_ALEMBIC_INI))
    cfg.set_main_option("sqlalchemy.url", url)
    return cfg


def test_migrations_match_models(tmp_path):
    """Running all migrations yields a schema identical to the models."""
    url = f"sqlite:///{tmp_path / 'drift.db'}"
    command.upgrade(_config_for(url), "head")

    engine = create_engine(url)
    try:
        with engine.connect() as conn:
            ctx = MigrationContext.configure(
                conn,
                opts={"compare_type": True, "render_as_batch": True},
            )
            diff = compare_metadata(ctx, Base.metadata)
    finally:
        engine.dispose()

    assert diff == [], f"Models and migrations diverged: {diff}"


def test_migrations_downgrade_upgrade_roundtrip(tmp_path):
    """Every migration's downgrade works: head -> base -> head with no error."""
    url = f"sqlite:///{tmp_path / 'roundtrip.db'}"
    cfg = _config_for(url)
    command.upgrade(cfg, "head")
    command.downgrade(cfg, "base")
    command.upgrade(cfg, "head")
