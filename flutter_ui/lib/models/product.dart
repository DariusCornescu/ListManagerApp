class Product {
  final String id;
  final String name;
  final String distributorName;
  final String aliases;

  Product({required this.id, required this.name, required this.distributorName, this.aliases = ''});

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'distributorName': distributorName,
        'aliases': aliases,
      };

  factory Product.fromJson(Map<String, dynamic> json) {
    return Product(
      id: json['id'] as String,
      name: json['name'] as String,
      distributorName: json['distributorName'] as String,
      aliases: json['aliases'] as String? ?? '',
    );
  }

  // Create copy with modifications
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