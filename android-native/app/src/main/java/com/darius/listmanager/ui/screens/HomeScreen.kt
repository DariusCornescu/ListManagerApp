package com.darius.listmanager.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.ui.components.PrimaryButton
import com.darius.listmanager.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.BadgedBox

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

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        android.util.Log.d("HomeScreen", "Permission result: $isGranted")
        if (isGranted) {
            android.util.Log.d("HomeScreen", "Permission granted - starting listening")
            viewModel.startListening()
        } else {
            android.util.Log.d("HomeScreen", "Permission denied")
            scope.launch {
                snackbarHostState.showSnackbar("Microphone permission required")
            }
        }
    }
    
    // Helper to check and start recording
    fun checkPermissionAndStart() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        
        android.util.Log.d("HomeScreen", "Checking permission - hasPermission: $hasPermission")
        
        if (hasPermission) {
            android.util.Log.d("HomeScreen", "Permission already granted - starting directly")
            viewModel.startListening()
        } else {
            android.util.Log.d("HomeScreen", "Permission not granted - requesting")
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
                    // Account/Login button - right side
                    IconButton(onClick = onNavigateToAccount) {
                        if (isLoggedIn && username != null) {
                            // Show avatar with first letter when logged in
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
                            // Show person icon when not logged in
                            Icon(
                                Icons.Rounded.AccountCircle, 
                                "Autentificare",
                                modifier = Modifier.size(28.dp)
                            )
                        }
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
                // Mic Icon Area with Processing Overlay
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(120.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = when (uiState.speechState) {
                            is SpeechState.Listening -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.fillMaxSize(),
                        onClick = {
                            android.util.Log.d("HomeScreen", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                            android.util.Log.d("HomeScreen", "🎤 Mic button clicked")
                            android.util.Log.d("HomeScreen", "Current state: ${uiState.speechState}")
                            
                            when (uiState.speechState) {
                                is SpeechState.Idle -> {
                                    android.util.Log.d("HomeScreen", "State is Idle - checking permission and starting...")
                                    checkPermissionAndStart()
                                }
                                is SpeechState.Listening -> {
                                    android.util.Log.d("HomeScreen", "State is Listening - stopping...")
                                    viewModel.stopListening()
                                }
                                is SpeechState.Partial, is SpeechState.Final -> {
                                    android.util.Log.d("HomeScreen", "State is ${uiState.speechState.javaClass.simpleName} - ignoring click")
                                }
                                is SpeechState.Error -> {
                                    android.util.Log.d("HomeScreen", "State is Error - resetting and trying again")
                                    checkPermissionAndStart()
                                }
                            }
                            android.util.Log.d("HomeScreen", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (uiState.isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(64.dp),
                                    strokeWidth = 3.dp
                                )
                            } else {
                                Icon(
                                    if (uiState.speechState is SpeechState.Listening)
                                        Icons.Rounded.Stop
                                    else
                                        Icons.Rounded.Mic,
                                    contentDescription = "Microphone",
                                    modifier = Modifier.size(64.dp),
                                    tint = when (uiState.speechState) {
                                        is SpeechState.Listening -> MaterialTheme.colorScheme.onPrimaryContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }

                    // Pulsing ring when processing
                    if (uiState.isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(100.dp)
                                .padding(8.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                    }
                }

                Text(
                    when (val state = uiState.speechState) {
                        is SpeechState.Idle -> "Apasă pentru a incepe inregistrarea"
                        is SpeechState.Listening -> "Ascultare..."
                        is SpeechState.Partial -> "Am auzit: ${state.text}"
                        is SpeechState.Final -> "Procesez: ${state.text}"
                        is SpeechState.Error -> "Eroare: ${state.message}"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (uiState.speechState is SpeechState.Listening || uiState.sessionAddedCount > 0) {
                    Text(
                        "Adăugate în sesiune: ${uiState.sessionAddedCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Processing indicator with message
                if (uiState.isProcessing) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Processing your request...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

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

                // Suggestions Section
                if (uiState.suggestions.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Ai vrut sa zici?",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = { viewModel.clearSuggestions() },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        "Clear suggestions",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            HorizontalDivider()

                            uiState.suggestions.forEach { rankedProduct ->
                                SuggestionItem(
                                    productName = rankedProduct.product.name,
                                    score = rankedProduct.score,
                                    onClick = {
                                        viewModel.addSuggestedProduct(
                                            rankedProduct.product.id,
                                            rankedProduct.product.name
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                PrimaryButton(
                    text = "Mergi spre sesiunea curentă",
                    icon = Icons.Rounded.ShoppingCart,
                    onClick = onNavigateToSession
                )

                Spacer(Modifier.weight(1f))

                if (uiState.reviewCount > 0) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(32.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        onClick = onNavigateToReview
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "De verificat",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    ) {
                                        Text(
                                            "${uiState.reviewCount}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Apasă pentru a confirma ${uiState.reviewCount} produs(e) ambigue",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            Icon(
                                Icons.Rounded.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(32.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    onClick = onNavigateToUnknown
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Produse necunoscute",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (uiState.unknownProductCount > 0) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        ) {
                                            Text(
                                                "${uiState.unknownProductCount}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (uiState.unknownProductCount > 0)
                                        "Apasa pentru a adauga ${uiState.unknownProductCount} produsul${if (uiState.unknownProductCount != 1) "s" else ""} in catalog"
                                    else
                                        "Niciun produs necunoscut in sesiunea curenta",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.Rounded.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== UTILITY FUNCTIONS ====================

@Composable
private fun SuggestionItem(
    productName: String,
    score: Double,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = productName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Badge(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Text(
                    text = "$score%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun formatSyncTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "acum"
        diff < 3600_000 -> "${diff / 60_000} min"
        diff < 86400_000 -> "${diff / 3600_000} ore"
        else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timestamp))
    }
}