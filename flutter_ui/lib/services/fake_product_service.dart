import '../models/product.dart';

class FakeProductService {
  static final FakeProductService _instance = FakeProductService._internal();
  factory FakeProductService() => _instance;
  FakeProductService._internal();

  final List<Product> _products = [
    Product(
      id: '1',
      name: 'Lapte',
      distributorName: 'Distributor A',
      aliases: 'lapte, lapte de vaca, lapte proaspat',
    ),
    Product(
      id: '2',
      name: 'Pâine albă',
      distributorName: 'Distributor A',
      aliases: 'paine, paine alba, franzela',
    ),
    Product(
      id: '3',
      name: 'Banane',
      distributorName: 'Distributor B',
      aliases: 'banane, banana',
    ),
    Product(
      id: '4',
      name: 'Mere',
      distributorName: 'Distributor B',
      aliases: 'mere, mar, mere romanesti',
    ),
    Product(
      id: '5',
      name: 'Ouă',
      distributorName: 'Distributor A',
      aliases: 'oua, oua de gaina, oua proaspete',
    ),
    Product(
      id: '6',
      name: 'Roșii',
      distributorName: 'Distributor B',
      aliases: 'rosii, tomate, rosii proaspete',
    ),
    Product(
      id: '7',
      name: 'Cartofi',
      distributorName: 'Distributor A',
      aliases: 'cartofi, cartof, cartofi proaspeti',
    ),
    Product(
      id: '8',
      name: 'Ceapă',
      distributorName: 'Distributor B',
      aliases: 'ceapa, cepe',
    ),
  ];

  int _nextId = 9;

  // READ - Get all products
  Future<List<Product>> getAllProducts() async {
    await Future.delayed(const Duration(milliseconds: 300));
    return List.from(_products);
  }

  // READ - Get product by ID
  Future<Product?> getProductById(String id) async {
    await Future.delayed(const Duration(milliseconds: 200));
    try {
      return _products.firstWhere((p) => p.id == id);
    } catch (e) {
      return null;
    }
  }

  // CREATE - Add new product
  Future<Product> createProduct({ required String name, required String distributorName, String aliases = ''}) async {
    await Future.delayed(const Duration(milliseconds: 500));

    final newProduct = Product(
      id: _nextId.toString(),
      name: name,
      distributorName: distributorName,
      aliases: aliases,
    );

    _nextId++;
    _products.add(newProduct);

    return newProduct;
  }

  // UPDATE - Modify existing product
  Future<bool> updateProduct(Product updatedProduct) async {
    await Future.delayed(const Duration(milliseconds: 500));

    final index = _products.indexWhere((p) => p.id == updatedProduct.id);

    if (index == -1) {
      return false;
    }

    _products[index] = updatedProduct;
    return true;
  }

  // DELETE - Remove product
  Future<bool> deleteProduct(String id) async {
    await Future.delayed(const Duration(milliseconds: 400));

    final initialLength = _products.length;
    _products.removeWhere((p) => p.id == id);

    return _products.length < initialLength;
  }

  // SEARCH - Filter products
  Future<List<Product>> searchProducts(String query) async {
    await Future.delayed(const Duration(milliseconds: 250));

    if (query.isEmpty) {
      return getAllProducts();
    }

    final lowerQuery = query.toLowerCase();
    return _products.where((product) {
      return product.name.toLowerCase().contains(lowerQuery) ||
          product.distributorName.toLowerCase().contains(lowerQuery) ||
          product.aliases.toLowerCase().contains(lowerQuery);
    }).toList();
  }

  // Get distributors list
  Future<List<String>> getDistributors() async {
    await Future.delayed(const Duration(milliseconds: 200));
    return _products.map((p) => p.distributorName).toSet().toList()..sort();
  }
}