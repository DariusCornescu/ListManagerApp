#!/usr/bin/env python3
"""Roll the Render backend back to the previous deploy after a failed smoke test.

  render_rollback.py --failed-sha SHA

Reads RENDER_API_KEY (secret) and RENDER_SERVICE_ID (variable) from the environment, and writes
`action`, `target_sha` and `reason` to $GITHUB_OUTPUT. `action` is one of:
  rolled_back  Render was asked to roll back to the previous live deploy.
  escalate     A person has to decide: the release changed the database schema, there is no
               earlier deploy, or an incident is already open.
  skipped      Not configured (no API key or service id), so nothing was attempted.

Rolling back code across an Alembic migration is unsafe: the database has already moved, and
the older code may not match it. Those releases are escalated, never rolled back.

A rollback through the API keeps automatic deploys on (unlike the dashboard), so the next
green commit on development deploys normally. Standard library only; the API key is never
printed.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import urllib.request
from dataclasses import dataclass

API = "https://api.render.com/v1"
MIGRATIONS_DIR = "backend-fastapi/alembic/versions/"
PREVIOUS_STATES = {"deactivated", "live"}


@dataclass(frozen=True)
class Target:
    deploy_id: str
    sha: str


def select_target(deploys: list[dict], failed_sha: str) -> Target | None:
    """The newest earlier deploy that went live, from Render's newest-first deploy list.

    Render wraps each item as {"deploy": {...}, "cursor": ...}; bare deploy objects are
    accepted too.
    """
    seen_failed = False
    for item in deploys:
        deploy = item.get("deploy", item)
        sha = (deploy.get("commit") or {}).get("id") or ""
        if sha and sha == failed_sha:
            seen_failed = True
            continue
        if not seen_failed:
            continue  # newer than the failing deploy (for example a deploy already queued)
        if deploy.get("status") in PREVIOUS_STATES and sha:
            return Target(deploy["id"], sha)
    return None


def migration_files(changed_paths: list[str]) -> list[str]:
    return [path for path in changed_paths if path.startswith(MIGRATIONS_DIR) and path.endswith(".py")]


def decide(target: Target | None, changed_paths: list[str], incident_open: bool) -> tuple[str, str]:
    if incident_open:
        return "escalate", "an incident is already open; not rolling back again automatically"
    if target is None:
        return "escalate", "no earlier live deploy found to roll back to"
    migrations = migration_files(changed_paths)
    if migrations:
        return "escalate", f"this release changed the database schema ({len(migrations)} migration file(s))"
    return "rolled_back", f"rolled back to {target.sha[:7]}"


def request(method: str, path: str, key: str, body: dict | None = None) -> object:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        f"{API}{path}", data=data, method=method,
        headers={"Authorization": f"Bearer {key}", "Accept": "application/json", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.loads(response.read() or b"null")


def changed_since(old_sha: str, new_sha: str) -> list[str]:
    out = subprocess.run(
        ["git", "diff", "--name-only", f"{old_sha}..{new_sha}"],
        check=True, capture_output=True, text=True, encoding="utf-8",
    ).stdout
    return [line for line in out.splitlines() if line]


def write_outputs(**values: str) -> None:
    for name, value in values.items():
        print(f"{name}={value}")
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            for name, value in values.items():
                handle.write(f"{name}={value}\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--failed-sha", required=True)
    parser.add_argument("--incident-open", action="store_true", help="an incident issue is already open")
    args = parser.parse_args(argv)

    key = os.environ.get("RENDER_API_KEY", "")
    service = os.environ.get("RENDER_SERVICE_ID", "")
    if not key or not service:
        write_outputs(action="skipped", target_sha="", reason="RENDER_API_KEY or RENDER_SERVICE_ID is not set")
        print("::warning::Rollback is not configured: set the RENDER_API_KEY secret and RENDER_SERVICE_ID variable.")
        return 0

    deploys = request("GET", f"/services/{service}/deploys?limit=20", key)
    target = select_target(deploys if isinstance(deploys, list) else [], args.failed_sha)
    changed = changed_since(target.sha, args.failed_sha) if target else []
    action, reason = decide(target, changed, args.incident_open)
    if action == "rolled_back":
        request("POST", f"/services/{service}/rollback", key, {"deployId": target.deploy_id})
    write_outputs(action=action, target_sha=target.sha if target else "", reason=reason)
    return 0


if __name__ == "__main__":
    # Results can hold non-ASCII (emoji, Romanian test names); a Windows console may not.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
