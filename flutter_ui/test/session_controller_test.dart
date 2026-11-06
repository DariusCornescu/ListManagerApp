import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/models/models.dart';
import 'package:flutter_ui/services/repositories.dart';
import 'package:flutter_ui/state/session_controller.dart';

import 'support/fake_server.dart';

const _milk = Product(id: 1, name: 'Lapte Zuzu 1L', distributorId: 1);
const _bread = Product(id: 2, name: 'Pâine albă', distributorId: 1);

void main() {
  late FakeServer server;
  late SessionController controller;

  setUp(() {
    server = FakeServer();
    controller = SessionController(SessionRepository(server.api()));
  });

  tearDown(() => controller.dispose());

  Future<SessionItem> oneItem({int quantity = 1}) async {
    await controller.refresh();
    await controller.add(_milk, quantity: quantity);
    return controller.items.single;
  }

  test('opens the active list, or starts one when there is none', () async {
    await controller.refresh();
    expect(controller.session!.id, 11);
    expect(server.sessionsCreated, 1);

    await controller.refresh();
    expect(server.sessionsCreated, 1);
  });

  test('adding the same product twice increments one row', () async {
    await controller.refresh();
    await controller.add(_milk);
    await controller.add(_milk);
    expect(controller.items.single.quantity, 2);
  });

  test('each add has its own key, so two real adds are not one retry', () async {
    await controller.refresh();
    await controller.add(_milk);
    await controller.add(_milk);
    expect(server.appliedKeys.length, 2);
  });

  test('changing a quantity saves it', () async {
    final item = await oneItem();
    await controller.setQuantity(item, 5);
    expect(server.items[item.id]!['quantity'], 5);
    expect(controller.items.single.quantity, 5);
    expect(controller.conflict, isNull);
  });

  test('going to zero removes the item', () async {
    final item = await oneItem();
    await controller.setQuantity(item, 0);
    expect(server.items, isEmpty);
    expect(controller.items, isEmpty);
  });

  group('when another writer gets in first', () {
    test('asks the user, shows their value, and changes nothing', () async {
      final item = await oneItem();
      server.bump(item.id, quantity: 9);

      await controller.setQuantity(item, 5);

      expect(controller.conflict!.wanted, 5);
      expect(controller.conflict!.server.quantity, 9);
      expect(server.items[item.id]!['quantity'], 9);
      expect(controller.items.single.quantity, 9);
    });

    test('use theirs keeps the server value', () async {
      final item = await oneItem();
      server.bump(item.id, quantity: 9);
      await controller.setQuantity(item, 5);

      controller.useServer();

      expect(controller.conflict, isNull);
      expect(server.items[item.id]!['quantity'], 9);
    });

    test('keep mine applies on top of the latest version', () async {
      final item = await oneItem();
      server.bump(item.id, quantity: 9);
      await controller.setQuantity(item, 5);

      await controller.keepMine();

      expect(controller.conflict, isNull);
      expect(server.items[item.id]!['quantity'], 5);
      expect(controller.error, isNull);
    });

    test('keep mine converges through a storm of conflicts, applying once', () async {
      final item = await oneItem();
      server.bump(item.id, quantity: 9);
      await controller.setQuantity(item, 5);
      var rivalWins = 3;
      server.rivalWrite = () {
        if (rivalWins-- > 0) server.bump(item.id);
      };
      final keysBefore = server.appliedKeys.length;

      await controller.keepMine();

      expect(server.items[item.id]!['quantity'], 5);
      expect(server.appliedKeys.length, keysBefore + 1);
      expect(controller.error, isNull);
    });

    test('keep mine gives up after a bounded storm and says so', () async {
      final item = await oneItem();
      server.bump(item.id, quantity: 9);
      await controller.setQuantity(item, 5);
      server.rivalWrite = () => server.bump(item.id);

      await controller.keepMine();

      expect(server.items[item.id]!['quantity'], 9);
      expect(controller.error, contains('Încearcă din nou'));
    });

    test('an item someone else removed is reported rather than recreated', () async {
      final item = await oneItem();
      server.items.remove(item.id);

      await controller.setQuantity(item, 5);

      expect(controller.conflict, isNull);
      expect(controller.error, isNotNull);
    });
  });

  test('offline, a failed write says it was not saved and keeps the list', () async {
    final item = await oneItem();
    server.offline = true;

    await controller.setQuantity(item, 5);

    expect(controller.error, contains('nu a fost salvată'));
    expect(controller.items.single.quantity, 1);
  });

  test('completing reports the totals and opens a fresh list', () async {
    await controller.refresh();
    await controller.add(_milk, quantity: 2);
    await controller.add(_bread);

    final result = await controller.complete();

    expect(result!.totalItems, 3);
    expect(controller.items, isEmpty);
    expect(controller.session!.id, 12);
  });

  test('clear empties the list', () async {
    await oneItem();
    await controller.clear();
    expect(controller.items, isEmpty);
    expect(server.items, isEmpty);
  });

  test('a live session event refreshes; a catalog event does not', () async {
    final live = StreamController<String>();
    final watching = SessionController(
      SessionRepository(server.api()),
      liveEvents: live.stream,
    );
    addTearDown(watching.dispose);
    addTearDown(live.close);
    await watching.refresh();
    server.items[200] = {
      'id': 200, 'product_id': 1, 'quantity': 3, 'version': 0,
      'product_name': 'Lapte Zuzu 1L', 'distributor_name': 'METRO',
    };

    live.add('product_updated');
    await pumpEventQueue();
    expect(watching.items, isEmpty);

    live.add('session_item_added');
    await pumpEventQueue();
    expect(watching.items.single.quantity, 3);
  });
}
