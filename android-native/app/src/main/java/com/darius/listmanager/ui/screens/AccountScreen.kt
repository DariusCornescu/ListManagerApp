package com.darius.listmanager.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.data.websocket.WebSocketService
import com.darius.listmanager.data.workspace.Workspace
import com.darius.listmanager.ui.components.AppDangerSoftButton
import com.darius.listmanager.ui.components.AppIconButton
import com.darius.listmanager.ui.components.AppPrimaryButton
import com.darius.listmanager.ui.components.AppSecondaryButton
import com.darius.listmanager.ui.components.AppTextButton
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.ConfirmSheet
import com.darius.listmanager.ui.components.SheetBody
import com.darius.listmanager.ui.components.pressScale
import com.darius.listmanager.ui.viewmodel.AuthViewModel
import com.darius.listmanager.ui.viewmodel.ProfileInput
import com.darius.listmanager.ui.viewmodel.WorkspaceSwitcherViewModel
import com.darius.listmanager.ui.viewmodel.workspaceOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    username: String?,
    onWorkspaceSwitched: () -> Unit = {},
    onManageTeams: () -> Unit = {},
    viewModel: AuthViewModel = viewModel(),
    workspaceViewModel: WorkspaceSwitcherViewModel = viewModel(),
) {
    var showLogoutSheet by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }
    val webSocketService = remember { WebSocketService.getInstance() }
    val uiState by viewModel.uiState.collectAsState()
    val currentWorkspace by workspaceViewModel.current.collectAsState()
    val teams by workspaceViewModel.teams.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Load the real account (username/email/role) from the server
    LaunchedEffect(Unit) {
        viewModel.loadCurrentUser()
    }

    LaunchedEffect(uiState.profileMessage) {
        uiState.profileMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearProfileMessage()
        }
    }

    val displayUsername = uiState.username ?: username

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { AppTopBar(title = "Contul meu", onBack = onBack) },
        bottomBar = {
            AccountFooter(
                canEdit = uiState.isLoggedIn || uiState.username != null,
                onEdit = { showEditSheet = true },
                onLogout = { showLogoutSheet = true },
            )
        },
    ) { padding ->
        AccountContent(
            username = displayUsername,
            role = uiState.role,
            email = uiState.email,
            fullName = uiState.fullName,
            company = uiState.company,
            address = uiState.address,
            currentWorkspace = currentWorkspace,
            teams = teams,
            onPickWorkspace = { workspace ->
                if (workspace != currentWorkspace) {
                    workspaceViewModel.switchTo(workspace)
                    onWorkspaceSwitched()
                }
            },
            onManageTeams = onManageTeams,
            modifier = Modifier.padding(padding),
        )
    }

    if (showLogoutSheet) {
        ConfirmSheet(
            title = "Te deconectezi?",
            message = "Poți reveni oricând cu utilizatorul și parola ta.",
            confirmLabel = "Deconectează-mă",
            onConfirm = {
                showLogoutSheet = false
                // Disconnect WebSocket first
                webSocketService.disconnect()
                android.util.Log.d("AccountScreen", "WebSocket disconnected on logout")
                viewModel.logout()
                onLogout()
            },
            onDismiss = { showLogoutSheet = false },
        )
    }

    if (showEditSheet) {
        ModalBottomSheet(
            onDismissRequest = { showEditSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            EditProfileContent(
                current = ProfileInput(
                    email = uiState.email ?: "",
                    fullName = uiState.fullName ?: "",
                    company = uiState.company ?: "",
                    address = uiState.address ?: "",
                ),
                isSaving = uiState.isSavingProfile,
                onDismiss = { showEditSheet = false },
                onConfirm = { input, newPassword ->
                    viewModel.updateProfile(input, newPassword)
                    showEditSheet = false
                },
            )
        }
    }
}

internal fun roleLabel(role: String?): String = when (role) {
    "ADMIN" -> "Administrator"
    "USER" -> "Utilizator"
    else -> "—"
}

