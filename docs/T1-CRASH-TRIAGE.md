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

## Controlled S25 validation - 2026-09-13

The Preview build includes an ADB-only crash activity that is absent from the
normal application variants. On a Samsung S25 (`SM-S931B`, Android 16), three
rapid controlled crashes produced a streak of 3. The next cold start:

- reset the streak to 0;
- replaced the Room database (inode `2507903` became `2486338`);
- uploaded every pending crash file and left the device queue empty; and
- grouped all three reports under one normalized signature.

The first run exposed an at-least-once delivery duplicate: three local files
created four server rows when a request reached the server just before Android
killed the process. Crash files now carry stable UUID-based event IDs and the
server enforces a unique nullable `event_id`. Repeating the same device test
then produced exactly 3 reports, 3 event IDs, and 1 signature.

This validates telemetry delivery, grouping, retry idempotency, and crash-loop
self-healing on the target phone. The controlled signature is test evidence and
must not be counted as a real shop crash for EX-15 or EX-16.

## Gate to resume EX-15 and EX-16

1. Publish the backend revision containing Alembic revision
   `d5e6f7a8b902` and verify `/api/admin/crashes/groups` as an administrator.
2. Start the seven-day observation window only after the migration and endpoint
   are live and the shop phones run a build that uploads pending reports.
3. Record the top signatures from the grouped endpoint, their scores, affected
   versions/devices, and a source-level root-cause hypothesis.
4. Fix the top three observed signatures, with a focused regression test before
   each commit.
5. Restart the seven-day window for each fixed signature. T1 completes only
   when all three remain at zero during real shop use for the full window.
