import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/main.dart';
import 'package:flutter_ui/services/token_store.dart';
import 'package:flutter_ui/state/catalog_controller.dart';

import 'support/fake_server.dart';

/// The app with a fake server. The socket has no server to reach, and only reconnects.
Future<AppServices> _pumpApp(WidgetTester tester, FakeServer server, {Tokens? tokens}) async {
  final services = AppServices(api: server.api(tokens: MemoryTokenStore(tokens)));
  await tester.pumpWidget(ListManagerApp(services: services));
  await tester.pumpAndSettle();
  addTearDown(services.live.stop);
  return services;
}

const _signedIn = Tokens(access: 'access-1', refresh: 'refresh-1');

void main() {
  group('login', () {
    testWidgets('starts signed out without a saved login', (tester) async {
      await _pumpApp(tester, FakeServer());
      expect(find.text('Autentificare'), findsOneWidget);
    });

    testWidgets('empty fields are refused without calling the server', (tester) async {
      final server = FakeServer();
      await _pumpApp(tester, server);

      await tester.tap(find.text('Autentificare'));
      await tester.pumpAndSettle();

      expect(find.text('Completați utilizatorul'), findsOneWidget);
      expect(server.requests, isEmpty);
    });

    testWidgets('a wrong password says so and stays here', (tester) async {
      await _pumpApp(tester, FakeServer());

      await tester.enterText(find.widgetWithText(TextFormField, 'Utilizator'), 'ana');
      await tester.enterText(find.widgetWithText(TextFormField, 'Parolă'), 'wrong');
      await tester.tap(find.text('Autentificare'));
      await tester.pumpAndSettle();

      expect(find.text('Utilizator sau parolă incorectă'), findsOneWidget);
    });

    testWidgets('signing in opens the list', (tester) async {
      final server = FakeServer();
      await _pumpApp(tester, server);

      await tester.enterText(find.widgetWithText(TextFormField, 'Utilizator'), 'ana');
      await tester.enterText(find.widgetWithText(TextFormField, 'Parolă'), 'correct');
      await tester.tap(find.text('Autentificare'));
      await tester.pumpAndSettle();

      expect(find.text('Lista este goală. Apasă Adaugă pentru primul produs.'), findsOneWidget);
    });

    testWidgets('creating an account shows the email field, then signs in', (tester) async {
      await _pumpApp(tester, FakeServer());

      await tester.tap(find.text('Nu ai cont? Creează unul'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextFormField, 'Utilizator'), 'ana');
      await tester.enterText(find.widgetWithText(TextFormField, 'Email'), 'a@b.ro');
      await tester.enterText(find.widgetWithText(TextFormField, 'Parolă'), 'correct');
      await tester.tap(find.text('Creează cont'));
      await tester.pumpAndSettle();

      expect(find.text('Adaugă'), findsOneWidget);
    });

    testWidgets('an expired login returns here and explains why', (tester) async {
      final server = FakeServer()..validRefresh = 'something-else';
      await _pumpApp(tester, server, tokens: const Tokens(access: 'expired', refresh: 'old'));

      expect(find.text('Sesiune expirată. Autentificați-vă din nou.'), findsOneWidget);
    });
  });

  group('list', () {
    testWidgets('a saved login goes straight to the list', (tester) async {
      await _pumpApp(tester, FakeServer(), tokens: _signedIn);
      expect(find.text('Lista curentă'), findsOneWidget);
    });

    testWidgets('picks a product through diacritic-free search', (tester) async {
      final server = FakeServer();
      await _pumpApp(tester, server, tokens: _signedIn);

      await tester.tap(find.text('Adaugă'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'paine');
      await tester.pumpAndSettle();
      expect(find.text('Lapte Zuzu 1L'), findsNothing);
      await tester.tap(find.text('Pâine albă'));
      await tester.pumpAndSettle();

      expect(server.items.values.single['product_id'], 2);
      expect(find.text('Pâine albă'), findsOneWidget);
      expect(find.text('Finalizează lista'), findsOneWidget);
    });

    testWidgets('plus and minus change the quantity', (tester) async {
      final server = FakeServer();
      final services = await _pumpApp(tester, server, tokens: _signedIn);
      await services.session.add(services.catalog.products.first);
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Crește cantitatea pentru Lapte Zuzu 1L'));
      await tester.pumpAndSettle();
      expect(server.items.values.single['quantity'], 2);

      await tester.tap(find.byTooltip('Scade cantitatea pentru Lapte Zuzu 1L'));
      await tester.pumpAndSettle();
      expect(server.items.values.single['quantity'], 1);
    });

    testWidgets('a conflict asks, and choosing mine applies it', (tester) async {
      final server = FakeServer();
      final services = await _pumpApp(tester, server, tokens: _signedIn);
      await services.session.add(services.catalog.products.first);
      await tester.pumpAndSettle();
      server.bump(server.items.keys.single, quantity: 9);

      await tester.tap(find.byTooltip('Crește cantitatea pentru Lapte Zuzu 1L'));
      await tester.pumpAndSettle();

      expect(find.text('Ai de decis'), findsOneWidget);
      expect(find.textContaining('Varianta ta: 2'), findsOneWidget);
      expect(find.textContaining('Varianta lor: 9'), findsOneWidget);

      await tester.tap(find.text('Varianta mea'));
      await tester.pumpAndSettle();

      expect(find.text('Ai de decis'), findsNothing);
      expect(server.items.values.single['quantity'], 2);
    });

    testWidgets('a conflict, choosing theirs keeps the server value', (tester) async {
      final server = FakeServer();
      final services = await _pumpApp(tester, server, tokens: _signedIn);
      await services.session.add(services.catalog.products.first);
      await tester.pumpAndSettle();
      server.bump(server.items.keys.single, quantity: 9);

      await tester.tap(find.byTooltip('Crește cantitatea pentru Lapte Zuzu 1L'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Varianta lor'));
      await tester.pumpAndSettle();

      expect(server.items.values.single['quantity'], 9);
      expect(find.text('9'), findsOneWidget);
    });

    testWidgets('finishing reports totals and opens an empty list', (tester) async {
      final server = FakeServer();
      final services = await _pumpApp(tester, server, tokens: _signedIn);
      await services.session.add(services.catalog.products.first, quantity: 3);
      await tester.pumpAndSettle();

      await tester.tap(find.text('Finalizează lista'));
      await tester.pumpAndSettle();

      expect(find.textContaining('3 bucăți'), findsOneWidget);
      expect(find.text('Lista este goală. Apasă Adaugă pentru primul produs.'), findsOneWidget);
    });

    testWidgets('offline, the failure is visible and offers a retry', (tester) async {
      final server = FakeServer();
      final services = await _pumpApp(tester, server, tokens: _signedIn);
      await services.session.add(services.catalog.products.first);
      await tester.pumpAndSettle();
      server.offline = true;

      await tester.tap(find.byTooltip('Crește cantitatea pentru Lapte Zuzu 1L'));
      await tester.pumpAndSettle();

      expect(find.textContaining('nu a fost salvată'), findsOneWidget);
      expect(find.text('Reîncearcă'), findsOneWidget);
    });
  });

  group('catalog', () {
    Future<void> openCatalog(WidgetTester tester) async {
      await tester.tap(find.text('Catalog'));
      await tester.pumpAndSettle();
    }

    testWidgets('anyone can browse and search; editing is hidden', (tester) async {
      await _pumpApp(tester, FakeServer(), tokens: _signedIn);
      await openCatalog(tester);

      await tester.enterText(find.widgetWithText(TextField, 'Caută produse'), 'surub');
      await tester.pumpAndSettle();

      expect(find.text('Șuruburi 4x40'), findsOneWidget);
      expect(find.text('Pâine albă'), findsNothing);
      expect(find.byTooltip('Adaugă produs'), findsNothing);
      expect(find.byTooltip('Șterge Șuruburi 4x40'), findsNothing);
    });

    testWidgets('an admin can add a product', (tester) async {
      final server = FakeServer()..isAdmin = true;
      await _pumpApp(tester, server, tokens: _signedIn);
      await openCatalog(tester);

      await tester.tap(find.byTooltip('Adaugă produs'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextField, 'Nume'), 'Ulei Floriol');
      await tester.tap(find.byType(DropdownButtonFormField<int>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('DEDEMAN').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Salvează'));
      await tester.pumpAndSettle();

      expect(server.products.last['name'], 'Ulei Floriol');
      expect(server.products.last['distributor_id'], 2);
    });

    testWidgets('a product needs a name and a distributor', (tester) async {
      final server = FakeServer()..isAdmin = true;
      await _pumpApp(tester, server, tokens: _signedIn);
      await openCatalog(tester);
      final before = server.products.length;

      await tester.tap(find.byTooltip('Adaugă produs'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Salvează'));
      await tester.pumpAndSettle();

      expect(find.text('Completați numele și distribuitorul'), findsOneWidget);
      expect(server.products.length, before);
    });

    testWidgets('an admin can delete a product, after confirming', (tester) async {
      final server = FakeServer()..isAdmin = true;
      await _pumpApp(tester, server, tokens: _signedIn);
      await openCatalog(tester);

      await tester.tap(find.byTooltip('Șterge Pâine albă'));
      await tester.pumpAndSettle();
      expect(server.products.length, 3);
      await tester.tap(find.text('Șterge'));
      await tester.pumpAndSettle();

      expect(server.products.any((p) => p['name'] == 'Pâine albă'), isFalse);
    });
  });

  group('account', () {
    testWidgets('shows the account, and logging out returns to sign-in', (tester) async {
      await _pumpApp(tester, FakeServer(), tokens: _signedIn);
      await tester.tap(find.text('Cont'));
      await tester.pumpAndSettle();
      expect(find.text('ana'), findsOneWidget);

      await tester.tap(find.text('Deconectare'));
      await tester.pumpAndSettle();

      expect(find.text('Autentificare'), findsOneWidget);
    });

    testWidgets('editing the profile saves and shows it', (tester) async {
      await _pumpApp(tester, FakeServer(), tokens: _signedIn);
      await tester.tap(find.text('Cont'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Editează profilul'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextField, 'Companie'), 'Magazin SRL');
      await tester.tap(find.text('Salvează'));
      await tester.pumpAndSettle();

      expect(find.text('Magazin SRL'), findsOneWidget);
    });
  });

  test('catalog search ignores case and Romanian diacritics', () {
    expect(CatalogController.normalize('ȘURUBURI Pâine ţ'), 'suruburi paine t');
  });
}
