package com.darius.listmanager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                CatalogSelectionTopBar(
                    selectedCount = uiState.selectedProductIds.size,
                    onExit = viewModel::exitSelectionMode,
                    onDelete = { showDeleteDialog = true },
                    onSelectAll = viewModel::selectAll,
                    onClearSelection = viewModel::clearSelection,
                )
            } else {
                CatalogTopBar(
                    isAdmin = uiState.isAdmin,
                    onBack = onBack,
                    onRefresh = viewModel::refresh,
                    onEnterSelection = viewModel::enterSelectionMode,
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
                CatalogError(uiState.error, padding, onRetry = viewModel::refresh)
            }
            else -> {
                val filteredCatalog = viewModel.getFilteredCatalog()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    if (uiState.isOffline) CatalogOfflineBanner()

                    if (!uiState.isSelectionMode) {
                        CatalogSearchField(searchQuery) {
                            searchQuery = it
                            viewModel.setSearchQuery(it)
                        }
                    }

                    if (filteredCatalog.isEmpty()) {
                        CatalogEmptyState(searchQuery)
                    } else {
                        CatalogList(
                            catalog = filteredCatalog,
                            selectedProductIds = uiState.selectedProductIds,
                            isSelectionMode = uiState.isSelectionMode,
                            onProductLongPress = viewModel::onProductLongPress,
                            onProductClick = viewModel::onProductClick,
                            onEditProduct = onNavigateToEdit,
                        )
                    }
                }
            }
        }

        if (showDeleteDialog) {
            DeleteProductsDialog(
                selectedCount = uiState.selectedProductIds.size,
                onConfirm = {
                    viewModel.deleteSelectedProducts()
                    showDeleteDialog = false
                },
                onDismiss = { showDeleteDialog = false },
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