@Composable
internal fun AccountContent(
    username: String?,
    role: String?,
    email: String?,
    fullName: String?,
    company: String?,
    address: String?,
    currentWorkspace: Workspace,
    teams: List<Workspace.Team>,
    onPickWorkspace: (Workspace) -> Unit,
    onManageTeams: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
                modifier = Modifier.size(68.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        (username?.firstOrNull() ?: 'U').uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            Column {
                Text(
                    username ?: "Utilizator",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(roleLabel(role), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }

        AccountHeading("Spațiu de lucru")
        WorkspaceSection(currentWorkspace, teams, onPickWorkspace, onManageTeams)

        AccountHeading("Date personale")
        Surface(color = scheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                AccountInfoRow("Email", email)
                HorizontalDivider(color = scheme.surfaceVariant)
                AccountInfoRow("Nume complet", fullName)
                HorizontalDivider(color = scheme.surfaceVariant)
                AccountInfoRow("Companie", company)
                HorizontalDivider(color = scheme.surfaceVariant)
                AccountInfoRow("Adresă", address)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AccountHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 8.dp),
    )
}

@Composable
private fun AccountInfoRow(label: String, value: String?) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value?.ifBlank { null } ?: "-",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun WorkspaceSection(
    current: Workspace,
    teams: List<Workspace.Team>,
    onPick: (Workspace) -> Unit,
    onManageTeams: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }

    Surface(
        onClick = { open = !open },
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface,
        contentColor = scheme.onSurface,
        interactionSource = source,
        modifier = Modifier
            .pressScale(source, pressedScale = 0.98f)
            .fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = scheme.surfaceVariant,
                contentColor = scheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Groups, contentDescription = null) }
            }
            Column(Modifier.weight(1f)) {
                Text(current.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (current is Workspace.Personal) "Lista ta" else "Lista comună a echipei",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (open) "Ascunde spațiile de lucru" else "Arată spațiile de lucru",
                tint = scheme.onSurfaceVariant,
            )
        }
    }

    AnimatedVisibility(visible = open) {
        Surface(
            color = scheme.surface,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
        ) {
            Column(Modifier.padding(6.dp)) {
                workspaceOptions(current, teams).forEach { option ->
                    WorkspaceOption(option, option == current) {
                        open = false
                        onPick(option)
                    }
                }
                AppTextButton("Gestionează echipele", onManageTeams, Modifier.padding(horizontal = 2.dp))
            }
        }
    }
}

@Composable
private fun WorkspaceOption(workspace: Workspace, isCurrent: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isCurrent) scheme.primaryContainer else scheme.surface,
        contentColor = if (isCurrent) scheme.onPrimaryContainer else scheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.RadioButton
                selected = isCurrent
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                workspace.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (isCurrent) Icon(Icons.Rounded.Check, contentDescription = null)
        }
    }
}

@Composable
internal fun AccountFooter(canEdit: Boolean, onEdit: () -> Unit, onLogout: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = scheme.outline)
        Surface(color = scheme.background) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                AppSecondaryButton("Editează profilul", onEdit, enabled = canEdit)
                AppDangerSoftButton("Deconectare", onLogout)
            }
        }
    }
}

@Composable
internal fun EditProfileContent(
    current: ProfileInput,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (input: ProfileInput, newPassword: String) -> Unit,
) {
    var fullName by remember { mutableStateOf(current.fullName) }
    var company by remember { mutableStateOf(current.company) }
    var address by remember { mutableStateOf(current.address) }
    var email by remember { mutableStateOf(current.email) }
    var newPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    SheetBody(Modifier.verticalScroll(rememberScrollState())) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Editează profilul", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            OutlinedTextField(
                value = fullName,
                onValueChange = { fullName = it.take(100) },
                label = { Text("Nume complet") },
                singleLine = true,
                shape = shape,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = company,
                onValueChange = { company = it.take(100) },
                label = { Text("Companie") },
                singleLine = true,
                shape = shape,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.take(255) },
                label = { Text("Adresă") },
                shape = shape,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email *") },
                isError = email.isBlank(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                shape = shape,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text("Parolă nouă (opțional)") },
                supportingText = { Text("Las-o goală ca să o păstrezi pe cea actuală.") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    AppIconButton(
                        if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        if (passwordVisible) "Ascunde parola" else "Arată parola",
                        { passwordVisible = !passwordVisible },
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = shape,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppPrimaryButton(
                    text = "Salvează",
                    onClick = { onConfirm(ProfileInput(email, fullName, company, address), newPassword) },
                    enabled = email.isNotBlank(),
                    loading = isSaving,
                )
                AppSecondaryButton("Anulează", onDismiss)
            }
        }
    }
}
