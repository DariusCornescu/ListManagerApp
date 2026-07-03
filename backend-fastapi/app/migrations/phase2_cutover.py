"""Phase 2 clean-cutover migration for the team-sessions feature.

WARNING: THIS DESTROYS EXISTING SESSION DATA BY DESIGN.

The Phase 2 schema adds ``owner_user_id`` and ``team_id`` columns to the
``global_sessions`` table so that every session is owned by exactly one of a
user or a team. Rather than back-fill ownership onto pre-existing rows (which
are ownerless and therefore meaningless under the new model), the human made an
explicit WIPE / CLEAN CUTOVER decision: drop the old session tables entirely so
that the subsequent ``models.Base.metadata.create_all()`` recreates them with
the new columns. All previously stored global sessions and their items are
permanently deleted. The new team tables are created by ``create_all`` and need
no action here.

This routine is intended to run once at application startup, BEFORE
``create_all``. It is written to be idempotent and safe to call repeatedly: if
the old tables are absent (already migrated / fresh database) it does nothing.
"""

from sqlalchemy import inspect, text

# Children first so foreign keys do not block the drop.
_TABLES_TO_DROP = ("global_session_items", "global_sessions")


def run_phase2_cutover(engine):
    """Perform the Phase 2 clean cutover for the given SQLAlchemy engine.

    Introspects the database and, if the legacy ``global_sessions`` table
    exists, drops both ``global_session_items`` and ``global_sessions`` (in
    that order) using raw DDL. A later ``Base.metadata.create_all()`` then
    recreates them with the new ``owner_user_id`` / ``team_id`` columns and
    leaves no stale ownerless session/item rows behind.

    Idempotent: if ``global_sessions`` does not exist there is nothing to do,
    so the function returns without touching the database. Safe to call on
    every startup.

    Returns:
        bool: True if a drop was performed (a real cutover happened), False if
        the database was already in the post-cutover state and nothing changed.
    """
    inspector = inspect(engine)
    existing_tables = set(inspector.get_table_names())

    # Only act when the legacy session table is present. On a fresh or
    # already-migrated database there is nothing to wipe.
    if "global_sessions" not in existing_tables:
        return False

    with engine.begin() as connection:
        for table_name in _TABLES_TO_DROP:
            # Guard each drop individually so a partially-migrated state (e.g.
            # only the parent table left) is still handled cleanly. IF EXISTS
            # keeps the raw DDL safe and idempotent on SQLite and Postgres.
            if table_name in existing_tables:
                connection.execute(text(f'DROP TABLE IF EXISTS "{table_name}"'))

    return True
