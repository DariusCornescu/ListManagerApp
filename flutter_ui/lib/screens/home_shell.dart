import 'package:flutter/material.dart';

import '../main.dart';
import 'account_screen.dart';
import 'catalog_screen.dart';
import 'session_screen.dart';

class HomeShell extends StatefulWidget {
  const HomeShell({super.key, required this.services});

  final AppServices services;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _tab = 0;

  @override
  void initState() {
    super.initState();
    widget.services.live.start();
    widget.services.session.refresh();
    widget.services.catalog.refresh();
  }

  @override
  void dispose() {
    widget.services.live.stop();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final services = widget.services;
    return Scaffold(
      body: IndexedStack(
        index: _tab,
        children: [
          SessionScreen(session: services.session, catalog: services.catalog),
          CatalogScreen(catalog: services.catalog, auth: services.auth),
          AccountScreen(auth: services.auth),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (index) => setState(() => _tab = index),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.shopping_cart), label: 'Listă'),
          NavigationDestination(icon: Icon(Icons.inventory_2), label: 'Catalog'),
          NavigationDestination(icon: Icon(Icons.person), label: 'Cont'),
        ],
      ),
    );
  }
}
