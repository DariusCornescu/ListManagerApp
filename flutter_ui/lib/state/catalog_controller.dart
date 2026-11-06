import 'package:flutter/foundation.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../services/repositories.dart';

class CatalogController extends ChangeNotifier {
  CatalogController(this._repository);

  final CatalogRepository _repository;

  List<Product> products = const [];
  List<Distributor> distributors = const [];
  bool isLoading = false;
  String? error;
  String query = '';

  /// Searches names and aliases, ignoring case and Romanian diacritics, because that
  /// is how people type on a phone.
  List<Product> get visibleProducts {
    final wanted = normalize(query.trim());
    if (wanted.isEmpty) return products;
    return products
        .where((p) => normalize('${p.name} ${p.aliases ?? ''}').contains(wanted))
        .toList();
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
    isLoading = products.isEmpty;
    notifyListeners();
    try {
      final loaded = await Future.wait([_repository.distributors(), _repository.products()]);
      distributors = loaded[0] as List<Distributor>;
      products = loaded[1] as List<Product>;
      error = null;
    } on NetworkException {
      error = 'Nu există conexiune.';
    } on ApiException catch (e) {
      error = e.message;
    }
    isLoading = false;
    notifyListeners();
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
