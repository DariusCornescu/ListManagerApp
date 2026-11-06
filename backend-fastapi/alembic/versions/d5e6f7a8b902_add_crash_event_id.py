"""add crash event id

Revision ID: d5e6f7a8b902
Revises: c4d5e6f7a801
Create Date: 2026-09-13

"""

from alembic import op
import sqlalchemy as sa


revision = "d5e6f7a8b902"
down_revision = "c4d5e6f7a801"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column(
        "crash_reports",
        sa.Column("event_id", sa.String(length=100), nullable=True),
    )
    op.create_index(
        "ix_crash_reports_event_id",
        "crash_reports",
        ["event_id"],
        unique=True,
    )


def downgrade():
    op.drop_index("ix_crash_reports_event_id", table_name="crash_reports")
    op.drop_column("crash_reports", "event_id")
