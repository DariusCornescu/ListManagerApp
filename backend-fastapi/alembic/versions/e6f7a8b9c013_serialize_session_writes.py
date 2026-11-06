"""serialize session writes

Revision ID: e6f7a8b9c013
Revises: d5e6f7a8b902
Create Date: 2026-09-13

"""

from alembic import op
import sqlalchemy as sa


revision = "e6f7a8b9c013"
down_revision = "d5e6f7a8b902"
branch_labels = None
depends_on = None

NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


def upgrade():
    op.add_column(
        "global_sessions",
        sa.Column(
            "next_op_seq",
            sa.Integer(),
            nullable=False,
            server_default="1",
        ),
    )

    connection = op.get_bind()
    duplicate = connection.execute(
        sa.text(
            """
            SELECT session_id, seq, COUNT(*) AS duplicate_count
            FROM session_ops
            GROUP BY session_id, seq
            HAVING COUNT(*) > 1
            LIMIT 1
            """
        )
    ).mappings().first()
    if duplicate is not None:
        raise RuntimeError(
            "Cannot enforce unique session operation sequences: "
            f"session {duplicate['session_id']} has sequence "
            f"{duplicate['seq']} {duplicate['duplicate_count']} times"
        )

    connection.execute(
        sa.text(
            """
            UPDATE global_sessions
            SET next_op_seq = (
                SELECT COALESCE(MAX(session_ops.seq), 0) + 1
                FROM session_ops
                WHERE session_ops.session_id = global_sessions.id
            )
            """
        )
    )

    if connection.dialect.name == "sqlite":
        with op.batch_alter_table(
            "session_ops",
            recreate="always",
            naming_convention=NAMING_CONVENTION,
        ) as batch_op:
            batch_op.drop_index("ix_session_ops_session_id_seq")
            batch_op.create_unique_constraint(
                "uq_session_ops_session_id_seq",
                ["session_id", "seq"],
            )
    else:
        op.drop_index("ix_session_ops_session_id_seq", table_name="session_ops")
        op.create_unique_constraint(
            "uq_session_ops_session_id_seq",
            "session_ops",
            ["session_id", "seq"],
        )


def downgrade():
    connection = op.get_bind()
    if connection.dialect.name == "sqlite":
        with op.batch_alter_table(
            "session_ops",
            recreate="always",
            naming_convention=NAMING_CONVENTION,
        ) as batch_op:
            batch_op.drop_constraint(
                "uq_session_ops_session_id_seq",
                type_="unique",
            )
            batch_op.create_index(
                "ix_session_ops_session_id_seq",
                ["session_id", "seq"],
                unique=False,
            )
    else:
        op.drop_constraint(
            "uq_session_ops_session_id_seq",
            "session_ops",
            type_="unique",
        )
        op.create_index(
            "ix_session_ops_session_id_seq",
            "session_ops",
            ["session_id", "seq"],
            unique=False,
        )

    op.drop_column("global_sessions", "next_op_seq")
