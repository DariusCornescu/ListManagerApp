package com.darius.listmanager.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.data.local.entity.InventoryItemEntity
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.ui.components.AppDangerSoftButton
import com.darius.listmanager.ui.components.AppEmptyState
import com.darius.listmanager.ui.components.AppIconButton
import com.darius.listmanager.ui.components.AppPrimaryButton
import com.darius.listmanager.ui.components.AppSecondaryButton
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.ConfirmSheet
import com.darius.listmanager.ui.components.SaveStateChip
import com.darius.listmanager.ui.components.SheetBody
import com.darius.listmanager.ui.components.pressScale
import com.darius.listmanager.ui.theme.Motion
import com.darius.listmanager.ui.viewmodel.InventoryUiState
import com.darius.listmanager.ui.viewmodel.InventoryViewModel
import com.darius.listmanager.util.InventoryMath
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    onBack: () -> Unit,
    viewModel: InventoryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var editingItem by remember { mutableStateOf<InventoryItemEntity?>(null) }
    var showClearSheet by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startListening()
        } else {
            scope.launch { snackbarHostState.showSnackbar("Am nevoie de acces la microfon ca să te ascult") }
        }
    }

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

    LaunchedEffect(uiState.speechState) {
        (uiState.speechState as? SpeechState.Error)?.let { snackbarHostState.showSnackbar(it.message) }
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
            AppTopBar(
                title = "Inventar",
                subtitle = inventorySubtitle(uiState.items.size),
                onBack = onBack,
                actions = {
                    SaveStateChip()
                    if (uiState.items.isNotEmpty()) {
                        AppIconButton(Icons.Rounded.DeleteSweep, "Listă nouă", { showClearSheet = true })
                    }
                },
            )
        },
        bottomBar = {
            InventoryFooter(
                state = uiState,
                onMic = {
                    if (uiState.speechState is SpeechState.Listening) viewModel.stopListening() else checkPermissionAndStart()
                },
                onExport = viewModel::exportPdf,
                onContinuousChange = viewModel::setContinuous,
            )
        },
    ) { padding ->
        InventoryContent(
            items = uiState.items,
            onEdit = { editingItem = it },
            modifier = Modifier.padding(padding),
        )
    }

    editingItem?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { editingItem = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            InventoryEditContent(
                item = item,
                onSave = {
                    viewModel.updateItem(it)
                    editingItem = null
                },
                onDelete = {
                    viewModel.deleteItem(item)
                    editingItem = null
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        val result = snackbarHostState.showSnackbar(
                            message = "Rând șters: ${item.name}",
                            actionLabel = "Anulează",
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.restoreItem(item)
                    }
                },
                onDismiss = { editingItem = null },
            )
        }
    }

    if (showClearSheet) {
        ConfirmSheet(
            title = "Listă nouă",
            message = "Ștergi toate rândurile din inventarul curent?",
            confirmLabel = "Șterge tot",
            onConfirm = {
                showClearSheet = false
                viewModel.clearList()
            },
            onDismiss = { showClearSheet = false },
        )
    }
}

internal fun inventorySubtitle(count: Int): String = when (count) {
    0 -> "Niciun rând"
    1 -> "1 rând"
    else -> "$count rânduri"
}

/** What the second line of a row says: the sum, or what's still missing from it. */
internal fun inventoryDetail(item: InventoryItemEntity): String {
    val quantity = InventoryMath.formatQuantity(item.quantity)
    return when {
        item.quantity == null && item.priceBani == null -> "Lipsesc cantitatea și prețul"
        item.quantity == null -> "Lipsește cantitatea"
        item.priceBani == null -> "Lipsește prețul"
        else -> "$quantity × ${InventoryMath.formatLei(item.priceBani)}"
    }
}

/** A blank or unreadable field means "not known yet", never zero. Prices are stored in bani. */
internal fun applyInventoryEdit(
    item: InventoryItemEntity,
    name: String,
    quantityText: String,
    priceText: String,
): InventoryItemEntity = item.copy(
    name = name.trim().ifBlank { item.name },
    quantity = quantityText.trim().replace(',', '.').toDoubleOrNull(),
    priceBani = priceText.trim().replace(',', '.').toDoubleOrNull()?.let { Math.round(it * 100) },
)

