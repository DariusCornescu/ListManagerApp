# Deployment — hosting the backend on Render

This guide gets the FastAPI backend running publicly (HTTPS) with a Postgres
database, so the Android app works across phones anywhere — not just on the
local network.

The repo already contains everything needed:
- `backend-fastapi/Procfile` — start command for Render.
- `backend-fastapi/render.yaml` — Blueprint provisioning Postgres + the web service.
- `backend-fastapi/runtime.txt` — pins Python 3.11.9.
- Startup runs Alembic migrations automatically (`app/main.py`), so the schema is
  created on a fresh database with no manual step.
- Seeding is idempotent (`seed.py`, `seed_admin.py`) — safe on every boot.

## One-time deploy (Render dashboard)

1. **Push this branch** to GitHub (`feat/backend-deploy`, or merge it first).
2. Go to <https://dashboard.render.com> → **New → Blueprint**.
3. **Connect** this GitHub repo and select the branch. Render reads
   `backend-fastapi/render.yaml`.
4. When prompted for the `sync: false` variables, set:
   - `ADMIN_PASSWORD` — the password for the seeded `admin` account (pick a strong one).
   - *(optional)* `GROQ_API_KEY` — only if you use server-side transcription.
   `SECRET_KEY` is generated automatically; `DATABASE_URL` is wired from the DB.
5. Click **Apply**. Render provisions the paid Postgres, builds, runs migrations
   at startup, and starts the service.
6. Wait for the service to go **Live**, then verify:
   ```
   curl https://listmanager-api.onrender.com/health      # {"status":"healthy"}
   curl https://listmanager-api.onrender.com/api/stats   # aggregate counts
   ```

## Android

If the Render service is named **`listmanager-api`** (as in `render.yaml`), its
URL is `https://listmanager-api.onrender.com` — which is already the default
`BuildConfig.BASE_URL`, so **no app change is needed**. Just rebuild the APK.

If you used a different service name, set it in
`android-native/app/secrets.properties` (gitignored):
```
LISTMANAGER_BASE_URL=https://<your-service-name>.onrender.com/
```
The WebSocket URL (`wss://…/ws`) is derived automatically from this.

## Verify end-to-end
1. Log in on **two phones** with `admin` / your `ADMIN_PASSWORD`.
2. Add a product to the session on phone A → it appears on phone B in real time (WebSocket).
3. Toggle airplane mode on A, add offline, reconnect → the offline queue syncs.

