"""add auth events

Revision ID: 34d3f798632e
Revises: c0d1e2f3a457
Create Date: 2026-11-07

One row per sign-in, sign-out or admin account action. No IP address, typed
username or token is stored. Additive: creates a new table only.
"""

from alembic import op
import sqlalchemy as sa


revision = "34d3f798632e"
down_revision = "c0d1e2f3a457"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "auth_events",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("user_id", sa.Integer(), nullable=True),
        sa.Column("actor_user_id", sa.Integer(), nullable=True),
        sa.Column("event_type", sa.String(length=30), nullable=False),
        sa.Column("reason", sa.String(length=30), nullable=True),
        sa.Column("client", sa.String(length=10), server_default="other", nullable=False),
        sa.Column("created_at", sa.DateTime(), server_default=sa.text("(CURRENT_TIMESTAMP)"), nullable=False),
        sa.ForeignKeyConstraint(
            ["actor_user_id"], ["users.id"],
            name=op.f("fk_auth_events_actor_user_id_users"), ondelete="SET NULL",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"], ["users.id"],
            name=op.f("fk_auth_events_user_id_users"), ondelete="SET NULL",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_auth_events")),
    )
    with op.batch_alter_table("auth_events", schema=None) as batch_op:
        batch_op.create_index(batch_op.f("ix_auth_events_created_at"), ["created_at"], unique=False)
        batch_op.create_index(batch_op.f("ix_auth_events_id"), ["id"], unique=False)
        batch_op.create_index("ix_auth_events_user_id_created_at", ["user_id", "created_at"], unique=False)


def downgrade():
    with op.batch_alter_table("auth_events", schema=None) as batch_op:
        batch_op.drop_index("ix_auth_events_user_id_created_at")
        batch_op.drop_index(batch_op.f("ix_auth_events_id"))
        batch_op.drop_index(batch_op.f("ix_auth_events_created_at"))
    op.drop_table("auth_events")
