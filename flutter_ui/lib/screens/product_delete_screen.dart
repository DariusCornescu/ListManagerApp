import 'package:flutter/material.dart';
import '../models/product.dart';
import '../services/fake_product_service.dart';

class ProductDeleteScreen extends StatefulWidget {
  final Product product;

  const ProductDeleteScreen({
    super.key,
    required this.product,
  });

  @override
  State<ProductDeleteScreen> createState() => _ProductDeleteScreenState();
}

class _ProductDeleteScreenState extends State<ProductDeleteScreen> {
  final FakeProductService _service = FakeProductService();
  bool _isDeleting = false;
  bool _confirmDelete = false;

  Future<void> _deleteProduct() async {
    if (!_confirmDelete) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Please confirm deletion by checking the box'),
          backgroundColor: Colors.orange,
        ),
      );
      return;
    }

    setState(() => _isDeleting = true);

    try {
      final success = await _service.deleteProduct(widget.product.id);

      if (success && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Product deleted successfully!'),
            backgroundColor: Colors.green,
          ),
        );
        Navigator.pop(context, true);
      } else if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Failed to delete product'),
            backgroundColor: Colors.red,
          ),
        );
        setState(() => _isDeleting = false);
      }
    } catch (e) {
      setState(() => _isDeleting = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Error deleting product: $e'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Delete Product'),
        backgroundColor: Colors.red.shade700,
        foregroundColor: Colors.white,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            const SizedBox(height: 20),
            
            // Warning icon
            Container(
              padding: const EdgeInsets.all(24),
              decoration: BoxDecoration(
                color: Colors.red.shade50,
                shape: BoxShape.circle,
              ),
              child: Icon(
                Icons.warning_amber_rounded,
                size: 80,
                color: Colors.red.shade700,
              ),
            ),
            const SizedBox(height: 32),
            
            // Warning title
            const Text(
              'Delete Product?',
              style: TextStyle(
                fontSize: 28,
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            
            // Warning message
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.red.shade50,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.red.shade200),
              ),
              child: Column(
                children: [
                  Icon(
                    Icons.error_outline,
                    color: Colors.red.shade700,
                    size: 32,
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    'This action cannot be undone!',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'The product will be permanently removed from the system.',
                    style: TextStyle(fontSize: 14),
                    textAlign: TextAlign.center,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 32),
            
            // Product details card
            Card(
              elevation: 4,
              child: Padding(
                padding: const EdgeInsets.all(20.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Product to be deleted:',
                      style: TextStyle(
                        fontSize: 14,
                        color: Colors.grey,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(height: 16),
                    
                    _buildDetailRow(
                      icon: Icons.tag,
                      label: 'ID',
                      value: widget.product.id,
                    ),
                    const Divider(height: 24),
                    
                    _buildDetailRow(
                      icon: Icons.label,
                      label: 'Name',
                      value: widget.product.name,
                    ),
                    const Divider(height: 24),
                    
                    _buildDetailRow(
                      icon: Icons.business,
                      label: 'Distributor',
                      value: widget.product.distributorName,
                    ),
                    
                    if (widget.product.aliases.isNotEmpty) ...[
                      const Divider(height: 24),
                      _buildDetailRow(
                        icon: Icons.auto_awesome,
                        label: 'Aliases',
                        value: widget.product.aliases,
                      ),
                    ],
                  ],
                ),
              ),
            ),
            const SizedBox(height: 32),
            
            // Confirmation checkbox
            Card(
              color: Colors.grey.shade50,
              child: CheckboxListTile(
                value: _confirmDelete,
                onChanged: _isDeleting
                    ? null
                    : (value) {
                        setState(() => _confirmDelete = value ?? false);
                      },
                title: const Text(
                  'I understand this action is permanent',
                  style: TextStyle(fontWeight: FontWeight.w600),
                ),
                subtitle: const Text(
                  'Check this box to confirm deletion',
                  style: TextStyle(fontSize: 12),
                ),
                activeColor: Colors.red,
                controlAffinity: ListTileControlAffinity.leading,
              ),
            ),
            const SizedBox(height: 32),
            
            // Action buttons
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: _isDeleting
                        ? null
                        : () => Navigator.pop(context),
                    style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.all(16),
                    ),
                    child: const Text('Cancel'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: ElevatedButton(
                    onPressed: _isDeleting ? null : _deleteProduct,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.red,
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.all(16),
                    ),
                    child: _isDeleting
                        ? const SizedBox(
                            height: 20,
                            width: 20,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              valueColor: AlwaysStoppedAnimation<Color>(
                                Colors.white,
                              ),
                            ),
                          )
                        : const Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(Icons.delete_forever, size: 20),
                              SizedBox(width: 8),
                              Text('Delete'),
                            ],
                          ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildDetailRow({
    required IconData icon,
    required String label,
    required String value,
  }) {
    return Row(
      children: [
        Icon(icon, size: 20, color: Colors.grey.shade600),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: TextStyle(
                  fontSize: 12,
                  color: Colors.grey.shade600,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                value,
                style: const TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}