class User {
  const User({
    required this.id,
    required this.username,
    required this.email,
    required this.role,
    this.fullName,
    this.company,
    this.address,
  });

  factory User.fromJson(Map<String, dynamic> json) => User(
    id: json['id'] as int,
    username: json['username'] as String,
    email: json['email'] as String,
    role: json['role'] as String,
    fullName: json['full_name'] as String?,
    company: json['company'] as String?,
    address: json['address'] as String?,
  );

  final int id;
  final String username;
  final String email;
  final String role;
  final String? fullName;
  final String? company;
  final String? address;

  bool get isAdmin => role == 'ADMIN';
}

class Distributor {
  const Distributor({required this.id, required this.name});

  factory Distributor.fromJson(Map<String, dynamic> json) => Distributor(
    id: json['id'] as int,
    name: json['distributor_name'] as String,
  );

  final int id;
  final String name;
}

class Product {
  const Product({
    required this.id,
    required this.name,
    required this.distributorId,
    this.aliases,
    this.price,
  });

  factory Product.fromJson(Map<String, dynamic> json) => Product(
    id: json['id'] as int,
    name: json['name'] as String,
    distributorId: json['distributor_id'] as int,
    aliases: json['aliases'] as String?,
    price: json['price']?.toString(),
  );

  final int id;
  final String name;
  final int distributorId;
  final String? aliases;

  /// A decimal string, exactly as the server sent it: never rounded through a double.
  final String? price;
}

class ShoppingSession {
  const ShoppingSession({
    required this.id,
    required this.name,
    required this.isActive,
    this.teamId,
  });

  factory ShoppingSession.fromJson(Map<String, dynamic> json) => ShoppingSession(
    id: json['id'] as int,
    name: json['name'] as String,
    isActive: json['is_active'] as bool,
    teamId: json['team_id'] as int?,
  );

  final int id;
  final String name;
  final bool isActive;
  final int? teamId;
}

class SessionItem {
  const SessionItem({
    required this.id,
    required this.productId,
    required this.quantity,
    required this.version,
    this.productName,
    this.distributorName,
  });

  factory SessionItem.fromJson(Map<String, dynamic> json) => SessionItem(
    id: json['id'] as int,
    productId: json['product_id'] as int,
    quantity: json['quantity'] as int,
    version: json['version'] as int,
    productName: json['product_name'] as String?,
    distributorName: json['distributor_name'] as String?,
  );

  final int id;
  final int productId;
  final int quantity;
  final int version;
  final String? productName;
  final String? distributorName;

  String get displayName => productName ?? 'Produs $productId';
}

/// A completed list, one group per distributor.
class CompletedSession {
  const CompletedSession({required this.distributorCount, required this.totalItems});

  factory CompletedSession.fromJson(Map<String, dynamic> json) => CompletedSession(
    distributorCount: json['distributor_count'] as int,
    totalItems: json['total_items'] as int,
  );

  final int distributorCount;
  final int totalItems;
}
