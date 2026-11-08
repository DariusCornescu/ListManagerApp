"""index crash report created at

Revision ID: f787337c7d90
Revises: 34d3f798632e
Create Date: 2026-11-08

Adds an index on crash_reports.created_at for the 180-day retention purge and the
per-day admin charts. Touches no data.
"""

from alembic import op


revision = "f787337c7d90"
down_revision = "34d3f798632e"
branch_labels = None
depends_on = None


def upgrade():
    with op.batch_alter_table("crash_reports", schema=None) as batch_op:
        batch_op.create_index(batch_op.f("ix_crash_reports_created_at"), ["created_at"], unique=False)


def downgrade():
    with op.batch_alter_table("crash_reports", schema=None) as batch_op:
        batch_op.drop_index(batch_op.f("ix_crash_reports_created_at"))
