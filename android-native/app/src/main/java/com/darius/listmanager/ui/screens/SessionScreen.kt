package com.darius.listmanager.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.ui.components.SessionItemRow
import com.darius.listmanager.ui.viewmodel.SessionViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    onBack: () -> Unit,
    viewModel: SessionViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Show share dialog when PDFs are generated
    LaunchedEffect(uiState.generatedPdfs) {
        uiState.generatedPdfs?.let { pdfs ->
            val intent = viewModel.createShareIntent()
            if (intent != null) {
                context.startActivity(Intent.createChooser(intent, "Distribuie PDF-urile Comenzii"))
            }

            scope.launch {
                snackbarHostState.showSnackbar(
                    "Am generat ${pdfs.size} liste. Sunt gata de o noua sesiune."
                )
            }

            viewModel.clearGeneratedPdfs()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Sesiune Curentă") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Înapoi")
                    }
                },
                actions = {
                    if (uiState.items.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Rounded.Delete, "Șterge Sesiunea")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.items.isNotEmpty() && !uiState.isGeneratingPdfs) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.generatePdfs() },
                    icon = { Icon(Icons.Rounded.PictureAsPdf, "Generează") },
                    text = { Text("Generează PDF-uri") }
                )
            }

            // Show loading FAB while generating
            if (uiState.isGeneratingPdfs) {
                ExtendedFloatingActionButton(
                    onClick = { /* Disabled while generating */ },
                    icon = {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    },
                    text = {
                        Text(uiState.pdfGenerationProgress ?: "Generez...")
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
                    }
                }
            }
            uiState.items.isEmpty() -> {
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
                            Icons.Rounded.ShoppingCart,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Sesiunea este goală",
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
                    items(uiState.items, key = { it.id }) { item ->
                        SessionItemRow(
                            item = item,
                            onQuantityChange = { newQty ->
                                viewModel.updateQuantity(item.productId, newQty)
                            },
                            onDelete = {
                                viewModel.deleteItem(item.id)
                            }
                        )
                    }
                }
            }
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("Șterge Sesiunea") },
                text = { Text("Sunteți sigur că doriți să ștergeți toate produsele din sesiunea curentă?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.clearSession()
                            showClearDialog = false
                            scope.launch {
                                snackbarHostState.showSnackbar("Sesiune ștearsă")
                            }
                        }
                    ) {
                        Text("Șterge")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text("Anulează")
                    }
                }
            )
        }
    }
}