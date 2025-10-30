package com.darius.listmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.ui.viewmodel.EditProductViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProductScreen(
    productId: Long,
    onBack: () -> Unit,
    viewModel: EditProductViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Load product data
    LaunchedEffect(productId) {
        viewModel.loadProduct(productId)
    }

    // Show messages
    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    // Handle successful save/delete
    LaunchedEffect(uiState.operationSuccess) {
        if (uiState.operationSuccess) {
            onBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Editează Produs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, "Înapoi")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        enabled = !uiState.isLoading && !uiState.isSaving
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            "Șterge produsul",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(
                        onClick = { viewModel.saveProduct() },
                        enabled = !uiState.isLoading && !uiState.isSaving && uiState.hasChanges
                    ) {
                        Icon(Icons.Rounded.Check, "Salvează")
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
                            "Eroare: ${uiState.error}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { viewModel.loadProduct(productId) }) {
                            Text("Reîncearcă")
                        }
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Product Name
                    OutlinedTextField(
                        value = uiState.productName,
                        onValueChange = { viewModel.updateProductName(it) },
                        label = { Text("Nume Produs *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !uiState.isSaving,
                        isError = uiState.productName.isBlank()
                    )

                    // Distributor (read-only, shows current distributor)
                    OutlinedTextField(
                        value = uiState.distributorName,
                        onValueChange = {},
                        label = { Text("Distribuitor") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = false,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )

                    // Aliases
                    OutlinedTextField(
                        value = uiState.aliases,
                        onValueChange = { viewModel.updateAliases(it) },
                        label = { Text("Aliasuri (separate prin virgulă)") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("ex: lapte, lapte proaspăt, lapte de vacă") },
                        supportingText = {
                            Text("Opțional: Adaugă nume alternative pentru recunoaștere vocală mai bună")
                        },
                        minLines = 2,
                        enabled = !uiState.isSaving
                    )

                    Spacer(Modifier.weight(1f))

                    // Save button
                    Button(
                        onClick = { viewModel.saveProduct() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isSaving && uiState.hasChanges && uiState.productName.isNotBlank()
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (uiState.isSaving) "Salvez..." else "Salvează Modificările")
                    }

                    if (uiState.hasChanges) {
                        Text(
                            "Aveți modificări nesalvate",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }

        // Delete confirmation dialog
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Șterge Produs") },
                text = {
                    Text("Ștergi \"${uiState.productName}\"? Această acțiune nu poate fi anulată.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteProduct()
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
    }
}