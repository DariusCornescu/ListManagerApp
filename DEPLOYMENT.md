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

## Android updates (Firebase App Distribution)

Phones get new versions without a cable. The **Android distribute** workflow
(`.github/workflows/android-distribute.yml`) runs only when started by hand. It points the app at
production, runs the release unit tests, builds a release APK signed with the release key, and
sends it to the `magazin` tester group. Each run gets `versionCode = 100 + run number`, so every
build counts as an update. The APK is not kept on GitHub, because this repository is public.

### One-time setup
1. **Release key.** Create it once, outside the repository, and keep the file and both
   passwords in a password manager. **If the key is lost, no update can be installed over the
   app already on the phones.**
   ```powershell
   & "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v `
     -keystore "$HOME\listmanager-release.jks" -storetype PKCS12 `
     -alias listmanager -keyalg RSA -keysize 4096 -validity 10000 `
     -dname "CN=ListManager"
   ```
   It asks for the passwords. Copy the file as base64 for GitHub:
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\listmanager-release.jks")) | Set-Clipboard
   ```
2. **Firebase.** At <https://console.firebase.google.com>, create a project and add an Android
   app with package `com.darius.listmanager.v2`. Distribution needs no `google-services.json`
   and no SDK in the app. Open App Distribution, create the group `magazin`, and add the email
   of everyone who uses a shop phone. Copy the app ID (`1:...:android:...`) from Project
   settings.
3. **Service account.** In Google Cloud console, IAM, create a service account with the
   **Firebase App Distribution Admin** role, then create a JSON key for it.
4. **GitHub secrets.** In the repository, open Settings, then Secrets and variables, then
   Actions, and add:
   `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (`listmanager`),
   `ANDROID_KEY_PASSWORD`, `FIREBASE_APP_ID`, `FIREBASE_API_KEY` (step 5), and
   `FIREBASE_SERVICE_ACCOUNT_JSON` (the whole JSON file). The workflow stops and lists any that
   are missing.
5. **In-app update prompt.** In Firebase, Project settings, download `google-services.json`. The
   app does not use the file: copy only its API key into GitHub, then delete the file.
   ```powershell
   (Get-Content -Raw "$HOME\Downloads\google-services.json" | ConvertFrom-Json).client[0].api_key[0].current_key | gh secret set FIREBASE_API_KEY --repo DariusCornescu/ListManagerApp
   ```
   In Google Cloud console, APIs & Services, enable the **Firebase App Testers API** for the
   project. If the key is restricted to a list of APIs, add that API to the list. Without it the
   check fails quietly, in the log only, and updates still come through App Tester.

For a local signed build, put the same four `LISTMANAGER_KEYSTORE_PATH`,
`LISTMANAGER_KEYSTORE_PASSWORD`, `LISTMANAGER_KEY_ALIAS` and `LISTMANAGER_KEY_PASSWORD` values in
`android-native/app/secrets.properties` (git-ignored). Without them the release build stays
unsigned.

### Publishing a version
In GitHub, open Actions, then **Android distribute**, then **Run workflow**, and type what changed.
Each tester gets an email or a notification in the Firebase App Tester app.

The app also checks by itself (`util/AppUpdates.kt`), in release builds sent through Firebase only.
When List Manager opens or comes back to the front, at most once an hour, it asks Firebase for a
newer release and shows **Update**; Android then asks to **Install**. About has a **Caută
actualizări** button for anyone who dismissed it. Android always asks before installing an app
from outside Play, so updates are never fully silent. The first check on each phone asks, once,
to sign in with the Google account invited to `magazin`, and to let List Manager install apps.
The first version with this check still installs from App Tester; the prompt appears from the
next version on.

### Moving a phone from the cable install, once
The app installed by cable is signed with this PC's debug key, so Android will not update it
with the release-signed one. On each phone:
1. Open the menu and make sure nothing is waiting to sync. Uninstalling deletes whatever was not
   sent: offline lists, unsent inventory, items in De rezolvat.
2. Uninstall **List Manager**.
3. Install from the Firebase email, then sign in again.

From then on, every new version arrives as an update.

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

## Local development
Runs on SQLite by default (`sqlite:///./listmanager.db`) — copy
`backend-fastapi/.env.example` to `.env`, set `SECRET_KEY` and `ADMIN_PASSWORD`,
then `uvicorn app.main:app --reload` from `backend-fastapi/`.
