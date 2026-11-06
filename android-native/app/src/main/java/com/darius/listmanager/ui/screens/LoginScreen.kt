package com.darius.listmanager.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.data.websocket.WebSocketService
import com.darius.listmanager.sync.SyncService
import com.darius.listmanager.ui.components.AppPrimaryButton
import com.darius.listmanager.ui.components.AppTextButton
import com.darius.listmanager.ui.components.AppTextField
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.BannerTone
import com.darius.listmanager.ui.components.StatusBanner
import com.darius.listmanager.ui.theme.AppTextStyles
import com.darius.listmanager.ui.viewmodel.AuthViewModel

internal fun isAuthSubmitEnabled(
    isRegisterMode: Boolean,
    username: String,
    email: String,
    password: String,
    confirmPassword: String
): Boolean = username.isNotBlank() && password.isNotBlank() &&
    (!isRegisterMode || (
        email.isNotBlank() &&
            confirmPassword.isNotBlank() &&
            password == confirmPassword
        ))

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: AuthViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var hasNavigated by remember { mutableStateOf(false) }
    val webSocketService = remember { WebSocketService.getInstance() }
    val syncService = remember { SyncService(context) }

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
            if (onBack != null) AppTopBar(title = "", onBack = onBack)
        },
    ) { padding ->
        LoginContent(
            isLoading = uiState.isLoading,
            error = uiState.error,
            registrationSuccess = uiState.registrationSuccess,
            onLogin = viewModel::login,
            onRegister = viewModel::register,
            onClearError = viewModel::clearError,
            onContinueOffline = onLoginSuccess,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun LoginContent(
    isLoading: Boolean,
    error: String?,
    registrationSuccess: Boolean,
    onLogin: (username: String, password: String) -> Unit,
    onRegister: (username: String, password: String, email: String) -> Unit,
    onClearError: () -> Unit,
    onContinueOffline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val scheme = MaterialTheme.colorScheme

    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isRegisterMode by remember { mutableStateOf(false) }

    val passwordsMismatch = isRegisterMode && confirmPassword.isNotEmpty() && password != confirmPassword

    // A new account signs in next, so the form goes back to asking for just that.
    LaunchedEffect(registrationSuccess) {
        if (registrationSuccess) {
            isRegisterMode = false
            confirmPassword = ""
        }
    }

    fun submit() {
        focusManager.clearFocus()
        if (isRegisterMode) {
            if (password == confirmPassword) onRegister(username, password, email)
        } else {
            onLogin(username, password)
        }
    }

    val enter = fadeIn() + expandVertically()
    val exit = fadeOut() + shrinkVertically()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(48.dp))

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = scheme.primary,
            contentColor = scheme.onPrimary,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("ListManager", style = AppTextStyles.BigMessage, color = scheme.onBackground)
        Text(
            "Listele tale, împreună.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(28.dp))

        AnimatedVisibility(visible = error != null, enter = enter, exit = exit) {
            StatusBanner(error.orEmpty(), tone = BannerTone.Error, modifier = Modifier.padding(bottom = 14.dp))
        }
        AnimatedVisibility(visible = registrationSuccess, enter = enter, exit = exit) {
            StatusBanner(
                "Cont creat. Te poți autentifica.",
                tone = BannerTone.Success,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }

        AppTextField(
            value = username,
            onValueChange = { username = it },
            label = "Utilizator",
            enabled = !isLoading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        )
        Spacer(Modifier.height(14.dp))

        // Email is required only when creating an account. Login uses username + password.
        AnimatedVisibility(visible = isRegisterMode, enter = enter, exit = exit) {
            Column {
                AppTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = "Email",
                    enabled = !isLoading,
                    keyboardType = KeyboardType.Email,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                )
                Spacer(Modifier.height(14.dp))
            }
        }

        AppTextField(
            value = password,
            onValueChange = { password = it },
            label = "Parolă",
            enabled = !isLoading,
            isPassword = true,
            keyboardType = KeyboardType.Password,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (isRegisterMode) ImeAction.Next else ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onNext = { if (isRegisterMode) focusManager.moveFocus(FocusDirection.Down) },
                onDone = { if (!isRegisterMode) submit() },
            ),
        )

        AnimatedVisibility(visible = isRegisterMode, enter = enter, exit = exit) {
            Column {
                Spacer(Modifier.height(14.dp))
                AppTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = "Confirmă parola",
                    enabled = !isLoading,
                    isPassword = true,
                    keyboardType = KeyboardType.Password,
                    isError = passwordsMismatch,
                    errorText = if (passwordsMismatch) "Parolele nu coincid." else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        AppPrimaryButton(
            text = if (isRegisterMode) "Creează cont" else "Intră în cont",
            onClick = { submit() },
            loading = isLoading,
            enabled = isAuthSubmitEnabled(isRegisterMode, username, email, password, confirmPassword),
        )

        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            AppTextButton(
                if (isRegisterMode) "Ai deja cont? Intră" else "Nu ai cont? Creează unul",
                {
                    isRegisterMode = !isRegisterMode
                    confirmPassword = ""
                    onClearError()
                },
            )
        }

        Spacer(Modifier.weight(1f, fill = false))
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            AppTextButton("Continuă fără cont", onContinueOffline, color = scheme.onSurfaceVariant)
        }
        Text(
            "Fără cont, listele rămân doar pe acest telefon.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Normal,
            color = scheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
