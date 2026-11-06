import 'dart:convert';

import 'package:flutter_ui/services/api_client.dart';
import 'package:flutter_ui/services/token_store.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// An in-memory stand-in for the backend, enforcing the rules the client depends on:
/// bearer auth, single-use refresh tokens and the optimistic lock on items.
class FakeServer {
  FakeServer() {
    client = MockClient(_handle);
  }

  late final http.Client client;

  final requests = <String>[];
  String validAccess = 'access-1';
  String? validRefresh = 'refresh-1';
  int refreshCalls = 0;
  bool offline = false;
  bool refreshOffline = false;
  bool isAdmin = false;

  int _itemId = 100;
  int? sessionId;
  int sessionsCreated = 0;
  final items = <int, Map<String, dynamic>>{};
  final appliedKeys = <String, Map<String, dynamic>>{};

  /// Runs before each item PUT, as another phone racing this one.
  void Function()? rivalWrite;

  final products = <Map<String, dynamic>>[
    {'id': 1, 'name': 'Lapte Zuzu 1L', 'distributor_id': 1, 'aliases': 'lapte', 'price': '7.50'},
    {'id': 2, 'name': 'Pâine albă', 'distributor_id': 1, 'aliases': null, 'price': null},
    {'id': 3, 'name': 'Șuruburi 4x40', 'distributor_id': 2, 'aliases': 'surub', 'price': null},
  ];
  final distributors = <Map<String, dynamic>>[
    {'id': 1, 'distributor_name': 'METRO'},
    {'id': 2, 'distributor_name': 'DEDEMAN'},
  ];

  ApiClient api({TokenStore? tokens}) => ApiClient(
    baseUrl: 'http://server.test',
    tokens: tokens ?? MemoryTokenStore(Tokens(access: validAccess, refresh: validRefresh)),
    httpClient: client,
  );

  /// Moves an item on, as if another user had written it.
  void bump(int itemId, {int? quantity}) {
    final item = items[itemId]!;
    item['version'] = (item['version'] as int) + 1;
    if (quantity != null) item['quantity'] = quantity;
  }

  http.Response _json(Object? body, [int status = 200]) => http.Response.bytes(
    utf8.encode(jsonEncode(body)),
    status,
    headers: {'content-type': 'application/json'},
  );

  http.Response _error(int status, Object detail) => _json({'detail': detail}, status);

