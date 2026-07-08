package com.darius.listmanager.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.data.local.entity.InventoryItemEntity
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.ui.viewmodel.InventoryViewModel
import com.darius.listmanager.util.InventoryMath

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    onBack: () -> Unit,
    viewModel: InventoryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var editingItem by remember { mutableStateOf<InventoryItemEntity?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.startListening() }

    fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.generatedPdf) {
        uiState.generatedPdf?.let {
            viewModel.createShareIntent()?.let { intent ->
                context.startActivity(Intent.createChooser(intent, "Trimite inventarul"))
            }
            viewModel.clearGeneratedPdf()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Inventar") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Rounded.DeleteSweep, "Listă nouă")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ===== Table =====
            if (uiState.items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Mic, contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Apasă microfonul și zi:\n„produs  cantitate  preț”",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // header row
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Produs", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Text("Cant.", Modifier.width(52.dp), style = MaterialTheme.typography.labelLarge)
                    Text("Preț", Modifier.width(72.dp), style = MaterialTheme.typography.labelLarge)
                    Text("Valoare", Modifier.width(80.dp), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(32.dp))
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(uiState.items, key = { it.id }) { item ->
                        InventoryRow(
                            item = item,
                            onClick = { editingItem = item },
                            onDelete = { viewModel.deleteItem(item.id) }
                        )
                        HorizontalDivider()
                    }
                }
            }

            // ===== Total + controls =====
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Total", style = MaterialTheme.typography.titleMedium)
                        Text(
                            InventoryMath.formatLei(uiState.totalBani),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // mic button — one spoken line per tap
                        Surface(
                            shape = CircleShape,
                            color = when (uiState.speechState) {
                                is SpeechState.Listening -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.primaryContainer
                            },
                            modifier = Modifier.size(64.dp),
                            onClick = {
                                when (uiState.speechState) {
                                    is SpeechState.Listening -> viewModel.stopListening()
                                    else -> checkPermissionAndStart()
                                }
                            }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (uiState.speechState is SpeechState.Listening)
                                        Icons.Rounded.Stop else Icons.Rounded.Mic,
                                    contentDescription = "Microfon",
                                    modifier = Modifier.size(32.dp),
                                    tint = when (uiState.speechState) {
                                        is SpeechState.Listening -> MaterialTheme.colorScheme.onPrimary
                                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                                    }
                                )
                            }
                        }
                        Button(
                            onClick = { viewModel.exportPdf() },
                            enabled = uiState.items.isNotEmpty() && !uiState.isGeneratingPdf,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (uiState.isGeneratingPdf) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.Description, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Export PDF")
                            }
                        }
                    }
                }
            }
        }
    }

    editingItem?.let { item ->
        EditInventoryRowDialog(
            item = item,
            onDismiss = { editingItem = null },
            onSave = { updated ->
                viewModel.updateItem(updated)
                editingItem = null
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Listă nouă") },
            text = { Text("Ștergi toate rândurile din inventarul curent?") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearList()
                }) { Text("Da, șterge tot") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Renunță") }
            }
        )
    }
}

@Composable
private fun InventoryRow(
    item: InventoryItemEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val valueBani = InventoryMath.lineValueBani(item.quantity, item.priceBani)
    Surface(onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                item.name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2
            )
            MissingAwareText(InventoryMath.formatQuantity(item.quantity), Modifier.width(52.dp))
            MissingAwareText(
                item.priceBani?.let { InventoryMath.formatLei(it) } ?: "",
                Modifier.width(72.dp)
            )
            Text(
                valueBani?.let { InventoryMath.formatLei(it) } ?: "—",
                Modifier.width(80.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Close, "Șterge",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Blank (missing) cells render as a highlighted em-dash inviting a tap-to-edit. */
@Composable
private fun MissingAwareText(text: String, modifier: Modifier) {
    if (text.isEmpty()) {
        Text(
            "—", modifier,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
    } else {
        Text(text, modifier, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EditInventoryRowDialog(
    item: InventoryItemEntity,
    onDismiss: () -> Unit,
    onSave: (InventoryItemEntity) -> Unit
) {
    var name by remember { mutableStateOf(item.name) }
    var quantityText by remember { mutableStateOf(InventoryMath.formatQuantity(item.quantity)) }
    var priceText by remember {
        mutableStateOf(item.priceBani?.let { b -> "${b / 100},${(b % 100).toString().padStart(2, '0')}" } ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifică rândul") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Produs") }, singleLine = true
                )
                OutlinedTextField(
                    value = quantityText, onValueChange = { quantityText = it },
                    label = { Text("Cantitate") }, singleLine = true
                )
                OutlinedTextField(
                    value = priceText, onValueChange = { priceText = it },
                    label = { Text("Preț (lei, ex. 4,50)") }, singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val quantity = quantityText.trim().replace(',', '.').toDoubleOrNull()
                    val priceBani = priceText.trim().replace(',', '.').toDoubleOrNull()
                        ?.let { Math.round(it * 100) }
                    onSave(
                        item.copy(
                            name = name.trim().ifBlank { item.name },
                            quantity = quantity,
                            priceBani = priceBani
                        )
                    )
                }
            ) { Text("Salvează") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Renunță") } }
    )
}
