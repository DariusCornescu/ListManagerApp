import 'package:flutter/material.dart';

import '../models/models.dart';
import '../state/catalog_controller.dart';
import '../state/session_controller.dart';

class SessionScreen extends StatefulWidget {
  const SessionScreen({super.key, required this.session, required this.catalog});

  final SessionController session;
  final CatalogController catalog;

  @override
  State<SessionScreen> createState() => _SessionScreenState();
}

class _SessionScreenState extends State<SessionScreen> {
  QuantityConflict? _shownConflict;

  SessionController get _session => widget.session;

  @override
  void initState() {
    super.initState();
    _session.addListener(_onSessionChange);
  }

  @override
  void dispose() {
    _session.removeListener(_onSessionChange);
    super.dispose();
  }

  void _onSessionChange() {
    final conflict = _session.conflict;
    if (conflict != null && !identical(conflict, _shownConflict)) {
      _shownConflict = conflict;
      WidgetsBinding.instance.addPostFrameCallback((_) => _askAboutConflict(conflict));
    }
  }

  Future<void> _askAboutConflict(QuantityConflict conflict) async {
    if (!mounted) return;
    final keepMine = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        title: const Text('Ai de decis'),
        content: Text(
          'Altcineva a modificat „${conflict.server.displayName}” între timp.\n\n'
          'Varianta ta: ${conflict.wanted}\nVarianta lor: ${conflict.server.quantity}',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Varianta lor'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Varianta mea'),
          ),
        ],
      ),
    );
    if (keepMine == true) {
      await _session.keepMine();
    } else {
      _session.useServer();
    }
  }

  Future<void> _addProduct() async {
    final product = await showModalBottomSheet<Product>(
      context: context,
      isScrollControlled: true,
      builder: (_) => _ProductPicker(catalog: widget.catalog),
    );
    if (product != null) await _session.add(product);
  }

  Future<void> _confirmClear() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Golești lista?'),
        content: const Text('Toate produsele vor fi șterse din această listă.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Anulează'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Golește'),
          ),
        ],
      ),
    );
    if (confirmed == true) await _session.clear();
  }

  Future<void> _complete() async {
    final messenger = ScaffoldMessenger.of(context);
    final result = await _session.complete();
    if (result != null) {
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            'Listă finalizată: ${result.totalItems} bucăți, '
            '${result.distributorCount} distribuitori.',
          ),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _session,
      builder: (context, _) {
        final items = _session.items;
        return Scaffold(
          appBar: AppBar(
            title: Text(_session.session?.name ?? 'Lista curentă'),
            actions: [
              if (items.isNotEmpty)
                IconButton(
                  tooltip: 'Golește lista',
                  icon: const Icon(Icons.delete_sweep),
                  onPressed: _confirmClear,
                ),
            ],
          ),
          floatingActionButton: FloatingActionButton.extended(
            onPressed: _session.session == null ? null : _addProduct,
            icon: const Icon(Icons.add),
            label: const Text('Adaugă'),
          ),
          body: Column(
            children: [
              if (_session.error != null)
                MaterialBanner(
                  content: Text(_session.error!),
                  actions: [
                    TextButton(onPressed: _session.refresh, child: const Text('Reîncearcă')),
                    TextButton(onPressed: _session.clearError, child: const Text('Închide')),
                  ],
                ),
              Expanded(child: _buildList(items)),
              if (items.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 88),
                  child: SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: _complete,
                      icon: const Icon(Icons.check),
                      label: const Text('Finalizează lista'),
                    ),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildList(List<SessionItem> items) {
    if (_session.isLoading) return const Center(child: CircularProgressIndicator());
    if (items.isEmpty) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(24),
          child: Text('Lista este goală. Apasă Adaugă pentru primul produs.'),
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: _session.refresh,
      child: ListView.builder(
        itemCount: items.length,
        itemBuilder: (context, index) => _ItemRow(item: items[index], session: _session),
      ),
    );
  }
}

class _ItemRow extends StatelessWidget {
  const _ItemRow({required this.item, required this.session});

  final SessionItem item;
  final SessionController session;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      title: Text(item.displayName),
      subtitle: item.distributorName == null ? null : Text(item.distributorName!),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            tooltip: 'Scade cantitatea pentru ${item.displayName}',
            icon: const Icon(Icons.remove_circle_outline),
            onPressed: () => session.setQuantity(item, item.quantity - 1),
          ),
          Text('${item.quantity}', style: Theme.of(context).textTheme.titleMedium),
          IconButton(
            tooltip: 'Crește cantitatea pentru ${item.displayName}',
            icon: const Icon(Icons.add_circle_outline),
            onPressed: () => session.setQuantity(item, item.quantity + 1),
          ),
        ],
      ),
    );
  }
}

class _ProductPicker extends StatefulWidget {
  const _ProductPicker({required this.catalog});

  final CatalogController catalog;

  @override
  State<_ProductPicker> createState() => _ProductPickerState();
}

class _ProductPickerState extends State<_ProductPicker> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final wanted = CatalogController.normalize(_query.trim());
    final products = widget.catalog.products
        .where((p) => CatalogController.normalize('${p.name} ${p.aliases ?? ''}').contains(wanted))
        .toList();
    return SafeArea(
      child: SizedBox(
        height: MediaQuery.of(context).size.height * 0.8,
        child: Padding(
          padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.all(16),
                child: TextField(
                  autofocus: true,
                  decoration: const InputDecoration(
                    labelText: 'Caută produs',
                    prefixIcon: Icon(Icons.search),
                  ),
                  onChanged: (value) => setState(() => _query = value),
                ),
              ),
              Expanded(
                child: products.isEmpty
                    ? const Center(child: Text('Niciun produs găsit'))
                    : ListView.builder(
                        itemCount: products.length,
                        itemBuilder: (context, index) => ListTile(
                          title: Text(products[index].name),
                          subtitle: Text(
                            widget.catalog.distributorName(products[index].distributorId),
                          ),
                          onTap: () => Navigator.pop(context, products[index]),
                        ),
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
