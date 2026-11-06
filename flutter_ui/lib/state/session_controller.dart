import 'dart:async';

import 'package:flutter/foundation.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../services/repositories.dart';

/// Someone else changed this item first. Quantities are absolute and cannot be merged
/// without risking a teammate's addition, so the user chooses, as on Android.
class QuantityConflict {
  const QuantityConflict({required this.server, required this.wanted});

  final SessionItem server;
  final int wanted;
}

class SessionController extends ChangeNotifier {
  SessionController(this._repository, {Stream<String>? liveEvents}) {
    _liveSubscription = liveEvents?.listen((event) {
      if (event.startsWith('session_')) refresh();
    });
  }

  static const _maxRebaseAttempts = 5;

  final SessionRepository _repository;
  StreamSubscription<String>? _liveSubscription;

  ShoppingSession? session;
  List<SessionItem> items = const [];
  bool isLoading = false;
  String? error;
  QuantityConflict? conflict;

  /// Finds the active list, or starts one. Used on opening and after completing.
  Future<void> refresh() async {
    isLoading = items.isEmpty;
    notifyListeners();
    await _guard(() async {
      session = await _repository.active() ?? await _repository.create('Lista curentă');
      items = await _repository.items(session!.id);
      error = null;
    });
    isLoading = false;
    notifyListeners();
  }

  Future<void> add(Product product, {int quantity = 1}) async {
    final current = session;
    if (current == null) return;
    await _guard(() async {
      await _repository.add(current.id, product.id, quantity, _repository.newOperationKey());
      items = await _repository.items(current.id);
      error = null;
    });
    notifyListeners();
  }

  Future<void> setQuantity(SessionItem item, int quantity) async {
    if (quantity <= 0) return remove(item);
    await _guard(() async {
      try {
        await _repository.setQuantity(item.id, quantity, item.version, _repository.newOperationKey());
        await _reload();
        error = null;
      } on ApiException catch (e) {
        if (!e.isConflict) rethrow;
        await _reload();
        final server = _itemById(item.id);
        if (server == null) {
          error = 'Altcineva a șters acest produs din listă.';
        } else {
          conflict = QuantityConflict(server: server, wanted: quantity);
        }
      }
    });
    notifyListeners();
  }

  /// Applies the user's quantity on top of the latest version, retrying while other
  /// writers keep getting in first. Gives up after a few rounds and says so.
  Future<void> keepMine() async {
    final pending = conflict;
    if (pending == null) return;
    conflict = null;
    final key = _repository.newOperationKey();
    await _guard(() async {
      var version = pending.server.version;
      for (var attempt = 0; attempt < _maxRebaseAttempts; attempt++) {
        try {
          await _repository.setQuantity(pending.server.id, pending.wanted, version, key);
          await _reload();
          error = null;
          return;
        } on ApiException catch (e) {
          if (!e.isConflict) rethrow;
          await _reload();
          final server = _itemById(pending.server.id);
          if (server == null) {
            error = 'Altcineva a șters acest produs din listă.';
            return;
          }
          version = server.version;
        }
      }
      error = 'Lista se schimbă chiar acum. Încearcă din nou peste câteva secunde.';
    });
    notifyListeners();
  }

  /// The list already shows the server's value, so this only closes the question.
  void useServer() {
    conflict = null;
    notifyListeners();
  }

  Future<void> remove(SessionItem item) async {
    await _guard(() async {
      await _repository.remove(item.id);
      await _reload();
      error = null;
    });
    notifyListeners();
  }

  Future<void> clear() async {
    final current = session;
    if (current == null) return;
    await _guard(() async {
      await _repository.clear(current.id);
      items = const [];
      error = null;
    });
    notifyListeners();
  }

  /// Completes the list and opens the next one. Null if it could not complete.
  Future<CompletedSession?> complete() async {
    final current = session;
    if (current == null) return null;
    CompletedSession? result;
    await _guard(() async {
      result = await _repository.complete(current.id);
      session = await _repository.active() ?? await _repository.create('Lista curentă');
      items = await _repository.items(session!.id);
      error = null;
    });
    notifyListeners();
    return result;
  }

  void clearError() {
    error = null;
    notifyListeners();
  }

  SessionItem? _itemById(int id) {
    for (final item in items) {
      if (item.id == id) return item;
    }
    return null;
  }

  Future<void> _reload() async {
    final current = session;
    if (current != null) items = await _repository.items(current.id);
  }

  Future<void> _guard(Future<void> Function() action) async {
    try {
      await action();
    } on NetworkException {
      error = 'Nu există conexiune. Modificarea nu a fost salvată.';
    } on ApiException catch (e) {
      error = e.message;
    }
  }

  @override
  void dispose() {
    _liveSubscription?.cancel();
    super.dispose();
  }
}
