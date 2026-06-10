package com.darius.listmanager.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.data.websocket.WebSocketService
import com.darius.listmanager.sync.SyncService
import com.darius.listmanager.ui.components.AppPrimaryButton
import com.darius.listmanager.ui.components.AppTextField
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
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isRegisterMode by remember { mutableStateOf(false) }

    var hasNavigated by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    val webSocketService = remember { WebSocketService.getInstance() }
    val syncService = remember { SyncService(context) }

    val passwordsMismatch = isRegisterMode &&
        confirmPassword.isNotEmpty() && password != confirmPassword

    fun submit() {
        focusManager.clearFocus()
        if (isRegisterMode) {
            if (password == confirmPassword) {
                viewModel.register(username, password, email)
            }
        } else {
            viewModel.login(username, password)
        }
    }

    // Navigate on successful login and connect WebSocket
    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn && !hasNavigated) {
            hasNavigated = true
            val token = syncService.getAuthToken()
            if (token != null) {
                android.util.Log.d("LoginScreen", "Connecting WebSocket after login")
                webSocketService.connect(token)
            }
            onLoginSuccess()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Înapoi")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            // Wordmark + tagline
            Text(
                "ListManager",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "manage your lists, together",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Username
            AppTextField(
                value = username,
                onValueChange = { username = it },
                label = "Username",
                enabled = !uiState.isLoading,
                keyboardType = KeyboardType.Text,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Email (both login and register mode)
            AppTextField(
                value = email,
                onValueChange = { email = it },
                label = "Email",
                enabled = !uiState.isLoading,
                keyboardType = KeyboardType.Email,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Password
            AppTextField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                enabled = !uiState.isLoading,
                isPassword = true,
                keyboardType = KeyboardType.Password,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (isRegisterMode) ImeAction.Next else ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onNext = { if (isRegisterMode) focusManager.moveFocus(FocusDirection.Down) },
                    onDone = { if (!isRegisterMode) submit() }
                )
            )

            // Confirm password (register only)
            AnimatedVisibility(
                visible = isRegisterMode,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(18.dp))
                    AppTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = "Confirm password",
                        enabled = !uiState.isLoading,
                        isPassword = true,
                        keyboardType = KeyboardType.Password,
                        isError = passwordsMismatch,
                        errorText = if (passwordsMismatch) "Parolele nu coincid" else null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { submit() }
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Error message
            AnimatedVisibility(
                visible = uiState.error != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer
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

            // Registration success
            AnimatedVisibility(
                visible = uiState.registrationSuccess,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
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

            Spacer(modifier = Modifier.height(24.dp))

            // Primary action
            AppPrimaryButton(
                text = if (isRegisterMode) "Create account" else "Sign in",
                onClick = { submit() },
                loading = uiState.isLoading,
                enabled = username.isNotBlank() && password.isNotBlank() &&
                    email.isNotBlank() &&
                    (!isRegisterMode || (confirmPassword.isNotBlank() && password == confirmPassword))
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Toggle login/register
            TextButton(
                onClick = {
                    isRegisterMode = !isRegisterMode
                    confirmPassword = ""
                    viewModel.clearError()
                }
            ) {
                Text(
                    if (isRegisterMode) "Already have an account? Sign in"
                    else "New here? Create account",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // De-emphasized offline link
            TextButton(onClick = onLoginSuccess) {
                Text(
                    "Continue offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
