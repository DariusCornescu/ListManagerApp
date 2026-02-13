import '../models/product.dart';

class FakeProductService {
  static final FakeProductService _instance = FakeProductService._internal();
  factory FakeProductService() => _instance;
  FakeProductService._internal();

  final List<Product> _products = [
    Product(
      id: '1',
      name: 'Ciment',
      distributorName: 'BauMax',
      aliases: 'ciment, ciment alb, ciment gri, ciment portland',
    ),
    Product(
      id: '2',
      name: 'Cărămidă',
      distributorName: 'BauMax',
      aliases: 'caramida, caramizi, caramida rosie, bloc ceramic',
    ),
    Product(
      id: '3',
      name: 'Gips-carton',
      distributorName: 'Dedeman',
      aliases: 'gips carton, rigips, placi gips, gips',
    ),
    Product(
      id: '4',
      name: 'Vopsea lavabilă',
      distributorName: 'Hornbach',
      aliases: 'vopsea, vopsea lavabila, vopsea alba, vopsea interior',
    ),
    Product(
      id: '5',
      name: 'Parchet laminat',
      distributorName: 'Dedeman',
      aliases: 'parchet, parchet laminat, laminat, podea laminata',
    ),
    Product(
      id: '6',
      name: 'Țiglă metalică',
      distributorName: 'BauMax',
      aliases: 'tigla, tigla metalica, tabla cutata, tigla tabla',
    ),
    Product(
      id: '7',
      name: 'Sârmă sudură',
      distributorName: 'Hornbach',
      aliases: 'sarma, sarma sudura, electrozi, sarma sudare',
    ),
    Product(
      id: '8',
      name: 'Adeziv gresie',
      distributorName: 'Dedeman',
      aliases: 'adeziv, adeziv gresie, adeziv faiance, mortar adeziv',
    ),
    Product(
      id: '9',
      name: 'Izolație termică',
      distributorName: 'Hornbach',
      aliases: 'izolatie, polistiren, vata minerala, izolatie termica',
    ),
    Product(
      id: '10',
      name: 'Șuruburi lemn',
      distributorName: 'BauMax',
      aliases: 'suruburi, suruburi lemn, vis, surub',
    ),
  ];

  int _nextId = 11;

  Future<List<Product>> getAllProducts() async {
    await Future.delayed(const Duration(milliseconds: 300));
    return List.from(_products);
  }

  Future<Product?> getProductById(String id) async {
    await Future.delayed(const Duration(milliseconds: 200));
    try {
      return _products.firstWhere((p) => p.id == id);
    } catch (e) {
      return null;
    }
  }

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

  Future<bool> updateProduct(Product updatedProduct) async {
    await Future.delayed(const Duration(milliseconds: 500));

    final index = _products.indexWhere((p) => p.id == updatedProduct.id);

    if (index == -1) {
      return false;
    }

    _products[index] = updatedProduct;
    return true;
  }

  Future<bool> deleteProduct(String id) async {
    await Future.delayed(const Duration(milliseconds: 400));

    final initialLength = _products.length;
    _products.removeWhere((p) => p.id == id);

    return _products.length < initialLength;
  }

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

  Future<List<String>> getDistributors() async {
    await Future.delayed(const Duration(milliseconds: 200));
    return _products.map((p) => p.distributorName).toSet().toList()..sort();
  }
}