@Composable
internal fun InventoryContent(
    items: List<InventoryItemEntity>,
    onEdit: (InventoryItemEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        AppEmptyState(
            icon = Icons.Rounded.Mic,
            title = "Inventarul e gol",
            message = "Apasă microfonul și spune:\n„produs, cantitate, preț”",
            modifier = modifier,
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
        ) {
            items(items, key = { it.id }) { item ->
                InventoryRow(
                    item = item,
                    onClick = { onEdit(item) },
                    modifier = Modifier
                        .animateItem()
                        .padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun InventoryRow(item: InventoryItemEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val value = InventoryMath.lineValueBani(item.quantity, item.priceBani)
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface,
        contentColor = scheme.onSurface,
        interactionSource = source,
        modifier = modifier
            .pressScale(source, pressedScale = 0.98f)
            .fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    inventoryDetail(item),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (value == null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (value == null) scheme.onErrorContainer else scheme.onSurfaceVariant,
                )
            }
            Text(
                value?.let { InventoryMath.formatLei(it) } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun InventoryFooter(
    state: InventoryUiState,
    onMic: () -> Unit,
    onExport: () -> Unit,
    onContinuousChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val listening = state.speechState is SpeechState.Listening || state.speechState is SpeechState.Partial
    Column {
        HorizontalDivider(color = scheme.outline)
        Surface(color = scheme.background) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            ) {
                AnimatedVisibility(visible = listening) {
                    Surface(color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer, shape = RoundedCornerShape(16.dp)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                state.partialText ?: "Te ascult…",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            state.draft?.let { draft ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    listOf(
                                        draft.nameText.ifBlank { "…" },
                                        draft.quantity?.let { InventoryMath.formatQuantity(it) } ?: "…",
                                        draft.priceBani?.let { InventoryMath.formatLei(it) } ?: "…",
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Total", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        Text(
                            InventoryMath.formatLei(state.totalBani),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    ExportButton(state, onExport)
                    InventoryMicButton(state.speechState is SpeechState.Listening, onMic)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Dictare continuă", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Rândurile se separă singure",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.continuous, onCheckedChange = onContinuousChange)
                }
            }
        }
    }
}

@Composable
private fun ExportButton(state: InventoryUiState, onExport: () -> Unit) {
    Box(Modifier.width(126.dp)) {
        AppSecondaryButton(
            text = "Export PDF",
            onClick = onExport,
            enabled = state.items.isNotEmpty(),
            loading = state.isGeneratingPdf,
        )
    }
}

@Composable
private fun InventoryMicButton(listening: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (listening) 20.dp else 30.dp, tween(Motion.ShortMillis), label = "micCorner")
    val container by animateColorAsState(
        if (listening) scheme.error else scheme.primary,
        tween(Motion.ShortMillis),
        label = "micColor",
    )
    val description = if (listening) "Oprește microfonul" else "Pornește microfonul"
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = container,
        contentColor = if (listening) scheme.onError else scheme.onPrimary,
        shadowElevation = 4.dp,
        interactionSource = source,
        modifier = Modifier
            .pressScale(source, pressedScale = 0.94f)
            .size(60.dp)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
internal fun InventoryEditContent(
    item: InventoryItemEntity,
    onSave: (InventoryItemEntity) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(item.name) }
    var quantityText by remember { mutableStateOf(InventoryMath.formatQuantity(item.quantity)) }
    var priceText by remember {
        mutableStateOf(item.priceBani?.let { b -> "${b / 100},${(b % 100).toString().padStart(2, '0')}" } ?: "")
    }
    val shape = RoundedCornerShape(16.dp)
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    SheetBody {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Modifică rândul", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Produs") },
                singleLine = true,
                shape = shape,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text("Cantitate") },
                    singleLine = true,
                    keyboardOptions = decimal,
                    shape = shape,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("Preț (lei)") },
                    placeholder = { Text("ex. 4,50") },
                    singleLine = true,
                    keyboardOptions = decimal,
                    shape = shape,
                    modifier = Modifier.weight(1f),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppPrimaryButton("Salvează", { onSave(applyInventoryEdit(item, name, quantityText, priceText)) })
                AppDangerSoftButton("Șterge rândul", onDelete)
                AppSecondaryButton("Anulează", onDismiss)
            }
        }
    }
}
