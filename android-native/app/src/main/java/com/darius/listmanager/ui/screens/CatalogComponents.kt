package com.darius.listmanager.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.ui.viewmodel.DistributorWithProducts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogSelectionTopBar(
    selectedCount: Int,
    onExit: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
) {
    TopAppBar(
        title = {
            Text("$selectedCount selectat${if (selectedCount != 1) "e" else ""}")
        },
        navigationIcon = {
            IconButton(
                onClick = onExit,
                modifier = Modifier.semantics {
                    contentDescription = "Ieși din modul selecție"
                }
            ) {
                Icon(Icons.Rounded.Close, "Închide selecția")
            }
        },
        actions = {
            if (selectedCount > 0) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics {
                        contentDescription = "Șterge $selectedCount produse"
                    }
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        "Șterge selectate",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Optional: Select All / Clear All menu
            var showMenu by remember { mutableStateOf(false) }
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Rounded.MoreVert, "Mai multe opțiuni")
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Selectează Tot") },
                    onClick = {
                        onSelectAll()
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.CheckCircle, null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Șterge Selecția") },
                    onClick = {
                        onClearSelection()
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Clear, null)
                    }
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogTopBar(
    isAdmin: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onEnterSelection: () -> Unit,
) {
    TopAppBar(
        title = { Text("Catalog Produse") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "Înapoi")
            }
        },
        actions = {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, "Reîmprospătează")
            }

            // Manual selection mode toggle (leads to delete) — doar ADMIN
            if (isAdmin) {
                var showMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Rounded.MoreVert, "Mai multe opțiuni")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Selectează Produse") },
                        onClick = {
                            onEnterSelection()
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Checklist, null)
                        }
                    )
                }
            }
        }
    )
}

@Composable
internal fun CatalogError(message: String?, padding: PaddingValues, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Rounded.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                message ?: "A apărut o eroare",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Button(onClick = onRetry) {
                Text("Încearcă din nou")
            }
        }
    }
}

@Composable
internal fun CatalogOfflineBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Mod offline - Se afișează datele salvate local",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
internal fun CatalogSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        placeholder = { Text("Caută produse...") },
        leadingIcon = {
            Icon(Icons.Rounded.Search, "Caută")
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Clear, "Șterge")
                }
            }
        },
        singleLine = true
    )
}

@Composable
internal fun CatalogEmptyState(query: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Rounded.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (query.isEmpty()) "Niciun produs în catalog" else "Nu s-au găsit rezultate",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun CatalogList(
    catalog: List<DistributorWithProducts>,
    selectedProductIds: Set<Long>,
    isSelectionMode: Boolean,
    onProductLongPress: (Long) -> Unit,
    onProductClick: (Long) -> Unit,
    onEditProduct: (Long) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        catalog.forEach { distributorWithProducts ->
            item(key = "distributor_${distributorWithProducts.distributor.id}") {
                DistributorCard(
                    distributorName = distributorWithProducts.distributor.distributorName,
                    products = distributorWithProducts.products,
                    selectedProductIds = selectedProductIds,
                    isSelectionMode = isSelectionMode,
                    onProductLongPress = { productId ->
                        onProductLongPress(productId)
                    },
                    onProductClick = { productId ->
                        onProductClick(productId)
                    },
                    onEditProduct = { productId ->
                        onEditProduct(productId)
                    }
                )
            }
        }
    }
}

@Composable
internal fun DeleteProductsDialog(selectedCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Șterge Produse") },
        text = {
            Text(
                "Ștergi $selectedCount produs${if (selectedCount != 1) "e" else ""}? " +
                        "Această acțiune nu poate fi anulată."
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Șterge")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Anulează")
            }
        }
    )
}

@Composable
internal fun DistributorCard(
    distributorName: String,
    products: List<ProductEntity>,
    selectedProductIds: Set<Long>,
    isSelectionMode: Boolean,
    onProductLongPress: (Long) -> Unit,
    onProductClick: (Long) -> Unit,
    onEditProduct: (Long) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Business,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        distributorName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        "${products.size}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            HorizontalDivider()

            products.forEach { product ->
                ProductItem(
                    product = product,
                    isSelected = selectedProductIds.contains(product.id),
                    isSelectionMode = isSelectionMode,
                    onLongPress = { onProductLongPress(product.id) },
                    onClick = { onProductClick(product.id) },
                    onEdit = { onEditProduct(product.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddProductDialog(
    distributors: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (productName: String, distributorName: String, aliases: String) -> Unit
) {
    var productName by remember { mutableStateOf("") }
    var selectedDistributor by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var aliases by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adaugă Produs Nou") },
        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = productName,
                    onValueChange = { productName = it },
                    label = { Text("Nume Produs *") },
                    placeholder = { Text("ex: Lapte") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Dropdown pentru distribuitori
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedDistributor,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Distribuitor *") },
                        placeholder = { Text("Selectează distribuitor") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        distributors.forEach { distributor ->
                            DropdownMenuItem(
                                text = { Text(distributor) },
                                onClick = {
                                    selectedDistributor = distributor
                                    expanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
                
                OutlinedTextField(
                    value = aliases,
                    onValueChange = { aliases = it },
                    label = { Text("Aliasuri (opțional)") },
                    placeholder = { Text("ex: lapte, milk") },
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Text(
                    "* Câmpuri obligatorii",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (productName.isNotBlank() && selectedDistributor.isNotBlank()) {
                        onConfirm(productName.trim(), selectedDistributor.trim(), aliases.trim())
                    }
                },
                enabled = productName.isNotBlank() && selectedDistributor.isNotBlank()
            ) {
                Text("Adaugă")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Anulează")
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProductItem(
    product: ProductEntity,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "product_bg_animation"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
            .semantics {
                contentDescription = if (isSelected) {
                    "${product.name}, selectat"
                } else {
                    product.name
                }
            },
        color = backgroundColor,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox in selection mode
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null, // Handled by parent click
                    modifier = Modifier.semantics {
                        contentDescription = if (isSelected) "Selectat" else "Neselectat"
                    }
                )
                Spacer(Modifier.width(8.dp))
            }

            // Product info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    product.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (!product.aliases.isNullOrBlank()) {
                    Text(
                        "Aliasuri: ${product.aliases}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Edit icon in selection mode
            if (isSelectionMode) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.semantics {
                        contentDescription = "Editează ${product.name}"
                    }
                ) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
