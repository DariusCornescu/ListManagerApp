class Product {
  final String id;
  final String name;
  final String distributorName;
  final String aliases;

  Product({required this.id, required this.name, required this.distributorName, this.aliases = ''});

  Product copyWith({String? id, String? name, String? distributorName, String? aliases}) {
    return Product(
      id: id ?? this.id,
      name: name ?? this.name,
      distributorName: distributorName ?? this.distributorName,
      aliases: aliases ?? this.aliases,
    );
  }

  @override
  String toString() {
    return 'Product(id: $id, name: $name, distributor: $distributorName)';
  }
}