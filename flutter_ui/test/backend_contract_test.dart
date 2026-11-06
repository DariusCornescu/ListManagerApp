@Tags(['backend'])
library;

import 'dart:io';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/models/models.dart';
import 'package:flutter_ui/services/api_client.dart';
import 'package:flutter_ui/services/repositories.dart';
import 'package:flutter_ui/services/token_store.dart';
import 'package:flutter_ui/state/session_controller.dart';
import 'package:http/http.dart' as http;

/// Runs the client against a REAL backend, so the fake server cannot drift from it:
/// BACKEND_URL=http://127.0.0.1:8000 flutter test test/backend_contract_test.dart
void main() {
  final baseUrl = Platform.environment['BACKEND_URL'];
  final skip = baseUrl == null ? 'BACKEND_URL is not set' : null;

  ApiClient client(TokenStore tokens) =>
      ApiClient(baseUrl: baseUrl!, tokens: tokens, httpClient: http.Client());

  Future<(ApiClient, AuthRepository)> signUp() async {
    final name = 'flutter${Random().nextInt(1 << 31)}';
    final tokens = MemoryTokenStore();
    final api = client(tokens);
    final auth = AuthRepository(api);
    await auth.register(name, '$name@example.com', 'contract-pass-1');
    await auth.login(name, 'contract-pass-1');
    return (api, auth);
  }

  test('register, sign in, profile', () async {
    final (_, auth) = await signUp();
    final me = await auth.me();
    expect(me.role, 'USER');

    final updated = await auth.updateProfile(company: 'Magazin SRL');
    expect(updated.company, 'Magazin SRL');
    expect((await auth.me()).company, 'Magazin SRL');
  }, skip: skip);

  test('refresh rotates; revocation ends the login', () async {
    final (api, auth) = await signUp();
    final old = api.tokens.current!;

    // An unusable access token forces the 401 → refresh → retry path.
    await api.tokens.save(Tokens(access: 'not-a-valid-token', refresh: old.refresh));
    expect((await auth.me()).role, 'USER');
    expect(api.tokens.current!.refresh, isNot(old.refresh));

    await api.post('/api/auth/logout-all');
    var expired = false;
    api.onSessionExpired = () => expired = true;
    await expectLater(auth.me(), throwsA(isA<ApiException>()));
    expect(expired, isTrue);
  }, skip: skip);

  test('catalog reads, and writes are refused for a normal user', () async {
    final (api, _) = await signUp();
    final catalog = CatalogRepository(api);

    final products = await catalog.products();
    expect(products, isNotEmpty);
    expect(await catalog.distributors(), isNotEmpty);
    await expectLater(
      catalog.createProduct('Nope', products.first.distributorId, null),
      throwsA(isA<ApiException>().having((e) => e.statusCode, 'status', 403)),
    );
  }, skip: skip);

  test('a whole list: add, change, lose a race, keep mine, complete', () async {
    final (api, _) = await signUp();
    final products = await CatalogRepository(api).products();
    final repository = SessionRepository(api);
    final controller = SessionController(repository);
    addTearDown(controller.dispose);

    await controller.refresh();
    await controller.add(products.first, quantity: 2);
    expect(controller.items.single.quantity, 2);
    expect(controller.items.single.productName, products.first.name);

    final stale = controller.items.single;
    await controller.setQuantity(stale, 3);
    expect(controller.items.single.quantity, 3);

    // The stale copy is what a phone would hold after another writer's change.
    await controller.setQuantity(stale, 7);
    expect(controller.conflict, isNotNull);
    expect(controller.conflict!.server.quantity, 3);

    await controller.keepMine();
    expect(controller.conflict, isNull);
    expect(controller.items.single.quantity, 7);

    final result = await controller.complete();
    expect(result!.totalItems, 7);
    expect(controller.items, isEmpty);
    expect(controller.session!.isActive, isTrue);
  }, skip: skip);

  test('a retried add applies once', () async {
    final (api, _) = await signUp();
    final products = await CatalogRepository(api).products();
    final repository = SessionRepository(api);
    final session = await repository.active() ?? await repository.create('Contract');

    final Product product = products.first;
    await repository.add(session.id, product.id, 2, 'same-key');
    final again = await repository.add(session.id, product.id, 2, 'same-key');

    expect(again.quantity, 2);
    expect((await repository.items(session.id)).single.quantity, 2);
  }, skip: skip);
}
