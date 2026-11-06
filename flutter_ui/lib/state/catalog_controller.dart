import 'dart:async';

import 'package:flutter/foundation.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../services/live_updates.dart';
import '../services/repositories.dart';

class CatalogController extends ChangeNotifier {
  CatalogController(this._repository, {Stream<String>? liveEvents}) {
    _liveSubscription = liveEvents?.listen((event) {
      if (event == 'catalog_changed' || event == LiveUpdates.reconnected) refresh();
    });
  }

  final CatalogRepository _repository;
  StreamSubscription<String>? _liveSubscription;
  int _latestRefresh = 0;
  List<Product>? _indexedProducts;
  Map<int, String> _searchText = const {};

  List<Product> products = const [];
  List<Distributor> distributors = const [];
  bool isLoading = false;
  String? error;
  String query = '';

  /// Searches names and aliases, ignoring case and Romanian diacritics, because that
  /// is how people type on a phone.
  List<Product> get visibleProducts => matching(query);

  /// Normalizing is too slow to repeat for every product on every keystroke with a
  /// catalog this size, so it's done once per loaded list.
  List<Product> matching(String value) {
    final wanted = normalize(value.trim());
    if (wanted.isEmpty) return products;
    if (!identical(_indexedProducts, products)) {
      _indexedProducts = products;
      _searchText = {
        for (final p in products) p.id: normalize('${p.name} ${p.aliases ?? ''}'),
      };
    }
    return products.where((p) => _searchText[p.id]!.contains(wanted)).toList();
  }

  static String normalize(String text) {
    const replacements = {
      'ă': 'a', 'â': 'a', 'î': 'i', 'ș': 's', 'ş': 's', 'ț': 't', 'ţ': 't',
    };
    return text.toLowerCase().split('').map((c) => replacements[c] ?? c).join();
  }

  String distributorName(int id) {
    for (final distributor in distributors) {
      if (distributor.id == id) return distributor.name;
    }
    return '';
  }

  void search(String value) {
    query = value;
    notifyListeners();
  }

  Future<void> refresh() async {
    // Live events can overlap, and only the newest answer may land.
    final thisRefresh = ++_latestRefresh;
    isLoading = products.isEmpty;
    notifyListeners();
    List<Object>? loaded;
    String? failure;
    try {
      loaded = await Future.wait<Object>([_repository.distributors(), _repository.products()]);
    } on NetworkException {
      failure = 'Nu există conexiune.';
    } on ApiException catch (e) {
      failure = e.message;
    }
    if (thisRefresh != _latestRefresh) return;
    if (loaded != null) {
      distributors = loaded[0] as List<Distributor>;
      products = loaded[1] as List<Product>;
    }
    error = failure;
    isLoading = false;
    notifyListeners();
  }

  @override
  void dispose() {
    _liveSubscription?.cancel();
    super.dispose();
  }

  /// Returns a message for the user, or null on success.
  Future<String?> save({int? id, required String name, required int distributorId, String? aliases}) =>
      _write(() => id == null
          ? _repository.createProduct(name, distributorId, aliases)
          : _repository.updateProduct(id, name, distributorId, aliases));

  Future<String?> delete(int id) => _write(() => _repository.deleteProduct(id));

  Future<String?> _write(Future<void> Function() action) async {
    try {
      await action();
      await refresh();
      return null;
    } on NetworkException {
      return 'Nu există conexiune.';
    } on ApiException catch (e) {
      return e.message;
    }
  }
}