## Plans and cost
`render.yaml` uses paid plans, on a free Hobby workspace (estimates, November 2026;
check Render's pricing page):
- Web service `0.5c-512mb`: $7/month.
- Postgres `0.1c-256mb`: about $6/month, plus 5 GB of disk at $0.30/GB. Disk can
  only grow.

Avoid the free plans for real use:
- The free web service **sleeps after ~15 min idle**; the first request after
  that takes 50s or more to wake, and live updates drop.
- The free Postgres **expires after 30 days, with a 14-day grace period before it
  is deleted** (per Render's articles in November 2026). The data is lost and the
  service then crashes at startup.

## Backups

Render keeps 3 days of point-in-time recovery on a Hobby workspace (7 on Pro), and none of it
survives the database being deleted, which already happened once. So `render.yaml` adds a cron
job, `listmanager-db-backup`, that dumps the database every day at 02:00 UTC and stores the
dump in Cloudflare R2. The job is `ops/db-backup/` (`Dockerfile` and `backup.sh`).

The job reads the server's version and dumps with the matching client. It dumps to a local file
first and checks it with `pg_restore --list`, including that
`users`, `products` and `global_sessions` have data. Only then does it upload, and it confirms
the stored size. A failed step uploads nothing and fails the run. The log never shows the
connection string or keys.

### One-time setup
1. **Cloudflare:** enable R2 and create a private bucket, for example `listmanager-backups`.
2. **R2 API token:** create one with **Object Read & Write** on that bucket only. Note the
   Account ID, Access Key ID and Secret Access Key. They are shown once.
3. **Retention:** on the bucket, add a lifecycle rule that deletes objects after 90 days.
   Optional: a bucket lock rule, so a leaked key cannot delete recent backups.
4. **Render:** after the Blueprint syncs, open `listmanager-db-backup` → Environment and set
   `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` and `R2_BUCKET`. Until then
   each run fails with `missing settings: ...`, and nothing else happens.
5. **First run:** click **Trigger Run**. The log should end with
   `backup ok: listmanager/YYYY/MM/DD/listmanager-<time>-pgNN.dump (<size> bytes, PostgreSQL NN)`,
   and the file
   should be in the bucket. In Render's account settings, turn on notifications for failed jobs.

Anyone with the R2 key can read the whole database, users and password hashes included. Keep
the bucket private and the token limited to it.

### Restore
Never restore over production. Restore into a new, empty database, check it, then point the app
at it, or copy back only what is missing.

The dump is in PostgreSQL's custom format. The job dumps with the client that matches the
server, and the file name ends in the version, for example `-pg16.dump`. Restore with the same
`pg_restore` version into a server of that version: a newer client's output can fail on an older
server. The backup image has clients 16 to 18 in `/usr/libexec/postgresqlNN`:

```bash
docker build -t listmanager-db-backup ops/db-backup
docker run --rm -it --entrypoint sh \
  -e AWS_ACCESS_KEY_ID=<key id> -e AWS_SECRET_ACCESS_KEY=<secret> -e AWS_DEFAULT_REGION=auto \
  -e TARGET_URL='postgresql://<user>:<password>@<host>:5432/<new empty database>' \
  listmanager-db-backup -c '
    aws s3 cp s3://<bucket>/listmanager/YYYY/MM/DD/<file>-pgNN.dump /tmp/restore.dump \
      --endpoint-url https://<account id>.r2.cloudflarestorage.com &&
    /usr/libexec/postgresqlNN/pg_restore --no-owner --no-privileges --exit-on-error \
      -d "$TARGET_URL" /tmp/restore.dump'
```

Then compare row counts for `users`, `products`, `distributors` and `global_sessions` with what
you expect. The restored `alembic_version` holds the schema revision. The app's startup
migrations take it from there.

### Monthly restore test
Once a month, restore the latest backup into a local Postgres of the same version as the file
name (`docker run -d -p 5433:5432 -e POSTGRES_PASSWORD=<pw> postgres:NN-alpine`) with the
command above, and check the row counts. A backup that has never been restored is not proven.

### Cost
A Render cron job is billed per minute it runs, with a small monthly minimum (about $1). R2 is
free up to 10 GB stored, and the dump is a few MB. Check both pricing pages.

## CI/CD

```
pull request ──> CI (only the changed parts) ──> ci-ok ──> merge (your call)
development  ──> CI (everything) ──> all checks pass ──> Render deploys ──> Deploy smoke test
```

**CI** (`.github/workflows/ci.yml`) runs on every pull request and on every push to
`development`:
- `changes` decides which jobs a pull request needs:
  - `backend-fastapi/**` runs `backend`: pip-audit, then pytest on SQLite and a real PostgreSQL 17;
  - `android-native/**` runs `android`: unit tests and the preview build;
  - `flutter_ui/**` runs `flutter`: analyze and test;
  - `.github/**` runs `actionlint`, which also runs shellcheck.

  A change to `ci.yml` runs all of them, and a push to `development` always runs all of them.
- `ci-ok` passes when every other job passed or was skipped. It is the only check branch
  protection needs, so a docs-only pull request can still merge.
- pip, Gradle and the Flutter SDK are cached. Actions are pinned to commit SHAs. Dependabot
  (`.github/dependabot.yml`) opens one grouped pull request a week when they have updates.

**Deploy.** `render.yaml` sets `autoDeployTrigger: checksPass`, so Render deploys a
`development` commit only after every GitHub check on it has passed (skipped counts as passed).
A commit with a failed check is not deployed. Fix forward with a new commit, or start
**Manual Deploy** in the Render dashboard.

**Deploy smoke test** (`.github/workflows/deploy-smoke.yml`). Render posts each deploy to
GitHub as a deployment in the environment `development - listmanager-api`. When it reaches its
final state, the workflow checks the commit that just went live:
- it fails if Render reports the deploy as failed. Production then still runs the previous
  version.
- it fails if `/health` is not `healthy` within 3 minutes.
- it fails if `/api/stats` cannot read the database.

The result shows as a check on that commit and in the Actions tab. GitHub emails you about
failed runs if notifications for Actions are on.

**Android** builds reach the phones only through **Android distribute**, which you start by hand.

### Branch protection (one-time, by you)
Protect `development` once this is merged, and after `ci-ok` has run at least once.
The settings below do three things:
- every change needs a pull request, with no approval required, since you work alone;
- `ci-ok` must be green before a merge;
- force pushes and branch deletion are blocked.

`enforce_admins: true` applies all of this to you as well. Set it to `false` if you want an
emergency bypass.

```bash
gh api -X PUT repos/DariusCornescu/ListManagerApp/branches/development/protection --input - <<'EOF'
{
  "required_status_checks": { "strict": false, "checks": [{ "context": "ci-ok" }] },
  "enforce_admins": true,
  "required_pull_request_reviews": { "required_approving_review_count": 0 },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
EOF
```

Check it: `gh api repos/DariusCornescu/ListManagerApp/branches/development/protection`.

## Local development
Runs on SQLite by default (`sqlite:///./listmanager.db`) — copy
`backend-fastapi/.env.example` to `.env`, set `SECRET_KEY` and `ADMIN_PASSWORD`,
then `uvicorn app.main:app --reload` from `backend-fastapi/`.
