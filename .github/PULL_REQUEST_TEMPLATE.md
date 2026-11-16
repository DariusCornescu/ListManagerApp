## What and why
<!-- What changes, and the problem it solves. Link the Linear issue (EX-…) if there is one. -->

## Label
<!-- One of these; see DEPLOYMENT.md, "Pull requests: labels and auto-merge". -->
- [ ] `auto-merge`: documentation, conflict-only merge, CI fix that doesn't touch production, Dependabot
- [ ] `needs-review`: everything else (UI, behaviour, schema, auth, deploys, signing, new dependencies)

## How it was tested
<!-- Commands you ran and their results. "Not run" is a valid answer if you say why. -->
- [ ] Backend: `pytest -q`
- [ ] Android: `./gradlew testDebugUnitTest testReleaseUnitTest lintPreview assemblePreview`
- [ ] Flutter: `flutter analyze && flutter test`
- [ ] On a phone:

## Mobile compatibility
<!-- CLAUDE.md: existing client endpoints keep working. A breaking change needs a migration note in docs/PROGRESS.md. -->
- [ ] No API change, or only additive changes
- [ ] Database migration: works with the app version already on the phones (N-1)

## UI
<!-- A UI change needs before and after images. Say where they come from: a test render does not prove how it looks on a phone. -->

## Checklist
- [ ] `docs/PROGRESS.md` updated
- [ ] No secrets, keystores or `.env` files
- [ ] No AI attribution in commits or this description