  Future<http.Response> _handle(http.Request request) async {
    final path = request.url.path;
    requests.add('${request.method} $path');
    if (offline) throw http.ClientException('offline');

    if (path == '/api/auth/login') {
      final body = jsonDecode(request.body) as Map<String, dynamic>;
      if (body['password'] != 'correct') return _error(401, 'Incorrect username or password');
      return _json({'access_token': validAccess, 'token_type': 'bearer', 'refresh_token': validRefresh});
    }
    if (path == '/api/auth/register') {
      return _json({'id': 1, 'username': 'ana', 'email': 'a@b.ro', 'role': 'USER', 'is_active': true});
    }
    if (path == '/api/auth/logout') return _json({'message': 'Logged out'});
    if (path == '/api/auth/refresh') {
      if (refreshOffline) throw http.ClientException('offline');
      refreshCalls++;
      final body = jsonDecode(request.body) as Map<String, dynamic>;
      if (body['refresh_token'] != validRefresh) return _error(401, 'Invalid refresh token');
      validAccess = 'access-${refreshCalls + 1}';
      validRefresh = 'refresh-${refreshCalls + 1}';
      return _json({'access_token': validAccess, 'token_type': 'bearer', 'refresh_token': validRefresh});
    }

    if (path == '/api/catalog/distributors') return _json(distributors);
    if (path == '/api/catalog/products') {
      if (request.method == 'GET') return _json(products);
      final body = jsonDecode(request.body) as Map<String, dynamic>;
      products.add({'id': products.length + 1, 'aliases': null, 'price': null, ...body});
      return _json(products.last);
    }
    final productPath = RegExp(r'^/api/catalog/products/(\d+)$').firstMatch(path);
    if (productPath != null) {
      final id = int.parse(productPath.group(1)!);
      if (request.method == 'DELETE') {
        products.removeWhere((p) => p['id'] == id);
        return _json({'message': 'deleted'});
      }
      final body = jsonDecode(request.body) as Map<String, dynamic>;
      final index = products.indexWhere((p) => p['id'] == id);
      products[index] = {...products[index], ...body};
      return _json(products[index]);
    }

    if (request.headers['Authorization'] == null) return _error(403, 'Not authenticated');
    if (request.headers['Authorization'] != 'Bearer $validAccess') {
      return _error(401, 'Invalid authentication credentials');
    }

    if (path == '/api/auth/me') {
      if (request.method == 'PUT') {
        final body = jsonDecode(request.body) as Map<String, dynamic>;
        return _json(_me()..addAll(body));
      }
      return _json(_me());
    }
    if (path == '/api/session/active') {
      if (sessionId == null) return _error(404, 'No active session');
      return _json(_session());
    }
    if (path == '/api/session/create') {
      sessionsCreated++;
      sessionId = 10 + sessionsCreated;
      items.clear();
      return _json(_session());
    }

    final itemsPath = RegExp(r'^/api/session/(\d+)/items$').firstMatch(path);
    if (itemsPath != null) {
      if (request.method == 'GET') return _json(items.values.toList());
      if (request.method == 'DELETE') {
        items.clear();
        return _json({'message': 'cleared', 'count': 0});
      }
      final body = jsonDecode(request.body) as Map<String, dynamic>;
      final key = body['idempotency_key'] as String?;
      if (key != null && appliedKeys.containsKey(key)) return _json(appliedKeys[key]);
      final productId = body['product_id'] as int;
      final existing = items.values.where((i) => i['product_id'] == productId);
      final Map<String, dynamic> item;
      if (existing.isNotEmpty) {
        item = existing.first;
        item['quantity'] = (item['quantity'] as int) + (body['quantity'] as int);
        item['version'] = (item['version'] as int) + 1;
      } else {
        item = {
          'id': _itemId++,
          'product_id': productId,
          'quantity': body['quantity'],
          'version': 0,
          'product_name': products.firstWhere((p) => p['id'] == productId)['name'],
          'distributor_name': 'METRO',
        };
        items[item['id'] as int] = item;
      }
      if (key != null) appliedKeys[key] = Map.of(item);
      return _json(item);
    }

    final itemPath = RegExp(r'^/api/session/items/(\d+)$').firstMatch(path);
    if (itemPath != null) {
      final id = int.parse(itemPath.group(1)!);
      if (request.method == 'DELETE') {
        items.remove(id);
        return _json({'message': 'deleted', 'id': id});
      }
      rivalWrite?.call();
      final item = items[id];
      if (item == null) return _error(404, 'Item not found');
      final body = jsonDecode(request.body) as Map<String, dynamic>;
      final key = body['idempotency_key'] as String?;
      if (key != null && appliedKeys.containsKey(key)) return _json(appliedKeys[key]);
      if (body['version'] != item['version']) {
        return _error(409, {'error': 'Conflict', 'message': 'Item was modified by another user.'});
      }
      item['quantity'] = body['quantity'];
      item['version'] = (item['version'] as int) + 1;
      if (key != null) appliedKeys[key] = Map.of(item);
      return _json(item);
    }

    final completePath = RegExp(r'^/api/session/(\d+)/complete$').firstMatch(path);
    if (completePath != null) {
      final total = items.values.fold<int>(0, (sum, i) => sum + (i['quantity'] as int));
      sessionId = null;
      return _json({'distributor_count': 1, 'total_items': total, 'items_by_distributor': {}});
    }

    return _error(404, 'Unknown route: ${request.method} $path');
  }

  Map<String, dynamic> _me() => {
    'id': 1,
    'username': 'ana',
    'email': 'a@b.ro',
    'role': isAdmin ? 'ADMIN' : 'USER',
    'is_active': true,
    'full_name': null,
    'company': null,
    'address': null,
  };

  Map<String, dynamic> _session() => {
    'id': sessionId,
    'name': 'Lista curentă',
    'is_active': true,
    'team_id': null,
  };
}
