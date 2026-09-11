"""add crash signature

Revision ID: c4d5e6f7a801
Revises: 9819cacf8390
Create Date: 2026-09-12

"""

import hashlib
import re

from alembic import op
import sqlalchemy as sa


revision = "c4d5e6f7a801"
down_revision = "9819cacf8390"
branch_labels = None
depends_on = None

_LINE_NUMBER = re.compile(r":\d+(?=\))")
_ANONYMOUS_CLASS = re.compile(r"\$\d+(?=[.$])")


def _signature(stacktrace: str) -> str:
    lines = [line.strip() for line in stacktrace.splitlines() if line.strip()]
    if not lines:
        canonical = "unknown"
    else:
        exception_type = lines[0].split(":", 1)[0].strip()
        frames = []
        for line in lines[1:]:
            if not line.startswith("at "):
                continue
            normalized = _LINE_NUMBER.sub(":#", line)
            frames.append(_ANONYMOUS_CLASS.sub("$#", normalized))
            if len(frames) == 8:
                break
        canonical = "\n".join([exception_type, *frames])
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def upgrade():
    op.add_column(
        "crash_reports",
        sa.Column("signature", sa.String(length=64), nullable=True),
    )
    op.create_index(
        "ix_crash_reports_signature",
        "crash_reports",
        ["signature"],
        unique=False,
    )

    crash_reports = sa.table(
        "crash_reports",
        sa.column("id", sa.Integer()),
        sa.column("stacktrace", sa.Text()),
        sa.column("signature", sa.String(length=64)),
    )
    connection = op.get_bind()
    rows = connection.execute(
        sa.select(crash_reports.c.id, crash_reports.c.stacktrace)
    ).all()
    for report_id, stacktrace in rows:
        connection.execute(
            crash_reports.update()
            .where(crash_reports.c.id == report_id)
            .values(signature=_signature(stacktrace))
        )


def downgrade():
    op.drop_index("ix_crash_reports_signature", table_name="crash_reports")
    op.drop_column("crash_reports", "signature")
