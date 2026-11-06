import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/services/api_client.dart';
import 'package:flutter_ui/services/token_store.dart';

import 'support/fake_server.dart';

void main() {
  late FakeServer server;

  setUp(() => server = FakeServer());

  test('sends the bearer token', () async {
    final me = await server.api().get('/api/auth/me') as Map<String, dynamic>;
    expect(me['username'], 'ana');
  });

  test('an expired token refreshes once, retries, and keeps the rotated pair', () async {
    final tokens = MemoryTokenStore(const Tokens(access: 'expired', refresh: 'refresh-1'));
    final me = await server.api(tokens: tokens).get('/api/auth/me') as Map<String, dynamic>;

    expect(me['username'], 'ana');
    expect(server.refreshCalls, 1);
    expect(tokens.current!.access, 'access-2');
    expect(tokens.current!.refresh, 'refresh-2');
  });

  test('requests failing together share one refresh', () async {
    final tokens = MemoryTokenStore(const Tokens(access: 'expired', refresh: 'refresh-1'));
    final api = server.api(tokens: tokens);

    await Future.wait([api.get('/api/auth/me'), api.get('/api/auth/me'), api.get('/api/auth/me')]);

    expect(server.refreshCalls, 1);
  });

  test('a rejected refresh ends the login and tells the app', () async {
    final tokens = MemoryTokenStore(const Tokens(access: 'expired', refresh: 'stolen'));
    var expired = 0;
    final api = server.api(tokens: tokens)..onSessionExpired = () => expired++;

    await expectLater(
      api.get('/api/auth/me'),
      throwsA(isA<ApiException>().having((e) => e.statusCode, 'status', 401)),
    );
    expect(tokens.current, isNull);
    expect(expired, 1);
  });

  test('a login without a refresh token ends as before', () async {
    final tokens = MemoryTokenStore(const Tokens(access: 'expired'));
    var expired = 0;
    final api = server.api(tokens: tokens)..onSessionExpired = () => expired++;

    await expectLater(api.get('/api/auth/me'), throwsA(isA<ApiException>()));
    expect(expired, 1);
    expect(server.refreshCalls, 0);
  });

  test('an unreachable server during refresh keeps the login', () async {
    final tokens = MemoryTokenStore(const Tokens(access: 'expired', refresh: 'refresh-1'));
    server.refreshOffline = true;
    var expired = 0;
    final api = server.api(tokens: tokens)..onSessionExpired = () => expired++;

    await expectLater(api.get('/api/auth/me'), throwsA(isA<NetworkException>()));
    expect(tokens.current, isNotNull);
    expect(expired, 0);
  });

  test('signing out during a refresh does not restore the old tokens', () async {
    final tokens = MemoryTokenStore(const Tokens(access: 'expired', refresh: 'refresh-1'));
    final api = server.api(tokens: tokens);

    final pending = api.get('/api/auth/me');
    await tokens.clear();

    await expectLater(pending, throwsA(isA<ApiException>()));
    expect(tokens.current, isNull);
  });

  test('no connection is a network error, not a server error', () async {
    server.offline = true;
    await expectLater(server.api().get('/api/auth/me'), throwsA(isA<NetworkException>()));
  });

  test('error details are read from both of the server shapes', () async {
    await expectLater(
      server.api().get('/api/session/active'),
      throwsA(
        isA<ApiException>()
            .having((e) => e.isNotFound, 'isNotFound', true)
            .having((e) => e.message, 'message', 'No active session'),
      ),
    );

    server.sessionId = 11;
    server.items[100] = {'id': 100, 'product_id': 1, 'quantity': 1, 'version': 5};
    await expectLater(
      server.api().put(
        '/api/session/items/100',
        body: {'quantity': 2, 'version': 1, 'idempotency_key': 'k'},
      ),
      throwsA(
        isA<ApiException>()
            .having((e) => e.isConflict, 'isConflict', true)
            .having((e) => e.message, 'message', 'Item was modified by another user.'),
      ),
    );
  });

  test('Romanian text survives the round trip', () async {
    final products = await server.api().get('/api/catalog/products') as List<dynamic>;
    expect((products[1] as Map<String, dynamic>)['name'], 'Pâine albă');
    expect((products[2] as Map<String, dynamic>)['name'], 'Șuruburi 4x40');
  });
}
