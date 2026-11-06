import 'package:flutter/foundation.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../services/repositories.dart';

enum AuthStatus { loading, signedOut, signedIn }

class AuthController extends ChangeNotifier {
  AuthController(this._repository, this._api) {
    _api.onSessionExpired = _onExpired;
  }

  final AuthRepository _repository;
  final ApiClient _api;

  AuthStatus status = AuthStatus.loading;
  User? user;
  bool sessionExpired = false;

  /// Restores a saved login. Offline it stays signed in, because the saved tokens may
  /// still be good; only the server can say otherwise.
  Future<void> restore() async {
    await _api.tokens.load();
    if (_api.tokens.current == null) {
      status = AuthStatus.signedOut;
    } else {
      status = AuthStatus.signedIn;
      await _loadUser();
    }
    notifyListeners();
  }

  Future<void> _loadUser() async {
    try {
      user = await _repository.me();
    } on NetworkException {
      // Keep what is known; a later screen retries.
    } on ApiException {
      // A rejected login arrives through onSessionExpired.
    }
  }

  Future<void> login(String username, String password) async {
    await _repository.login(username, password);
    sessionExpired = false;
    status = AuthStatus.signedIn;
    await _loadUser();
    notifyListeners();
  }

  Future<void> register(String username, String email, String password) async {
    await _repository.register(username, email, password);
    await login(username, password);
  }

  Future<void> updateProfile({String? fullName, String? company, String? address}) async {
    user = await _repository.updateProfile(
      fullName: fullName,
      company: company,
      address: address,
    );
    notifyListeners();
  }

  Future<void> logout() async {
    await _repository.logout();
    user = null;
    status = AuthStatus.signedOut;
    notifyListeners();
  }

  void _onExpired() {
    user = null;
    sessionExpired = true;
    status = AuthStatus.signedOut;
    notifyListeners();
  }
}
