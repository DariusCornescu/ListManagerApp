# Workflows

The design follows the KlusAI webos pipeline, cut down to what ListManager needs: one backend on
Render, an Android app sent through Firebase App Distribution, and a Flutter client. How to use it
day to day (labels, releases, rollback setup) is in [`DEPLOYMENT.md`](../../DEPLOYMENT.md#cicd).

| File | Name | What it proves or does |
|---|---|---|
| `ci.yml` | `CI` | Every pull request and every `development` commit: tests, lint, builds, hygiene. `ci-ok` is the one required check. |
| `auto-merge.yml` | `Bot: auto-merge` | Merges a pull request labelled `auto-merge` once `ci-ok` is green, then starts CI on `development`. |
| `deploy-smoke.yml` | `Post-deploy: smoke test and rollback` | After Render deploys: checks `/health` and `/api/stats`, rolls back or escalates when they fail. |
| `release.yml` | `Release: tag and notes` | Tags a green `development` commit, writes release notes, ships Android. |
| `android-distribute.yml` | `Release: Android distribute` | Signs the release APK and sends it to the shop phones. Also called by `release.yml`. |

## Naming

GitHub sorts the Actions sidebar by a workflow's `name:`, so the name is the only grouping there
is. Every workflow is named `<Category>: <what it proves>`:

| Category | Meaning |
|---|---|
| `Gate:` | Blocks a pull request. |
| `Bot:` | Acts on a pull request on our behalf. |
| `Post-deploy:` | Runs after Render reports a deploy. |
| `Release:` | Tags or ships. |
| `Manual:` | `workflow_dispatch` only: an operator tool. |

Rules, from webos:
- **Quote the name.** `name: "Bot: auto-merge"`. An unquoted value containing `: ` is a YAML error.
- **The name states the capability and stops.** Scope and reasons go in the header comment.
- **`CI` is exempt.** `auto-merge.yml` listens for it with `workflow_run: workflows: [CI]`.
  Renaming it would silently stop auto-merge.

## What is safe to rename

| Thing | Safe? | Why |
|---|---|---|
| Workflow `name:` | Yes, except `CI` | Run history survives a rename. |
| Workflow file name | No | GitHub treats it as a new workflow, and `release.yml` calls `android-distribute.yml` by path. |
| Job id | No for `ci-ok` | Branch protection requires the `ci-ok` check. Renaming it leaves every open pull request waiting on a check that never reports. `auto-merge.yml` and `release.yml` also read `ci-ok` by name. |

## Gates that a green job is not enough for

These are the webos rules ListManager adopts:
- **A skipped or empty suite is not a pass.** After each test run, `.github/scripts/check_test_report.py`
  reads the JUnit (or Flutter JSON) results and fails on zero tests, a failure, or a skip not
  listed in [`.github/test-skips.txt`](../test-skips.txt). Each line there has to say why. The
  counts go to the run summary.
- **No committed secrets.** `check_committed_secrets.py` runs on every event. It rejects keystores,
  `google-services.json`, `.env`, the git-ignored `ApiConfig.kt` and token-shaped strings. The
  repository is public, so a pushed secret has to be rotated, not just deleted. Only the path and
  the kind of finding are logged.
- **No AI attribution.** `check_ai_attribution.py` fails a pull request whose commits or
  description credit an AI tool, such as a `Co-Authored-By:` trailer or a "generated with" line.
- **Scripts are tested.** `.github/scripts/tests` runs whenever `.github/` changes.

## Alerts

- **`development-red`.** `ci.yml` opens one issue when CI fails on `development` and closes it on
  the next green run.
- **`production-incident`.** `deploy-smoke.yml` opens one issue when a deploy fails its checks,
  and says whether it rolled back.

GitHub emails these to the repository owner when issue notifications are on.
