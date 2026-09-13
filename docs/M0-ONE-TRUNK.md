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

**Historical issue (resolved in the corrective candidate below):** Android displayed an App Compatibility warning at startup:
`libonnxruntime.so`, `libhftokenizer.so`, and `libonnxruntime4j_jni.so` have LOAD
segments that are not 16 KB aligned; `libandroidx.graphics.path.so` is also
reported with an unknown alignment error. Dismissing the warning let the app
reach login. The sentence-embeddings runtime was upgraded rather than removed,
so the existing fuzzy fallback and semantic integration contract remain intact.

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
| Samsung S25 / Android 16 (API 36) | development / USB local | Login and reinstall/restart passed | Not run | Pass: Lapte Zuzu 1L ×2 | Not run | Open: drawer, completion/PDF; device kernel is 4 KB |

M0's tagged-device gate cannot be replaced by unit tests or merely producing an APK.

## Corrective candidate

Real dictation transcribed correctly but returned Unknown: Room rejected products
because their distributor parents had not been cached. Fixed in bf9736b, local
tag m0-candidate-2026-09-11.2. Four new native Room tests failed before the fix;
all 145 preview tests pass afterwards. APK SHA-256:
CD48CAE8C5833423B2E628B26281B09930E26E87F69774B91C168CFE6B3677A2.
The later device retest confirmed the catalog cache fix; the remaining quantity
and segmentation findings are recorded below. The 16 KB warning is still unresolved.

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
This candidate was superseded by the quantity-aware matching and adjacent-fragment
merge corrections in `8b58d13` and `3cd0284`.

## Final quantity and fragment-merge device result

The S25 recognizer returned "două lapte Zuzu" with two hypotheses. The resolver
initially segmented the text into "două lapte" and "Zuzu", matched both fragments
to Lapte Zuzu 1L, then merged them before the write. The app emitted one AutoAdd
and one POST with `product_id=1`, `quantity=2`; the backend returned 200 and the
session screen displayed exactly one `Lapte Zuzu 1L` row with quantity 2.

The verified development APK includes the 168-test Android gate and Preview build
from `4af044e`; SHA-256:
`A4F79BBEE5A1970C53B0F573938C7BC52DC10CA173B431B618838E5237B58FF5`.
M0 remains open for the drawer, completion/PDF, second-phone, and publication
gates.

## Android 16 KB native-library corrective candidate

The original Preview APK contained 4 KB LOAD alignment in the tokenizer and
ONNX libraries. `sentence-embeddings` is now `v6`, built by its upstream project
after moving to NDK r28b, and ONNX Runtime is explicitly resolved to `1.24.3`
instead of the old transitive `1.17.0`.

`tools/check_native_page_alignment.py` parses each ELF program-header table in
the APK and fails if an `arm64-v8a` or `x86_64` LOAD segment is below 16 KB. The
fresh Preview APK passes for all eight relevant libraries; Android Build Tools
35 `zipalign -c -P 16 -v 4` also passes. Four checker tests and the four existing
voice-corpus tool tests pass. Android gates pass with 168 Debug tests, 168
Release tests, and a successful Preview assembly. A focused Preview
instrumentation test also loads `OrtEnvironment` through JNI on the S25 (1/1).

The upgraded APK was installed over the existing Preview on the S25 without
clearing data and completed a cold start without `UnsatisfiedLinkError` or a
process crash. That phone reports `PAGE_SIZE=4096`, so final execution on a true
16 KB device or emulator remains additional compatibility evidence rather than
a blocker for the static APK gate. The APK SHA-256 is
`389163CAB6FEB0F655A5D8FA02A07B5E3FCC5AE5C01E4B171E6A4A524A0CB6F1`.

The startup log also confirms `model.onnx` is not packaged, as documented by the
assets README. Semantic matching therefore continues to use the designed
fuzzy-only fallback; supplying and validating the model is separate work.
