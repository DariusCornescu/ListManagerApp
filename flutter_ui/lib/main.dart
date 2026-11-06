import 'package:flutter/material.dart';

import 'config.dart';
import 'screens/home_shell.dart';
import 'screens/login_screen.dart';
import 'services/api_client.dart';
import 'services/live_updates.dart';
import 'services/repositories.dart';
import 'services/token_store.dart';
import 'state/auth_controller.dart';
import 'state/catalog_controller.dart';
import 'state/session_controller.dart';

void main() {
  runApp(ListManagerApp.production());
}

/// Everything the screens need, created once. Tests build it around a fake server.
class AppServices {
  AppServices({required ApiClient api})
    : auth = AuthController(AuthRepository(api), api),
      live = LiveUpdates(baseUrl: api.baseUrl, accessToken: () => api.tokens.current?.access),
      _api = api;

  final ApiClient _api;
  final AuthController auth;
  final LiveUpdates live;

  late final SessionController session = SessionController(
    SessionRepository(_api),
    liveEvents: live.events,
  );
  late final CatalogController catalog = CatalogController(
    CatalogRepository(_api),
    liveEvents: live.events,
  );
}

class ListManagerApp extends StatefulWidget {
  const ListManagerApp({super.key, required this.services});

  factory ListManagerApp.production() => ListManagerApp(
    services: AppServices(
      api: ApiClient(baseUrl: apiBaseUrl, tokens: SecureTokenStore()),
    ),
  );

  final AppServices services;

  @override
  State<ListManagerApp> createState() => _ListManagerAppState();
}

class _ListManagerAppState extends State<ListManagerApp> {
  @override
  void initState() {
    super.initState();
    widget.services.auth.restore();
  }

  @override
  Widget build(BuildContext context) {
    final services = widget.services;
    return MaterialApp(
      title: 'List Manager',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF65509F)),
        useMaterial3: true,
      ),
      debugShowCheckedModeBanner: false,
      home: ListenableBuilder(
        listenable: services.auth,
        builder: (context, _) => switch (services.auth.status) {
          AuthStatus.loading => const Scaffold(
            body: Center(child: CircularProgressIndicator()),
          ),
          AuthStatus.signedOut => LoginScreen(auth: services.auth),
          AuthStatus.signedIn => HomeShell(services: services),
        },
      ),
    );
  }
}
