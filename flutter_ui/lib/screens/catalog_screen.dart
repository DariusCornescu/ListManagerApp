import 'package:flutter/material.dart';

import '../models/models.dart';
import '../state/auth_controller.dart';
import '../state/catalog_controller.dart';

class CatalogScreen extends StatelessWidget {
  const CatalogScreen({super.key, required this.catalog, required this.auth});

  final CatalogController catalog;
  final AuthController auth;

  Future<void> _showMessage(BuildContext context, String? message) async {
    if (message != null && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
    }
  }

  Future<void> _edit(BuildContext context, [Product? product]) async {
    final message = await showDialog<String?>(
      context: context,
      builder: (_) => _ProductDialog(catalog: catalog, product: product),
    );
    if (context.mounted) await _showMessage(context, message);
  }

  Future<void> _delete(BuildContext context, Product product) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Ștergi produsul?'),
        content: Text(product.name),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Anulează'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Șterge'),
          ),
        ],
      ),
    );
    if (confirmed == true && context.mounted) {
      final message = await catalog.delete(product.id);
      if (context.mounted) await _showMessage(context, message);
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([catalog, auth]),
      builder: (context, _) {
        // The server enforces this as well; hiding only avoids a guaranteed refusal.
        final isAdmin = auth.user?.isAdmin ?? false;
        final products = catalog.visibleProducts;
        return Scaffold(
          appBar: AppBar(title: const Text('Catalog')),
          floatingActionButton: isAdmin
              ? FloatingActionButton(
                  tooltip: 'Adaugă produs',
                  onPressed: () => _edit(context),
                  child: const Icon(Icons.add),
                )
              : null,
          body: Column(
            children: [
              Padding(
                padding: const EdgeInsets.all(16),
                child: TextField(
                  decoration: const InputDecoration(
                    labelText: 'Caută produse',
                    prefixIcon: Icon(Icons.search),
                  ),
                  onChanged: catalog.search,
                ),
              ),
              if (catalog.error != null)
                MaterialBanner(
                  content: Text(catalog.error!),
                  actions: [
                    TextButton(onPressed: catalog.refresh, child: const Text('Reîncearcă')),
                  ],
                ),
              Expanded(
                child: catalog.isLoading
                    ? const Center(child: CircularProgressIndicator())
                    : products.isEmpty
                    ? const Center(child: Text('Niciun produs găsit'))
                    : RefreshIndicator(
                        onRefresh: catalog.refresh,
                        child: ListView.builder(
                          itemCount: products.length,
                          itemBuilder: (context, index) {
                            final product = products[index];
                            return ListTile(
                              title: Text(product.name),
                              subtitle: Text(catalog.distributorName(product.distributorId)),
                              onTap: isAdmin ? () => _edit(context, product) : null,
                              trailing: isAdmin
                                  ? IconButton(
                                      tooltip: 'Șterge ${product.name}',
                                      icon: const Icon(Icons.delete_outline),
                                      onPressed: () => _delete(context, product),
                                    )
                                  : null,
                            );
                          },
                        ),
                      ),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _ProductDialog extends StatefulWidget {
  const _ProductDialog({required this.catalog, this.product});

  final CatalogController catalog;
  final Product? product;

  @override
  State<_ProductDialog> createState() => _ProductDialogState();
}

class _ProductDialogState extends State<_ProductDialog> {
  late final _name = TextEditingController(text: widget.product?.name);
  late final _aliases = TextEditingController(text: widget.product?.aliases);
  late int? _distributorId = widget.product?.distributorId;
  bool _isSaving = false;
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    _aliases.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final name = _name.text.trim();
    final distributorId = _distributorId;
    if (name.isEmpty || distributorId == null) {
      setState(() => _error = 'Completați numele și distribuitorul');
      return;
    }
    setState(() {
      _isSaving = true;
      _error = null;
    });
    final message = await widget.catalog.save(
      id: widget.product?.id,
      name: name,
      distributorId: distributorId,
      aliases: _aliases.text.trim(),
    );
    if (!mounted) return;
    if (message == null) {
      Navigator.pop(context);
    } else {
      setState(() {
        _isSaving = false;
        _error = message;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.product == null ? 'Produs nou' : 'Editează produsul'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _name,
              decoration: const InputDecoration(labelText: 'Nume'),
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<int>(
              initialValue: _distributorId,
              isExpanded: true,
              decoration: const InputDecoration(labelText: 'Distribuitor'),
              items: [
                for (final distributor in widget.catalog.distributors)
                  DropdownMenuItem(
                    value: distributor.id,
                    child: Text(distributor.name, overflow: TextOverflow.ellipsis),
                  ),
              ],
              onChanged: (value) => setState(() => _distributorId = value),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _aliases,
              decoration: const InputDecoration(labelText: 'Aliasuri, separate prin virgulă'),
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _isSaving ? null : () => Navigator.pop(context),
          child: const Text('Anulează'),
        ),
        FilledButton(
          onPressed: _isSaving ? null : _save,
          child: const Text('Salvează'),
        ),
      ],
    );
  }
}
