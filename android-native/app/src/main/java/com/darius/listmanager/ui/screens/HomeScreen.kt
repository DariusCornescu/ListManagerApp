package com.darius.listmanager.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.darius.listmanager.ui.components.SaveStateChip
import com.darius.listmanager.ui.viewmodel.HomeUiState
import com.darius.listmanager.ui.viewmodel.HomeViewModel
import com.darius.listmanager.util.RankedProduct
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToSession: () -> Unit,
    onNavigateToUnknown: () -> Unit,
    onNavigateToReview: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAccount: () -> Unit,
    isLoggedIn: Boolean = false,
    username: String? = null,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startListening()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Microphone permission required")
            }
        }
    }

    fun checkPermissionAndStart() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            viewModel.startListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Show message as snackbar when it changes
    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("List Manager") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Rounded.Menu, "Menu")
                    }
                },
                actions = {
                    SaveStateChip()
                    IconButton(onClick = onNavigateToAccount) {
                        HomeAccountIcon(isLoggedIn, username)
                    }
                },
            )
        }
    ) { padding ->
        HomeContent(
            uiState = uiState,
            onStart = ::checkPermissionAndStart,
            onStop = viewModel::stopListening,
            onClearSuggestions = viewModel::clearSuggestions,
            onPickSuggestion = { viewModel.addSuggestedProduct(it.product.id, it.product.name) },
            onOpenSession = onNavigateToSession,
            onOpenReview = onNavigateToReview,
            onOpenUnknown = onNavigateToUnknown,
            modifier = Modifier.padding(padding),
        )
    }
}

/** One big central REC button, with everything else secondary and out of its way. */
@Composable
internal fun HomeContent(
    uiState: HomeUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearSuggestions: () -> Unit,
    onPickSuggestion: (RankedProduct) -> Unit,
    onOpenSession: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenUnknown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // Scrolls only when suggestions outgrow the space; otherwise REC stays centred.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                RecordButton(
                    action = recordActionFor(uiState.speechState, uiState.isProcessing),
                    onStart = onStart,
                    onStop = onStop,
                )

                HomeSpeechStatus(uiState.speechState, uiState.sessionAddedCount)

                if (uiState.suggestions.isNotEmpty()) {
                    HomeSuggestionsCard(
                        suggestions = uiState.suggestions,
                        onClear = onClearSuggestions,
                        onPick = onPickSuggestion,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = onOpenSession, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.ShoppingCart, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Mergi spre sesiunea curentă")
        }

        Spacer(Modifier.height(12.dp))

        HomeAttentionRow(
            reviewCount = uiState.reviewCount,
            unknownCount = uiState.unknownProductCount,
            onOpenReview = onOpenReview,
            onOpenUnknown = onOpenUnknown,
        )
    }
}

/** Avatar with the first letter when logged in, a person icon otherwise. */
@Composable
private fun HomeAccountIcon(isLoggedIn: Boolean, username: String?) {
    if (isLoggedIn && username != null) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = username.first().uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    } else {
        Icon(
            Icons.Rounded.AccountCircle,
            "Autentificare",
            modifier = Modifier.size(28.dp)
        )
    }
}
