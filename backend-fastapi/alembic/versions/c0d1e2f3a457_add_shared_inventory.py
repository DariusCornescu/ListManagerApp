"""add shared inventory

Revision ID: c0d1e2f3a457
Revises: b9c0d1e2f346
Create Date: 2026-11-06

"""

from alembic import op
import sqlalchemy as sa


revision = "c0d1e2f3a457"
down_revision = "b9c0d1e2f346"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "inventories",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("owner_user_id", sa.Integer(), nullable=True),
        sa.Column("team_id", sa.Integer(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=True),
        sa.CheckConstraint(
            "(owner_user_id IS NULL) != (team_id IS NULL)",
            name=op.f("ck_inventories_ck_inventory_one_owner"),
        ),
        sa.ForeignKeyConstraint(
            ["owner_user_id"], ["users.id"], name=op.f("fk_inventories_owner_user_id_users")
        ),
        sa.ForeignKeyConstraint(
            ["team_id"], ["teams.id"], name=op.f("fk_inventories_team_id_teams")
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_inventories")),
        sa.UniqueConstraint("owner_user_id", name=op.f("uq_inventories_owner_user_id")),
        sa.UniqueConstraint("team_id", name=op.f("uq_inventories_team_id")),
    )
    op.create_index(op.f("ix_inventories_id"), "inventories", ["id"], unique=False)

    op.create_table(
        "inventory_items",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("inventory_id", sa.Integer(), nullable=False),
        sa.Column("item_uuid", sa.String(length=36), nullable=False),
        sa.Column("name", sa.String(length=200), nullable=False),
        sa.Column("product_id", sa.Integer(), nullable=True),
        sa.Column("quantity", sa.Float(precision=53), nullable=True),
        sa.Column("price_bani", sa.BigInteger(), nullable=True),
        sa.Column("version", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("created_at", sa.DateTime(), nullable=True),
        sa.Column("updated_at", sa.DateTime(), nullable=True),
        sa.Column("deleted_at", sa.DateTime(), nullable=True),
        sa.ForeignKeyConstraint(
            ["inventory_id"],
            ["inventories.id"],
            name=op.f("fk_inventory_items_inventory_id_inventories"),
        ),
        sa.ForeignKeyConstraint(
            ["product_id"],
            ["products.id"],
            name=op.f("fk_inventory_items_product_id_products"),
            ondelete="SET NULL",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_inventory_items")),
        sa.UniqueConstraint("inventory_id", "item_uuid", name="uq_inventory_item_uuid"),
    )
    op.create_index(op.f("ix_inventory_items_id"), "inventory_items", ["id"], unique=False)
    op.create_index(
        op.f("ix_inventory_items_inventory_id"), "inventory_items", ["inventory_id"], unique=False
    )

    op.create_table(
        "inventory_applied_ops",
        sa.Column("key", sa.String(length=64), nullable=False),
        sa.Column("inventory_id", sa.Integer(), nullable=False),
        sa.Column("result_json", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=True),
        sa.ForeignKeyConstraint(
            ["inventory_id"],
            ["inventories.id"],
            name=op.f("fk_inventory_applied_ops_inventory_id_inventories"),
        ),
        sa.PrimaryKeyConstraint("key", "inventory_id", name=op.f("pk_inventory_applied_ops")),
    )


def downgrade():
    op.drop_table("inventory_applied_ops")
    op.drop_index(op.f("ix_inventory_items_inventory_id"), table_name="inventory_items")
    op.drop_index(op.f("ix_inventory_items_id"), table_name="inventory_items")
    op.drop_table("inventory_items")
    op.drop_index(op.f("ix_inventories_id"), table_name="inventories")
    op.drop_table("inventories")
