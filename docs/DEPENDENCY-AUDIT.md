# Backend dependency audit (EX-47)

Tool: `pip-audit`, run from a throwaway virtual environment against
`backend-fastapi/requirements.txt`, including transitive dependencies.

## Result

| When | Findings |
| --- | --- |
| Before, 2026-11-06 | 43 advisory entries across 7 packages |
| After, 2026-11-06 | **No known vulnerabilities** |

The project's `.venv` pip works (`python -m pip`), so the blocker recorded in
EX-47 no longer applies.

## Triage

| Package | Before | After | Why it mattered | Action |
| --- | --- | --- | --- | --- |
| starlette (via fastapi) | 0.35.1 | 1.10.0 | Web framework; multipart and request handling advisories, some reachable without auth | Upgraded through fastapi |
| fastapi | 0.109.0 | 0.162.0 | ReDoS via form parsing; pins the old starlette | Upgraded |
| python-multipart | 0.0.6 | 0.0.33 | Parses CSV and audio uploads | Upgraded |
| python-jose + ecdsa | 3.3.0 | removed | Token library, with advisories that have **no fixed release** | Replaced by PyJWT 2.15.1 |
| python-dotenv | 1.0.0 | 1.2.4 | Reads `.env` | Upgraded |
| pytest | 7.4.3 | 9.2.0 | Test only, not reachable in production | Upgraded |
| pydantic, pydantic-settings, pytest-asyncio, httpx | various | see `requirements.txt` | No advisories; required by the upgrades above | Upgraded as required |

## Behaviour preserved across the upgrade

- A request with **no token still gets 403**. Newer FastAPI would answer 401, which
  the mobile clients treat as "session expired". `app/auth.py` keeps the old answer.
- HS256 remains pinned on decode, so tokens issued before the switch stay valid.
- `test_route_contract.py` now reads the OpenAPI schema, because `app.routes` nests
  included routers in this FastAPI version. The expected route set did not change.

## After pulling this change

Reinstall the backend dependencies in any existing virtual environment:

```powershell
cd backend-fastapi
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

## Repeating the audit

```powershell
python -m venv audit-venv
.\audit-venv\Scripts\python.exe -m pip install pip-audit
.\audit-venv\Scripts\python.exe -m pip_audit -r requirements.txt
```

There is no CI in this repository yet, so this check runs only when someone runs it.
