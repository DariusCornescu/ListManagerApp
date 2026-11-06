package com.darius.listmanager.ui.screens

import android.content.Intent
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.data.local.dao.SessionItemWithProduct
import com.darius.listmanager.ui.components.AppEmptyState
import com.darius.listmanager.ui.components.AppIconButton
import com.darius.listmanager.ui.components.AppPrimaryButton
import com.darius.listmanager.ui.components.AppSecondaryButton
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.ConfirmSheet
import com.darius.listmanager.ui.components.SaveStateChip
import com.darius.listmanager.ui.components.SessionItemRow
import com.darius.listmanager.ui.components.SkeletonRows
import com.darius.listmanager.ui.theme.Motion
import com.darius.listmanager.ui.viewmodel.SessionUiState
import com.darius.listmanager.ui.viewmodel.SessionViewModel
import kotlinx.coroutines.launch

@Composable
fun SessionScreen(
    onBack: () -> Unit,
    viewModel: SessionViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showClearSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun openShareChooser() {
        viewModel.createShareIntent()?.let {
            context.startActivity(Intent.createChooser(it, "Distribuie PDF-urile comenzii"))
        }
    }

    // Opens once per generation. Turning the phone must not open it again.
    LaunchedEffect(uiState.shareChooserPending) {
        if (uiState.shareChooserPending) {
            openShareChooser()
            viewModel.onShareChooserOpened()
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    fun deleteWithUndo(item: SessionItemWithProduct) {
        viewModel.deleteItem(item)
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = "Șters: ${item.productName}",
                actionLabel = "Anulează",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(item)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = "Lista curentă",
                subtitle = sessionSubtitle(uiState),
                onBack = onBack,
                actions = {
                    SaveStateChip()
                    if (uiState.items.isNotEmpty()) {
                        AppIconButton(Icons.Rounded.Delete, "Golește lista", { showClearSheet = true })
                    }
                },
            )
        },
        bottomBar = {
            if (uiState.items.isNotEmpty()) {
                SessionFooter(uiState.isGeneratingPdfs, viewModel::generatePdfs)
            }
        },
    ) { padding ->
        SessionContent(
            state = uiState,
            onQuantityChange = { item, quantity -> viewModel.updateQuantity(item.productId, quantity) },
            onDelete = ::deleteWithUndo,
            onRetry = viewModel::refresh,
            onRecord = onBack,
            modifier = Modifier.padding(padding),
        )
    }

    uiState.generatedPdfs?.let { pdfs ->
        // Above the Scaffold, so the emptied list doesn't show behind it.
        PdfsReadyContent(
            count = pdfs.size,
            onShare = ::openShareChooser,
            onDone = viewModel::clearGeneratedPdfs,
        )
    }

    if (showClearSheet) {
        ConfirmSheet(
            title = "Golești lista?",
            message = "Toate produsele vor fi șterse din lista curentă.",
            confirmLabel = "Golește lista",
            onConfirm = {
                viewModel.clearSession()
                showClearSheet = false
            },
            onDismiss = { showClearSheet = false },
        )
    }
}

internal fun sessionSubtitle(state: SessionUiState): String {
    val count = when (val size = state.items.size) {
        1 -> "1 produs"
        else -> "$size produse"
    }
    return if (state.isLoading) state.workspaceName else "${state.workspaceName} · $count"
}

@Composable
internal fun SessionContent(
    state: SessionUiState,
    onQuantityChange: (SessionItemWithProduct, Int) -> Unit,
    onDelete: (SessionItemWithProduct) -> Unit,
    onRetry: () -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> SkeletonRows(
            count = 6,
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        state.error != null -> AppEmptyState(
            icon = Icons.Rounded.CloudOff,
            title = "Nu am putut încărca lista",
            message = "Verifică conexiunea și încearcă din nou.",
            modifier = modifier,
            action = { AppSecondaryButton("Încearcă din nou", onRetry, Modifier.width(220.dp)) },
        )
        state.items.isEmpty() -> AppEmptyState(
            icon = Icons.Rounded.ShoppingCart,
            title = "Lista e goală",
            message = "Spune ce îți trebuie și produsele apar aici.",
            modifier = modifier,
            action = { AppSecondaryButton("Înregistrează", onRecord, Modifier.width(220.dp)) },
        )
        else -> {
            val groups = state.items.groupBy { it.distributorId }.values.toList()
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 16.dp),
            ) {
                groups.forEach { rows ->
                    item(key = "group-${rows.first().distributorId}", contentType = "group") {
                        DistributorHeading(rows.first().distributorName, rows.size, Modifier.animateItem())
                    }
                    items(rows, key = { it.id }, contentType = { "item" }) { item ->
                        SessionItemRow(
                            item = item,
                            onQuantityChange = { onQuantityChange(item, it) },
                            onDelete = { onDelete(item) },
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = tween(Motion.ShortMillis),
                                    placementSpec = tween(Motion.ScreenMillis, easing = Motion.Standard),
                                    fadeOutSpec = tween(Motion.ShortMillis),
                                )
                                .padding(bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DistributorHeading(name: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 8.dp),
    ) {
        val style = MaterialTheme.typography.labelLarge
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text(name, style = style, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(if (count == 1) "1 produs" else "$count produse", style = style, color = color)
    }
}

@Composable
internal fun SessionFooter(isGenerating: Boolean, onGenerate: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = scheme.outline)
        Surface(color = scheme.background) {
            AppPrimaryButton(
                text = "Generează PDF-uri",
                onClick = onGenerate,
                loading = isGenerating,
                loadingText = "Se generează…",
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
internal fun PdfsReadyContent(count: Int, onShare: () -> Unit, onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(32.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .size(92.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(44.dp))
                }
            }
            Text(
                "PDF-urile sunt gata",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            Text(
                if (count == 1) "Un PDF pentru un distribuitor. Lista poate începe din nou."
                else "Câte un PDF pentru fiecare dintre cei $count distribuitori. Lista poate începe din nou.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 18.dp),
            )
            AppPrimaryButton("Distribuie", onShare, icon = Icons.Rounded.Share)
            AppSecondaryButton("Gata", onDone)
        }
    }
}
