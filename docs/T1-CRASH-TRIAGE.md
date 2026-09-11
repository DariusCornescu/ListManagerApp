# T1 crash triage

This is the working record for Linear EX-15 and EX-16. Crash collection and
grouping are implemented, but the available sources contain no real crash
reports yet. An empty pre-deployment dataset is not evidence of crash-free shop
use, so neither issue is complete.

## Collection snapshot - 2026-09-12

| Source | Result |
| --- | --- |
| `backend-fastapi/listmanager.db` | 0 rows in `crash_reports` |
| isolated M0 phone-test database | 0 rows in `crash_reports` |
| Samsung S25, `com.darius.listmanager.v2` pending crash directory | no pending reports |
| Samsung S25, `com.darius.listmanager.v2.preview` pending crash directory | no pending reports |
| Samsung S25 Android crash log buffer | empty |
| production backend | not inspected; no admin credentials are configured in this checkout |

## Ranked signatures

No signature can be ranked from the available data. The top-three fix list is
therefore empty, and EX-16 must wait for observed reports.

The grouped admin endpoint ranks future signatures by:

`triage_score = report count × affected users`

`username` identifies an affected user when present. For anonymous reports,
the device string is the proxy identity; a report with neither value counts as
one identity. The endpoint also returns first/last seen, affected app versions
and devices, and recent expandable instances.

## Gate to resume EX-15 and EX-16

1. Publish the backend revision containing Alembic revision
   `c4d5e6f7a801` and verify `/api/admin/crashes/groups` as an administrator.
2. Start the seven-day observation window only after the migration and endpoint
   are live and the shop phones run a build that uploads pending reports.
3. Record the top signatures from the grouped endpoint, their scores, affected
   versions/devices, and a source-level root-cause hypothesis.
4. Fix the top three observed signatures, with a focused regression test before
   each commit.
5. Restart the seven-day window for each fixed signature. T1 completes only
   when all three remain at zero during real shop use for the full window.
