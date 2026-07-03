"""idempotency schema fixes: composite AppliedOp PK; drop SessionOp unique

Revision ID: 0002
Revises: 0001
Create Date: 2026-06-11

"""
from alembic import op
import sqlalchemy as sa

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None

# Same convention the models/Base use, so batch-mode table recreation on SQLite
# can reflect existing constraints under their predictable names (otherwise the
# unique constraint reflects nameless and drop_constraint-by-name can't find it).
NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


def upgrade():
    # applied_ops: session_id becomes NOT NULL and part of a composite PK.
    # recreate="always" makes this work on SQLite (which can't ALTER a PK).
    with op.batch_alter_table("applied_ops", recreate="always") as batch_op:
        batch_op.alter_column(
            "session_id", existing_type=sa.Integer(), nullable=False
        )
        batch_op.create_primary_key("pk_applied_ops", ["key", "session_id"])

    # session_ops: drop the global unique constraint on idempotency_key.
    with op.batch_alter_table(
        "session_ops", recreate="always", naming_convention=NAMING_CONVENTION
    ) as batch_op:
        batch_op.drop_constraint(
            "uq_session_ops_idempotency_key", type_="unique"
        )


def downgrade():
    with op.batch_alter_table("session_ops", recreate="always") as batch_op:
        batch_op.create_unique_constraint(
            "uq_session_ops_idempotency_key", ["idempotency_key"]
        )

    with op.batch_alter_table("applied_ops", recreate="always") as batch_op:
        batch_op.create_primary_key("pk_applied_ops", ["key"])
        batch_op.alter_column(
            "session_id", existing_type=sa.Integer(), nullable=True
        )
