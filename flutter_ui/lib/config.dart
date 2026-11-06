/// The backend address, chosen at build time:
/// `flutter run --dart-define=API_BASE_URL=https://listmanager-api.onrender.com`.
///
/// The default reaches a local backend from the Android emulator. A web build needs
/// its origin listed in the backend's CORS_ORIGINS setting.
const String apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://10.0.2.2:8000',
);
