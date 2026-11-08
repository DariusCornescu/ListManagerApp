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
import com.darius.listmanager.ui.components.AppIconButton
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.SaveStateChip
import com.darius.listmanager.ui.viewmodel.HomeUiState
import com.darius.listmanager.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToSession: () -> Unit,
    onNavigateToResolve: () -> Unit,
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
                snackbarHostState.showSnackbar("Am nevoie de acces la microfon ca să te ascult")
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
            AppTopBar(
                title = "ListManager",
                navigationIcon = { AppIconButton(Icons.Rounded.Menu, "Meniu", onOpenDrawer) },
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
            onUndoLastAdded = viewModel::undoLastAdded,
            onOpenSession = onNavigateToSession,
            onOpenResolve = onNavigateToResolve,
            modifier = Modifier.padding(padding),
            onContinuousChange = viewModel::setContinuousDictation,
        )
    }

    uiState.suggestionPrompt?.let { prompt ->
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissSuggestion,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SuggestionSheetContent(
                prompt = prompt,
                onPick = viewModel::pickSuggestion,
                onDismiss = viewModel::dismissSuggestion,
            )
        }
    }
}

/** What's happening goes high, and everything the thumb needs goes low. */
@Composable
internal fun HomeContent(
    uiState: HomeUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUndoLastAdded: () -> Unit,
    onOpenSession: () -> Unit,
    onOpenResolve: () -> Unit,
    modifier: Modifier = Modifier,
    onContinuousChange: (Boolean) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Scrolls only if a long phrase outgrows the space. The dock stays put.
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            HomeStage(uiState.speechState, uiState.isProcessing, uiState.sessionAddedCount)
        }

        LastAddedArea(uiState.lastAdded, uiState.canUndoLastAdded, onUndoLastAdded)

        DictationModeRow(uiState.continuousDictation, onContinuousChange)

        HomeDock(
            action = recordActionFor(uiState.speechState, uiState.isProcessing),
            resolveCount = uiState.reviewCount + uiState.unknownProductCount,
            itemCount = uiState.sessionItemCount,
            onStart = onStart,
            onStop = onStop,
            onOpenResolve = onOpenResolve,
            onOpenList = onOpenSession,
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
