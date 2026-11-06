import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class Tokens {
  const Tokens({required this.access, this.refresh});

  final String access;

  /// Absent when the server predates refresh tokens.
  final String? refresh;
}

/// Reads are synchronous once [load] has run, because every request needs them.
abstract class TokenStore {
  Tokens? get current;
  Future<void> load();
  Future<void> save(Tokens tokens);
  Future<void> clear();
}

class SecureTokenStore implements TokenStore {
  SecureTokenStore([FlutterSecureStorage? storage])
    : _storage = storage ?? const FlutterSecureStorage();

  static const _accessKey = 'access_token';
  static const _refreshKey = 'refresh_token';

  final FlutterSecureStorage _storage;
  Tokens? _current;

  @override
  Tokens? get current => _current;

  @override
  Future<void> load() async {
    final access = await _storage.read(key: _accessKey);
    final refresh = await _storage.read(key: _refreshKey);
    _current = access == null ? null : Tokens(access: access, refresh: refresh);
  }

  @override
  Future<void> save(Tokens tokens) async {
    _current = tokens;
    await _storage.write(key: _accessKey, value: tokens.access);
    await _storage.write(key: _refreshKey, value: tokens.refresh);
  }

  @override
  Future<void> clear() async {
    _current = null;
    await _storage.delete(key: _accessKey);
    await _storage.delete(key: _refreshKey);
  }
}

class MemoryTokenStore implements TokenStore {
  MemoryTokenStore([this._current]);

  Tokens? _current;

  @override
  Tokens? get current => _current;

  @override
  Future<void> load() async {}

  @override
  Future<void> save(Tokens tokens) async => _current = tokens;

  @override
  Future<void> clear() async => _current = null;
}
