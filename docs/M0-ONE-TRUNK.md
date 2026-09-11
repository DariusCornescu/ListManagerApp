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

## Branch cleanup safety

Verified backup: `android-native/.tooling/m0-before-cleanup.bundle` (ignored local file).
Before deleting remote branches, preserve a git bundle of all branch/remote refs,
verify every candidate is integrated, fast-forward development, and publish it.
Use expected-tip leases for deletion so another contributor's new commits cannot
be removed. Do not delete development. Keep at most one local WIP branch.

## Device acceptance — EX-9 (pending)

Known phone: Samsung S25. Android version must be read from the connected device.
No authorized USB device was available at the initial check. Backend destination
is awaiting the user's choice. Do not install an emulator-configured APK as a
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
