"""add session client uuid

Revision ID: f7a8b9c0d124
Revises: d5e6f7a8b902
Create Date: 2026-09-13

"""

from alembic import op
import sqlalchemy as sa


revision = "f7a8b9c0d124"
down_revision = "d5e6f7a8b902"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column(
        "global_sessions",
        sa.Column("client_uuid", sa.String(length=36), nullable=True),
    )
    op.create_index(
        "ix_global_sessions_client_uuid",
        "global_sessions",
        ["client_uuid"],
        unique=True,
    )


def downgrade():
    op.drop_index("ix_global_sessions_client_uuid", table_name="global_sessions")
    op.drop_column("global_sessions", "client_uuid")
