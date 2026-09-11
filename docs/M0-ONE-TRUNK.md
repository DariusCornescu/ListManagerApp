# M0 — One trunk acceptance record

M0 is not complete until the device acceptance section is filled with observed results.

## Integration

- N-best/semantic work is already in development (498a698); the ONNX rollback is preserved.
- Drawer scrolling fix f9f2b98 is already in development.
- Admin catalog CRUD is reapplied on the current reliability branch from
  733b807, 45c46cc, 4f0a1d6. Only the progress-log merge required resolution;
  both histories were retained. Includes the product-price persistence regression.
- Reliability M1–M4 remain included. No production API route or DB schema changed.
- Backend full suite: 241 passed, 197 warnings, 313.61s.
- Android testDebugUnitTest + assembleDebug: successful; existing 141-test result
  reused by Gradle because Android inputs were unchanged.
- Fresh preview gate: testPreviewUnitTest + assemblePreview passed (141 tests,
  zero failures/errors; 46 tasks executed, 1m 55s).
- Local development is now 7a5edc7. Both admin branch histories are integrated;
  ancestry-only merges introduced no additional code changes.
- Local tag: `m0-candidate-2026-09-11` at 7a5edc7. Device acceptance is pending.
- Preview APK: `android-native/app/build/outputs/apk/preview/app-preview.apk`;
  SHA-256 `B70ED58A75D6149C76B8094246720294E220B7057D3EAF19AC40EBD716D7EBA9`.
  Package `com.darius.listmanager.v2.preview`, label ListManager NOU. The ignored
  local ApiConfig uses `http://127.0.0.1:8000/`; USB testing requires a local
  backend and `adb reverse tcp:8000 tcp:8000`. Neither installation nor phone
  smoke testing has happened.

## Branch cleanup safety

Verified backup: `android-native/.tooling/m0-before-cleanup.bundle` (ignored local file).
Before deleting remote branches, preserve a git bundle of all branch/remote refs,
verify every candidate is integrated, fast-forward development, and publish it.
Use expected-tip leases for deletion so another contributor's new commits cannot
be removed. Do not delete development. Keep at most one local WIP branch.

Publishing is blocked by missing GitHub credentials. Remote development remains
3f61f1c; no remote branches were deleted and the candidate tag is local only.
The device-authentication attempt produced no code and was stopped. Local
branches are development plus one reliability branch.

## Device acceptance — EX-9 (pending)

Connected phone verified: Samsung Galaxy S25 (SM-S931B), Android 16 / API 36.
USB debugging is authorized after the Samsung driver setup.

The tagged preview APK was installed successfully for user 0 alongside the daily
app. USB reverse forwards port 8000 to a loopback-only local backend with its own
ignored SQLite database (`android-native/.tooling/m0-phone.sqlite`). The backend
health endpoint passes. Login with a seeded local test account succeeded and the
main recording/session screen rendered; the preview process remained running.
No production backend or daily-app data was used.

**Observed issue:** Android displays an App Compatibility warning at startup:
`libonnxruntime.so`, `libhftokenizer.so`, and `libonnxruntime4j_jni.so` have LOAD
segments that are not 16 KB aligned; `libandroidx.graphics.path.so` is also
reported with an unknown alignment error. Dismissing the warning with OK lets
the app reach login. The warning has not been suppressed or fixed. The
sentence-embeddings dependency is still referenced by EmbeddingModel; removing
it without adapting that implementation would break compilation.

Real dictation requested from the user: "doua lapte Zuzu" against the local
catalog's "Lapte Zuzu 1L". No pass is recorded until the outcome is observed.
Restart persistence, drawer scrolling and completion/PDF checks remain pending.

Run on each supported real phone and record device model, Android version,
app version/tag, backend environment, time and outcome:

1. Install the candidate without deleting the daily app's data. Prefer the
   existing preview variant for an isolated local smoke test; a daily-app update
   additionally requires compatible signing and its own recorded acceptance.
2. Open and sign in. Check that application initialization completes and the
   catalog/session loads. Close/reopen and verify login persists.
3. Open the drawer and reach its bottom entries by scrolling.
4. Dictate a known catalog product and quantity. Confirm the match and displayed
   quantity; add another item and correct a quantity.
5. Complete the session, generate the distributor PDFs and open them. Verify
   product names, quantities and grouping against the list used in the test.
6. Restart and confirm the completed session is retained and the new active list
   is usable. Record any crash or permission problem.

Evidence table (do not pre-fill Pass):

| Device / Android | Candidate tag / environment | Login/restart | Drawer | Dictate/match | Complete/PDF | Result |
| --- | --- | --- | --- | --- | --- | --- |
| Samsung S25 / Android 16 (API 36) | m0-candidate-2026-09-11 / USB local | Login passed; restart pending | Not run | Awaiting real dictation | Not run | Open: 16 KB warning and remaining flow |

M0's tagged-device gate cannot be replaced by unit tests or merely producing an APK.

## Corrective candidate

Real dictation transcribed correctly but returned Unknown: Room rejected products
because their distributor parents had not been cached. Fixed in bf9736b, local
tag m0-candidate-2026-09-11.2. Four new native Room tests failed before the fix;
all 145 preview tests pass afterwards. APK SHA-256:
CD48CAE8C5833423B2E628B26281B09930E26E87F69774B91C168CFE6B3677A2.
Device retest remains pending; 16 KB warning is still unresolved.

## Spoken quantity corrective candidate

The first catalog-fixed retest transcribed "două lapte Zuzu" correctly, but the
segmenter split it into "două lapte" and "Zuzu". This produced one review entry
and one automatic addition with quantity 1. The test exposed two missing pieces:
the add paths ignored spoken quantities, and quantity prefixes could be separated
from their product name.

Commits c7e8a49 and ef8b3cf preserve a leading spoken quantity through automatic
and reviewed additions and keep it with the matching product segment. Added tests
cover Romanian words, numeric prefixes, product measurements, and multiple
quantity/product pairs. Full preview gate: 151 tests, zero failures/errors;
assemblePreview passed. Local tag `m0-candidate-2026-09-12.4`; APK SHA-256
`F3D304FD0D8949C14F335BB60AEFBF6C89DEAB5E0B5CB873710EFD11BDDC36B8`.
The build is installed on the S25. A clean device retest remains pending.
