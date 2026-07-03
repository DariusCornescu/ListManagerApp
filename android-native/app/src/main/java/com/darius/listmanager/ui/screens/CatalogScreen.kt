package com.darius.listmanager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.ui.viewmodel.CatalogViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onBack: () -> Unit,
    onNavigateToEdit: (Long) -> Unit = {},
    viewModel: CatalogViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Trigger initial load
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    // Refresh when returning from edit screen (composition resumes)
    DisposableEffect(Unit) {
        onDispose {
            // When screen is being disposed (navigating away)
        }
    }

    // Alternative: Use lifecycle to refresh on resume
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Handle delete result message
    LaunchedEffect(uiState.deleteMessage) {
        uiState.deleteMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearDeleteMessage()
        }
    }

    // Handle back press in selection mode
    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.exitSelectionMode()
    }

    // State for Add Product Dialog
    var showAddProductDialog by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // FAB pentru adăugare manuală de produse (doar ADMIN)
            if (uiState.isAdmin && !uiState.isSelectionMode && !uiState.isLoading) {
                FloatingActionButton(
                    onClick = { showAddProductDialog = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Adaugă produs manual")
                }
            }
        },
        topBar = {
            if (uiState.isSelectionMode) {
                // Contextual selection app bar
                TopAppBar(
                    title = {
                        Text("${uiState.selectedProductIds.size} selectat${if (uiState.selectedProductIds.size != 1) "e" else ""}")
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { viewModel.exitSelectionMode() },
                            modifier = Modifier.semantics {
                                contentDescription = "Ieși din modul selecție"
                            }
                        ) {
                            Icon(Icons.Rounded.Close, "Închide selecția")
                        }
                    },
                    actions = {
                        if (uiState.selectedProductIds.isNotEmpty()) {
                            IconButton(
                                onClick = { showDeleteDialog = true },
                                modifier = Modifier.semantics {
                                    contentDescription = "Șterge ${uiState.selectedProductIds.size} produse"
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
                                    viewModel.selectAll()
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.CheckCircle, null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Șterge Selecția") },
                                onClick = {
                                    viewModel.clearSelection()
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
            } else {
                // Normal app bar
                TopAppBar(
                    title = { Text("Catalog Produse") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Rounded.ArrowBack, "Înapoi")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Rounded.Refresh, "Reîmprospătează")
                        }

                        // Manual selection mode toggle (leads to delete) — doar ADMIN
                        if (uiState.isAdmin) {
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
                                        viewModel.enterSelectionMode()
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
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null && uiState.distributors.isEmpty() -> {
                // Only show full-screen error if we have NO data to display
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
                            uiState.error ?: "A apărut o eroare",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Button(onClick = { viewModel.refresh() }) {
                            Text("Încearcă din nou")
                        }
                    }
                }
            }
            else -> {
                val filteredCatalog = viewModel.getFilteredCatalog()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    // Offline indicator banner - rubric requirement
                    if (uiState.isOffline) {
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
                    
                    // Search bar (only show when not in selection mode)
                    if (!uiState.isSelectionMode) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                viewModel.setSearchQuery(it)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            placeholder = { Text("Caută produse...") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Search, "Caută")
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        searchQuery = ""
                                        viewModel.setSearchQuery("")
                                    }) {
                                        Icon(Icons.Rounded.Clear, "Șterge")
                                    }
                                }
                            },
                            singleLine = true
                        )
                    }

                    if (filteredCatalog.isEmpty()) {
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
                                    if (searchQuery.isEmpty()) "Niciun produs în catalog" else "Nu s-au găsit rezultate",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            filteredCatalog.forEach { distributorWithProducts ->
                                item(key = "distributor_${distributorWithProducts.distributor.id}") {
                                    DistributorCard(
                                        distributorName = distributorWithProducts.distributor.distributorName,
                                        products = distributorWithProducts.products,
                                        selectedProductIds = uiState.selectedProductIds,
                                        isSelectionMode = uiState.isSelectionMode,
                                        onProductLongPress = { productId ->
                                            viewModel.onProductLongPress(productId)
                                        },
                                        onProductClick = { productId ->
                                            viewModel.onProductClick(productId)
                                        },
                                        onEditProduct = { productId ->
                                            onNavigateToEdit(productId)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Delete confirmation dialog
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Șterge Produse") },
                text = {
                    Text(
                        "Ștergi ${uiState.selectedProductIds.size} produs${if (uiState.selectedProductIds.size != 1) "e" else ""}? " +
                                "Această acțiune nu poate fi anulată."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteSelectedProducts()
                            showDeleteDialog = false
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Șterge")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Anulează")
                    }
                }
            )
        }
        
        // Add Product Dialog
        if (showAddProductDialog) {
            AddProductDialog(
                distributors = uiState.distributors.map { it.distributor.distributorName }.sorted(),
                onDismiss = { showAddProductDialog = false },
                onConfirm = { productName, distributorName, aliases ->
                    scope.launch {
                        try {
                            viewModel.addProduct(productName, distributorName, aliases)
                            showAddProductDialog = false
                            snackbarHostState.showSnackbar("Produs adăugat: $productName")
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Eroare: ${e.message}")
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun DistributorCard(
    distributorName: String,
    products: List<com.darius.listmanager.data.local.entity.ProductEntity>,
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
private fun AddProductDialog(
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
    product: com.darius.listmanager.data.local.entity.ProductEntity,
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