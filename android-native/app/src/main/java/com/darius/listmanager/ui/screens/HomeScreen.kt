package com.darius.listmanager.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.ui.components.PrimaryButton
import com.darius.listmanager.ui.viewmodel.HomeViewModel
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
                    IconButton(onClick = onNavigateToAccount) {
                        HomeAccountIcon(isLoggedIn, username)
                    }
                },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                HomeMicButton(
                    speechState = uiState.speechState,
                    isProcessing = uiState.isProcessing,
                    onStart = ::checkPermissionAndStart,
                    onStop = viewModel::stopListening,
                )

                HomeSpeechStatus(uiState.speechState, uiState.sessionAddedCount)

                if (uiState.isProcessing) HomeProcessingCard()

                // Stop/Cancel button when listening
                if (uiState.speechState is SpeechState.Listening) {
                    Button(
                        onClick = {
                            viewModel.stopListening()
                            viewModel.clearSuggestions()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Anulează inregistrarea")
                    }
                }

                if (uiState.suggestions.isNotEmpty()) {
                    HomeSuggestionsCard(
                        suggestions = uiState.suggestions,
                        onClear = viewModel::clearSuggestions,
                        onPick = { viewModel.addSuggestedProduct(it.product.id, it.product.name) },
                    )
                }

                Spacer(Modifier.height(8.dp))

                PrimaryButton(
                    text = "Mergi spre sesiunea curentă",
                    icon = Icons.Rounded.ShoppingCart,
                    onClick = onNavigateToSession
                )

                Spacer(Modifier.weight(1f))

                HomeAttentionRow(
                    reviewCount = uiState.reviewCount,
                    unknownCount = uiState.unknownProductCount,
                    onOpenReview = onNavigateToReview,
                    onOpenUnknown = onNavigateToUnknown,
                )
            }
        }
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
