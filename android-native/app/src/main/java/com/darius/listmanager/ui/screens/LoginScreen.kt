package com.darius.listmanager.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.data.websocket.WebSocketService
import com.darius.listmanager.sync.SyncService
import com.darius.listmanager.ui.viewmodel.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: AuthViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isRegisterMode by remember { mutableStateOf(false) }
    
    var hasNavigated by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    
    val webSocketService = remember { WebSocketService.getInstance() }
    val syncService = remember { SyncService(context) }
    
    // Navigate on successful login and connect WebSocket
    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn && !hasNavigated) {
            hasNavigated = true
            
            // Connect WebSocket with new token
            val token = syncService.getAuthToken()
            if (token != null) {
                android.util.Log.d("LoginScreen", "Connecting WebSocket after login")
                webSocketService.connect(token)
            }
            
            onLoginSuccess()
        }
    }
    
    Scaffold(
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { Text("Autentificare") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Înapoi")
                        }
                    }
                )
            }
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
            Spacer(modifier = Modifier.height(24.dp))
            
            // App Icon/Logo
            Icon(
                Icons.Rounded.Inventory,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                "List Manager",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Text(
                if (isRegisterMode) "Creează un cont nou" else "Gestionare produse cu sincronizare",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Username field
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Utilizator") },
                leadingIcon = { Icon(Icons.Rounded.Person, null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                enabled = !uiState.isLoading
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Password field
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Parolă") },
                leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Rounded.VisibilityOff 
                            else Icons.Rounded.Visibility,
                            contentDescription = null
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None 
                                       else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (isRegisterMode) ImeAction.Next else ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onNext = { if (isRegisterMode) focusManager.moveFocus(FocusDirection.Down) },
                    onDone = {
                        if (!isRegisterMode) {
                            focusManager.clearFocus()
                            viewModel.login(username, password)
                        }
                    }
                ),
                enabled = !uiState.isLoading
            )
            
            // Confirm password field (only in register mode)
            AnimatedVisibility(
                visible = isRegisterMode,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirmă parola") },
                        leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                        visualTransformation = if (passwordVisible) VisualTransformation.None 
                                               else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = confirmPassword.isNotEmpty() && password != confirmPassword,
                        supportingText = if (confirmPassword.isNotEmpty() && password != confirmPassword) {
                            { Text("Parolele nu coincid") }
                        } else null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (password == confirmPassword) {
                                    viewModel.register(username, password)
                                }
                            }
                        ),
                        enabled = !uiState.isLoading
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Error message
            AnimatedVisibility(
                visible = uiState.error != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            uiState.error ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            // Success message for registration
            AnimatedVisibility(
                visible = uiState.registrationSuccess,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Cont creat cu succes! Te poți autentifica.",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Main action button (Login or Register)
            Button(
                onClick = { 
                    if (isRegisterMode) {
                        if (password == confirmPassword) {
                            viewModel.register(username, password)
                        }
                    } else {
                        viewModel.login(username, password) 
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = !uiState.isLoading && username.isNotBlank() && password.isNotBlank() &&
                         (!isRegisterMode || password == confirmPassword)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        if (isRegisterMode) Icons.Rounded.PersonAdd else Icons.AutoMirrored.Rounded.Login, 
                        null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (isRegisterMode) "Înregistrare" else "Autentificare")
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Toggle between Login and Register
            TextButton(
                onClick = { 
                    isRegisterMode = !isRegisterMode
                    confirmPassword = ""
                    viewModel.clearError()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (isRegisterMode) "Ai deja cont? Autentifică-te" 
                    else "Nu ai cont? Înregistrează-te",
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            // Divider with "OR"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    "  sau  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Continue Offline button
            TextButton(
                onClick = onLoginSuccess,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Rounded.CloudOff, 
                    null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Continuă Offline")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Info text
            Text(
                "Credențiale demo:\nadmin / admin123  •  user / user123",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
