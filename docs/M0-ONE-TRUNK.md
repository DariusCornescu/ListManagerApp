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

Known phone: Samsung S25. Android version must be read from the connected device.
After the user reported connecting the phone, ADB still listed no devices and
Windows reported no Samsung/Android/ADB device. USB detection and debugging
authorization need to be established before reading the Android version.
A local USB backend is the provisional test environment; it has not been started.
Do not install an emulator-configured APK as a
working phone build; 10.0.2.2 is not the PC address on a physical phone.

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
| Samsung S25 / unknown | Pending | Not run | Not run | Not run | Not run | Pending |

M0's tagged-device gate cannot be replaced by unit tests or merely producing an APK.
