package com.darius.listmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.ui.components.AppEmptyState
import com.darius.listmanager.ui.components.AppIconButton
import com.darius.listmanager.ui.components.AppPrimaryButton
import com.darius.listmanager.ui.components.AppSecondaryButton
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.BannerTone
import com.darius.listmanager.ui.components.ConfirmSheet
import com.darius.listmanager.ui.components.SkeletonRows
import com.darius.listmanager.ui.components.StatusBanner
import com.darius.listmanager.ui.viewmodel.EditProductUiState
import com.darius.listmanager.ui.viewmodel.EditProductViewModel

@Composable
fun EditProductScreen(
    productId: Long,
    onBack: () -> Unit,
    viewModel: EditProductViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(productId) {
        viewModel.loadProduct(productId)
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.operationSuccess) {
        if (uiState.operationSuccess) onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = "Editează produsul",
                onBack = onBack,
                actions = {
                    if (uiState.isAdmin) {
                        AppIconButton(
                            Icons.Rounded.Delete,
                            "Șterge produsul",
                            { showDeleteSheet = true },
                            enabled = !uiState.isLoading && !uiState.isSaving,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (uiState.isAdmin && !uiState.isLoading && uiState.error == null) {
                EditProductFooter(uiState, viewModel::saveProduct)
            }
        },
    ) { padding ->
        EditProductContent(
            state = uiState,
            onNameChange = viewModel::updateProductName,
            onAliasesChange = viewModel::updateAliases,
            onRetry = { viewModel.loadProduct(productId) },
            modifier = Modifier.padding(padding),
        )
    }

    if (showDeleteSheet) {
        ConfirmSheet(
            title = "Ștergi produsul?",
            message = "„${uiState.productName}” va dispărea din catalog. Această acțiune nu poate fi anulată.",
            confirmLabel = "Șterge",
            onConfirm = {
                viewModel.deleteProduct()
                showDeleteSheet = false
            },
            onDismiss = { showDeleteSheet = false },
        )
    }
}

@Composable
internal fun EditProductContent(
    state: EditProductUiState,
    onNameChange: (String) -> Unit,
    onAliasesChange: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> SkeletonRows(
            count = 3,
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
        )
        state.error != null -> AppEmptyState(
            icon = Icons.Rounded.ErrorOutline,
            title = state.error,
            modifier = modifier,
            action = { AppSecondaryButton("Încearcă din nou", onRetry, Modifier.width(220.dp)) },
        )
        else -> Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            val shape = RoundedCornerShape(16.dp)
            val editable = state.isAdmin && !state.isSaving
            if (!state.isAdmin) {
                StatusBanner("Doar administratorii pot modifica catalogul.")
            }
            OutlinedTextField(
                value = state.productName,
                onValueChange = onNameChange,
                label = { Text("Nume produs *") },
                isError = state.productName.isBlank(),
                supportingText = if (state.productName.isBlank()) {
                    { Text("Scrie numele produsului.") }
                } else null,
                singleLine = true,
                enabled = editable,
                shape = shape,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.distributorName,
                onValueChange = {},
                label = { Text("Distribuitor") },
                singleLine = true,
                enabled = false,
                shape = shape,
                // Can't be changed here, but it still has to be easy to read.
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.aliases,
                onValueChange = onAliasesChange,
                label = { Text("Sinonime (separate prin virgulă)") },
                placeholder = { Text("ex. lapte, lapte proaspăt") },
                supportingText = { Text("Opțional. Ajută recunoașterea vocală.") },
                enabled = editable,
                shape = shape,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.isAdmin && state.hasChanges && !state.isSaving) {
                StatusBanner("Ai modificări nesalvate.", tone = BannerTone.Info)
            }
        }
    }
}

@Composable
internal fun EditProductFooter(state: EditProductUiState, onSave: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = scheme.outline)
        Surface(color = scheme.background) {
            AppPrimaryButton(
                text = "Salvează modificările",
                onClick = onSave,
                enabled = state.hasChanges && state.productName.isNotBlank(),
                loading = state.isSaving,
                loadingText = "Se salvează…",
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
