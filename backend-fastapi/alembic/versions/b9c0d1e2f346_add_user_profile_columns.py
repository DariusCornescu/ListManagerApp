"""add user profile columns

Revision ID: b9c0d1e2f346
Revises: a8b9c0d1e235
Create Date: 2026-11-06

"""

from alembic import op
import sqlalchemy as sa


revision = "b9c0d1e2f346"
down_revision = "a8b9c0d1e235"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("users", sa.Column("full_name", sa.String(length=100), nullable=True))
    op.add_column("users", sa.Column("company", sa.String(length=100), nullable=True))
    op.add_column("users", sa.Column("address", sa.String(length=255), nullable=True))


def downgrade():
    op.drop_column("users", "address")
    op.drop_column("users", "company")
    op.drop_column("users", "full_name")
