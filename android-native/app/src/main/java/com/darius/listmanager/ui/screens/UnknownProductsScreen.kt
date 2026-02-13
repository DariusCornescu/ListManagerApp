package com.darius.listmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.ui.components.EditProductBottomSheet
import com.darius.listmanager.ui.viewmodel.UnknownViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnknownProductsScreen(
    onBack: () -> Unit,
    viewModel: UnknownViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedProductId by remember { mutableStateOf<Long?>(null) }
    var selectedProductText by remember { mutableStateOf<String?>(null) }
    var showEditSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Show save message as snackbar
    LaunchedEffect(uiState.saveMessage) {
        uiState.saveMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearSaveMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Produse Necunoscute") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, "Back")
                    }
                }
            )
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
            uiState.error != null -> {
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
                            Icons.Rounded.Error,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "Error: ${uiState.error}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            uiState.unknownProducts.isEmpty() -> {
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
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Toate produsele au fost adaugate!",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    "Apasă pe orice produs pentru a-l adăuga în catalog",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    items(uiState.unknownProducts, key = { it.id }) { product ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                selectedProductId = product.id
                                selectedProductText = product.spokenText
                                showEditSheet = true
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        product.spokenText,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            selectedProductId = product.id
                                            selectedProductText = product.spokenText
                                            showEditSheet = true
                                        }
                                    ) {
                                        Icon(
                                            Icons.Rounded.Add,
                                            contentDescription = "Adaugă în catalog",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            viewModel.deleteUnknownProduct(product.id)
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Sters")
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = "Sterge",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showEditSheet && selectedProductText != null && selectedProductId != null) {
            EditProductBottomSheet(
                productName = selectedProductText!!,
                distributors  = uiState.distributors,
                onDismiss = { showEditSheet = false },
                onSave = { name, distributor, aliases ->
                    viewModel.saveProduct(
                        unknownProductId = selectedProductId!!,
                        productName = name,
                        distributorName = distributor,
                        aliases = aliases
                    )
                    showEditSheet = false
                }
            )
        }
    }
}