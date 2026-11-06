import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/services/live_updates.dart';
import 'package:flutter_ui/services/repositories.dart';
import 'package:flutter_ui/state/catalog_controller.dart';

import 'support/fake_server.dart';

void main() {
  late FakeServer server;
  late StreamController<String> live;
  late CatalogController catalog;

  setUp(() {
    server = FakeServer();
    live = StreamController<String>();
    catalog = CatalogController(CatalogRepository(server.api()), liveEvents: live.stream);
  });

  tearDown(() async {
    catalog.dispose();
    await live.close();
  });

  void importProduct() {
    server.products.add({
      'id': 900, 'name': 'Ciment Portland', 'distributor_id': 1, 'aliases': 'ciment',
      'created_at': '2026-11-06T10:00:00',
    });
  }

  bool hasImported() => catalog.products.any((p) => p.id == 900);

  test('an import reaches the catalog without opening the screen', () async {
    await catalog.refresh();
    importProduct();

    live.add('catalog_changed');
    await pumpEventQueue();

    expect(hasImported(), isTrue);
  });

  test('a reconnect catches up on an import missed while offline', () async {
    await catalog.refresh();
    importProduct();

    live.add(LiveUpdates.reconnected);
    await pumpEventQueue();

    expect(hasImported(), isTrue);
  });

  test('other live events do not reload the catalog', () async {
    await catalog.refresh();
    importProduct();

    live.add('session_item_added');
    await pumpEventQueue();

    expect(hasImported(), isFalse);
  });

  test('a failed refresh keeps the catalog already loaded', () async {
    await catalog.refresh();
    final loaded = catalog.products.length;
    server.offline = true;

    await catalog.refresh();

    expect(catalog.products, hasLength(loaded));
    expect(catalog.error, isNotNull);
  });

  test('search ignores case and diacritics, in names and aliases', () async {
    importProduct();
    await catalog.refresh();

    for (final term in ['CIMENT', 'portland', ' ciment ']) {
      expect(catalog.matching(term).map((p) => p.id), [900], reason: term);
    }
    expect(catalog.matching('paine').map((p) => p.name), contains('Pâine albă'));
  });

  test('search sees products loaded after an earlier search', () async {
    await catalog.refresh();
    expect(catalog.matching('ciment'), isEmpty);

    importProduct();
    await catalog.refresh();

    expect(catalog.matching('ciment').map((p) => p.id), [900]);
  });

  test('an empty search shows everything', () async {
    await catalog.refresh();
    expect(catalog.matching('  '), hasLength(catalog.products.length));
  });
}
