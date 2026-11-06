package com.darius.listmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.data.websocket.WebSocketService
import com.darius.listmanager.ui.viewmodel.AuthViewModel
import com.darius.listmanager.ui.viewmodel.ProfileInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    username: String?,
    viewModel: AuthViewModel = viewModel()
) {
    val scrollState = rememberScrollState()
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val webSocketService = remember { WebSocketService.getInstance() }
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Load the real account (username/email/role) from the server
    LaunchedEffect(Unit) {
        viewModel.loadCurrentUser()
    }

    // Surface profile update success/error
    LaunchedEffect(uiState.profileMessage) {
        uiState.profileMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearProfileMessage()
        }
    }

    val displayUsername = uiState.username ?: username

    // Logout confirmation dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
            title = { Text("Deconectare") },
            text = { Text("Ești sigur că vrei să te deconectezi?") },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        // Disconnect WebSocket first
                        webSocketService.disconnect()
                        android.util.Log.d("AccountScreen", "WebSocket disconnected on logout")
                        // Then logout
                        viewModel.logout()
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Deconectează-mă")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Anulează")
                }
            }
        )
    }
    
    // Edit profile dialog
    if (showEditDialog) {
        EditProfileDialog(
            current = ProfileInput(
                email = uiState.email ?: "",
                fullName = uiState.fullName ?: "",
                company = uiState.company ?: "",
                address = uiState.address ?: "",
            ),
            isSaving = uiState.isSavingProfile,
            onDismiss = { showEditDialog = false },
            onConfirm = { input, newPassword ->
                viewModel.updateProfile(input, newPassword)
                showEditDialog = false
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Contul meu") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Înapoi")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar
            Surface(
                modifier = Modifier.size(100.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = (displayUsername?.firstOrNull() ?: 'U').uppercase().toString(),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Username
            Text(
                text = displayUsername ?: "Utilizator",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            // Role (real value from server)
            Text(
                text = when (uiState.role) {
                    "ADMIN" -> "Administrator"
                    "USER" -> "Utilizator"
                    else -> "—"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Account info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Informații cont",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    HorizontalDivider()
                    
                    AccountInfoRow(
                        icon = Icons.Rounded.Person,
                        label = "Utilizator",
                        value = displayUsername ?: "-"
                    )

                    AccountInfoRow(
                        icon = Icons.Rounded.Email,
                        label = "Email",
                        value = uiState.email ?: "-"
                    )

                    AccountInfoRow(
                        icon = Icons.Rounded.AccountCircle,
                        label = "Nume complet",
                        value = uiState.fullName ?: "-"
                    )

                    AccountInfoRow(
                        icon = Icons.Rounded.Business,
                        label = "Companie",
                        value = uiState.company ?: "-"
                    )

                    AccountInfoRow(
                        icon = Icons.Rounded.Place,
                        label = "Adresă",
                        value = uiState.address ?: "-"
                    )

                    AccountInfoRow(
                        icon = Icons.Rounded.Badge,
                        label = "Rol",
                        value = when (uiState.role) {
                            "ADMIN" -> "Administrator"
                            "USER" -> "Utilizator"
                            else -> "—"
                        },
                        valueColor = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Edit profile button
            OutlinedButton(
                onClick = { showEditDialog = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.isLoggedIn || uiState.username != null
            ) {
                Icon(Icons.Rounded.Edit, null)
                Spacer(Modifier.width(8.dp))
                Text("Editează profilul")
            }

            Spacer(modifier = Modifier.weight(1f))

            // Logout button
            Button(
                onClick = { showLogoutDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, null)
                Spacer(Modifier.width(8.dp))
                Text("Deconectare")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label)
        }
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileDialog(
    current: ProfileInput,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (input: ProfileInput, newPassword: String) -> Unit
) {
    var fullName by remember { mutableStateOf(current.fullName) }
    var company by remember { mutableStateOf(current.company) }
    var address by remember { mutableStateOf(current.address) }
    var email by remember { mutableStateOf(current.email) }
    var newPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        title = { Text("Editează profilul") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it.take(100) },
                    label = { Text("Nume complet") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )
                OutlinedTextField(
                    value = company,
                    onValueChange = { company = it.take(100) },
                    label = { Text("Companie") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.take(255) },
                    label = { Text("Adresă") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("Parolă nouă (opțional)") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Rounded.VisibilityOff
                                else Icons.Rounded.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )
                Text(
                    "Lasă parola goală pentru a o păstra pe cea actuală.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(ProfileInput(email, fullName, company, address), newPassword) },
                enabled = !isSaving && email.isNotBlank()
            ) {
                Text("Salvează")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    )
}
