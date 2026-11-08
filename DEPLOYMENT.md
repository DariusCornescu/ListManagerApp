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
   `ANDROID_KEY_PASSWORD`, `FIREBASE_APP_ID`, and `FIREBASE_SERVICE_ACCOUNT_JSON` (the whole
   JSON file). The workflow stops and lists any that are missing.

For a local signed build, put the same four `LISTMANAGER_KEYSTORE_PATH`,
`LISTMANAGER_KEYSTORE_PASSWORD`, `LISTMANAGER_KEY_ALIAS` and `LISTMANAGER_KEY_PASSWORD` values in
`android-native/app/secrets.properties` (git-ignored). Without them the release build stays
unsigned.

### Publishing a version
In GitHub, open Actions, then **Android distribute**, then **Run workflow**, and type what changed.
Each tester gets an email or a notification in the Firebase App Tester app, and taps to update.
Updates are not installed by themselves.

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

## Local development
Runs on SQLite by default (`sqlite:///./listmanager.db`) — copy
`backend-fastapi/.env.example` to `.env`, set `SECRET_KEY` and `ADMIN_PASSWORD`,
then `uvicorn app.main:app --reload` from `backend-fastapi/`.
