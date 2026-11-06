import 'dart:math';

import '../models/models.dart';
import 'api_client.dart';
import 'token_store.dart';

class AuthRepository {
  AuthRepository(this._api);

  final ApiClient _api;

  Future<void> login(String username, String password) async {
    final json = await _api.post('/api/auth/login', body: {
      'username': username,
      'password': password,
    }) as Map<String, dynamic>;
    await _api.tokens.save(
      Tokens(
        access: json['access_token'] as String,
        refresh: json['refresh_token'] as String?,
      ),
    );
  }

  Future<void> register(String username, String email, String password) async {
    await _api.post('/api/auth/register', body: {
      'username': username,
      'email': email,
      'password': password,
    });
  }

  Future<User> me() async =>
      User.fromJson(await _api.get('/api/auth/me') as Map<String, dynamic>);

  /// A blank profile field clears the value; a null field is left alone.
  Future<User> updateProfile({String? fullName, String? company, String? address}) async {
    final json = await _api.put('/api/auth/me', body: {
      if (fullName != null) 'full_name': fullName,
      if (company != null) 'company': company,
      if (address != null) 'address': address,
    });
    return User.fromJson(json as Map<String, dynamic>);
  }

  /// Local logout always succeeds; revoking on the server is best effort.
  Future<void> logout() async {
    final refresh = _api.tokens.current?.refresh;
    await _api.tokens.clear();
    if (refresh == null) return;
    try {
      await _api.post('/api/auth/logout', body: {'refresh_token': refresh});
    } on NetworkException {
      // The token simply expires on the server.
    } on ApiException {
      // Same.
    }
  }
}

class CatalogRepository {
  CatalogRepository(this._api);

  final ApiClient _api;

  Future<List<Distributor>> distributors() async {
    final json = await _api.get('/api/catalog/distributors') as List<dynamic>;
    return json.map((e) => Distributor.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<List<Product>> products() async {
    final json = await _api.get('/api/catalog/products') as List<dynamic>;
    return json.map((e) => Product.fromJson(e as Map<String, dynamic>)).toList();
  }

  Map<String, Object?> _productBody(String name, int distributorId, String? aliases) => {
    'name': name,
    'distributor_id': distributorId,
    if (aliases != null && aliases.isNotEmpty) 'aliases': aliases,
  };

  Future<void> createProduct(String name, int distributorId, String? aliases) =>
      _api.post('/api/catalog/products', body: _productBody(name, distributorId, aliases));

  Future<void> updateProduct(int id, String name, int distributorId, String? aliases) =>
      _api.put('/api/catalog/products/$id', body: _productBody(name, distributorId, aliases));

  Future<void> deleteProduct(int id) => _api.delete('/api/catalog/products/$id');
}

class SessionRepository {
  SessionRepository(this._api, {Random? random}) : _random = random ?? Random.secure();

  final ApiClient _api;
  final Random _random;

  /// One key per user action, so a retried request cannot apply twice.
  String newOperationKey() =>
      List.generate(32, (_) => _random.nextInt(16).toRadixString(16)).join();

  Future<ShoppingSession?> active() async {
    try {
      return ShoppingSession.fromJson(
        await _api.get('/api/session/active') as Map<String, dynamic>,
      );
    } on ApiException catch (e) {
      if (e.isNotFound) return null;
      rethrow;
    }
  }

  Future<ShoppingSession> create(String name) async => ShoppingSession.fromJson(
    await _api.post('/api/session/create', body: {'name': name}) as Map<String, dynamic>,
  );

  Future<List<SessionItem>> items(int sessionId) async {
    final json = await _api.get('/api/session/$sessionId/items') as List<dynamic>;
    return json.map((e) => SessionItem.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<SessionItem> add(int sessionId, int productId, int quantity, String key) async =>
      SessionItem.fromJson(
        await _api.post('/api/session/$sessionId/items', body: {
          'product_id': productId,
          'quantity': quantity,
          'idempotency_key': key,
        }) as Map<String, dynamic>,
      );

  Future<SessionItem> setQuantity(int itemId, int quantity, int version, String key) async =>
      SessionItem.fromJson(
        await _api.put('/api/session/items/$itemId', body: {
          'quantity': quantity,
          'version': version,
          'idempotency_key': key,
        }) as Map<String, dynamic>,
      );

  Future<void> remove(int itemId) => _api.delete('/api/session/items/$itemId');

  Future<void> clear(int sessionId) => _api.delete('/api/session/$sessionId/items');

  Future<CompletedSession> complete(int sessionId) async => CompletedSession.fromJson(
    await _api.post('/api/session/$sessionId/complete') as Map<String, dynamic>,
  );
}
