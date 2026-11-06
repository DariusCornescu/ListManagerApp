# List Manager Flutter client

A second client for the List Manager backend, for Android, iOS, the web and desktop.
**The native Android app (`android-native/`) stays the complete client.**

| | Flutter | Android native |
| --- | --- | --- |
| Sign-in, refresh, logout | Yes | Yes |
| Profile | Yes | Yes |
| Catalog browsing, admin editing | Yes | Yes |
| Shared list: add, quantity, remove, clear, complete | Yes | Yes |
| Live updates, conflict resolution | Yes | Yes |
| Voice input and on-device ranking | No | Yes |
| Offline queue | No: a failed write says it was not saved | Yes |
| PDF generation | No | Yes |
| Teams and workspace switching | No: uses the most recent active list | Yes |

## Running it

```powershell
flutter pub get
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8000
```

`API_BASE_URL` defaults to the Android emulator's address for a local backend. Release
builds need an `https://` URL. A web build also needs its origin in the backend's
`CORS_ORIGINS` setting.

## Tests

```powershell
flutter analyze
flutter test
```

To also run the client against a real backend, so the test fake cannot drift from it:

```powershell
$env:BACKEND_URL = "http://127.0.0.1:8000"
flutter test test/backend_contract_test.dart
```

## Layout

- `lib/services/`: the API client (token refresh, error mapping), repositories, live updates.
- `lib/state/`: `ChangeNotifier` controllers holding each screen's state.
- `lib/screens/`: the UI.
- `test/support/fake_server.dart`: an in-memory backend for tests.
