package com.darius.listmanager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.ui.components.AppEmptyState
import com.darius.listmanager.ui.components.AppSecondaryButton
import com.darius.listmanager.ui.components.BannerTone
import com.darius.listmanager.ui.components.ConfirmSheet
import com.darius.listmanager.ui.components.ProductFormSheet
import com.darius.listmanager.ui.components.SkeletonRows
import com.darius.listmanager.ui.components.StatusBanner
import com.darius.listmanager.ui.viewmodel.CatalogViewModel
import kotlinx.coroutines.launch

@Composable
fun CatalogScreen(
    onBack: () -> Unit,
    onNavigateToEdit: (Long) -> Unit = {},
    viewModel: CatalogViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteSheet by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Loads when the screen opens, and again on returning from editing a product.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.deleteMessage) {
        uiState.deleteMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearDeleteMessage()
        }
    }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.exitSelectionMode()
    }

    val totalProducts = uiState.distributors.sumOf { it.products.size }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (uiState.isAdmin && !uiState.isSelectionMode && !uiState.isLoading) {
                ExtendedFloatingActionButton(
                    onClick = { showAddSheet = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Produs nou", fontWeight = FontWeight.ExtraBold) },
                )
            }
        },
        topBar = {
            if (uiState.isSelectionMode) {
                CatalogSelectionTopBar(
                    selectedCount = uiState.selectedProductIds.size,
                    totalCount = totalProducts,
                    onExit = viewModel::exitSelectionMode,
                    onDelete = { showDeleteSheet = true },
                    onSelectAll = viewModel::selectAll,
                    onClearSelection = viewModel::clearSelection,
                )
            } else {
                CatalogTopBar(
                    subtitle = if (uiState.isLoading) null else catalogSubtitle(totalProducts, uiState.distributors.size),
                    isAdmin = uiState.isAdmin,
                    onBack = onBack,
                    onRefresh = viewModel::refresh,
                    onEnterSelection = viewModel::enterSelectionMode,
                )
            }
        }
    ) { padding ->
        val filtered = remember(uiState.distributors, uiState.searchQuery) { viewModel.getFilteredCatalog() }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> SkeletonRows(
                    count = 8,
                    rowHeight = 60.dp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
                uiState.error != null && uiState.distributors.isEmpty() -> AppEmptyState(
                    icon = Icons.Rounded.CloudOff,
                    title = "Nu am putut încărca catalogul",
                    message = "Verifică conexiunea și încearcă din nou.",
                    action = { AppSecondaryButton("Încearcă din nou", viewModel::refresh, Modifier.width(220.dp)) },
                )
                else -> {
                    if (uiState.isOffline) {
                        StatusBanner(
                            "Ești offline. Vezi produsele salvate pe telefon.",
                            tone = BannerTone.Info,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        )
                    }
                    if (!uiState.isSelectionMode) {
                        CatalogSearchField(
                            uiState.searchQuery,
                            viewModel::setSearchQuery,
                            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                    if (filtered.isEmpty()) {
                        CatalogEmptyState(uiState.searchQuery)
                    } else {
                        CatalogList(
                            catalog = filtered,
                            searching = uiState.searchQuery.isNotBlank(),
                            isAdmin = uiState.isAdmin,
                            isSelectionMode = uiState.isSelectionMode,
                            selectedProductIds = uiState.selectedProductIds,
                            onProductLongPress = viewModel::onProductLongPress,
                            onProductClick = viewModel::onProductClick,
                            onEditProduct = onNavigateToEdit,
                        )
                    }
                }
            }
        }
    }

    if (showDeleteSheet) {
        val count = uiState.selectedProductIds.size
        ConfirmSheet(
            title = if (count == 1) "Ștergi un produs?" else "Ștergi $count produse?",
            message = "Această acțiune nu poate fi anulată.",
            confirmLabel = "Șterge",
            onConfirm = {
                viewModel.deleteSelectedProducts()
                showDeleteSheet = false
            },
            onDismiss = { showDeleteSheet = false },
        )
    }

    if (showAddSheet) {
        ProductFormSheet(
            title = "Produs nou",
            distributors = uiState.distributors.map { it.distributor.distributorName },
            onDismiss = { showAddSheet = false },
            onSave = { productName, distributorName, aliases ->
                scope.launch {
                    try {
                        viewModel.addProduct(productName, distributorName, aliases)
                        showAddSheet = false
                        snackbarHostState.showSnackbar("Produs adăugat: $productName")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: SecurityException) {
                        snackbarHostState.showSnackbar(e.message ?: "Doar administratorii pot modifica catalogul")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Nu am putut adăuga produsul. Încearcă din nou.")
                    }
                }
            },
        )
    }
}
