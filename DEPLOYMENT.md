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

## Local development
Runs on SQLite by default (`sqlite:///./listmanager.db`) — copy
`backend-fastapi/.env.example` to `.env`, set `SECRET_KEY` and `ADMIN_PASSWORD`,
then `uvicorn app.main:app --reload` from `backend-fastapi/`.
