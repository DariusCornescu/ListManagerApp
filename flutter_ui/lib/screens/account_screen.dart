import 'package:flutter/material.dart';

import '../services/api_client.dart';
import '../state/auth_controller.dart';

class AccountScreen extends StatelessWidget {
  const AccountScreen({super.key, required this.auth});

  final AuthController auth;

  Future<void> _editProfile(BuildContext context) async {
    final user = auth.user;
    final fullName = TextEditingController(text: user?.fullName);
    final company = TextEditingController(text: user?.company);
    final address = TextEditingController(text: user?.address);
    final save = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Editează profilul'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: fullName,
                maxLength: 100,
                decoration: const InputDecoration(labelText: 'Nume complet'),
              ),
              TextField(
                controller: company,
                maxLength: 100,
                decoration: const InputDecoration(labelText: 'Companie'),
              ),
              TextField(
                controller: address,
                maxLength: 255,
                decoration: const InputDecoration(labelText: 'Adresă'),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Anulează'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Salvează'),
          ),
        ],
      ),
    );
    if (save != true) return;
    String message = 'Profil actualizat';
    try {
      await auth.updateProfile(
        fullName: fullName.text,
        company: company.text,
        address: address.text,
      );
    } on NetworkException {
      message = 'Nu există conexiune. Profilul nu a fost salvat.';
    } on ApiException catch (e) {
      message = e.message;
    }
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: auth,
      builder: (context, _) {
        final user = auth.user;
        return Scaffold(
          appBar: AppBar(title: const Text('Contul meu')),
          body: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              ListTile(
                leading: const Icon(Icons.person),
                title: const Text('Utilizator'),
                subtitle: Text(user?.username ?? '-'),
              ),
              ListTile(
                leading: const Icon(Icons.email),
                title: const Text('Email'),
                subtitle: Text(user?.email ?? '-'),
              ),
              ListTile(
                leading: const Icon(Icons.account_circle),
                title: const Text('Nume complet'),
                subtitle: Text(user?.fullName ?? '-'),
              ),
              ListTile(
                leading: const Icon(Icons.business),
                title: const Text('Companie'),
                subtitle: Text(user?.company ?? '-'),
              ),
              ListTile(
                leading: const Icon(Icons.place),
                title: const Text('Adresă'),
                subtitle: Text(user?.address ?? '-'),
              ),
              const SizedBox(height: 16),
              OutlinedButton.icon(
                onPressed: user == null ? null : () => _editProfile(context),
                icon: const Icon(Icons.edit),
                label: const Text('Editează profilul'),
              ),
              const SizedBox(height: 8),
              FilledButton.icon(
                onPressed: auth.logout,
                icon: const Icon(Icons.logout),
                label: const Text('Deconectare'),
              ),
            ],
          ),
        );
      },
    );
  }
}
