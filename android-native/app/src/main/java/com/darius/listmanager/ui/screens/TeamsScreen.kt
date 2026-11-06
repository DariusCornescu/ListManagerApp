package com.darius.listmanager.ui.screens

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.network.TeamDTO
import com.darius.listmanager.ui.components.AppEmptyState
import com.darius.listmanager.ui.components.AppPrimaryButton
import com.darius.listmanager.ui.components.AppSecondaryButton
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.ConfirmSheet
import com.darius.listmanager.ui.components.SheetBody
import com.darius.listmanager.ui.components.SkeletonRows
import com.darius.listmanager.ui.components.pressScale
import com.darius.listmanager.ui.viewmodel.TeamsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamsScreen(
    onBack: () -> Unit,
    onOpenTeam: (Long, String) -> Unit,
    viewModel: TeamsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateSheet by remember { mutableStateOf(false) }
    var showJoinSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Refresh when returning to this screen (e.g. after leaving a team in TeamDetail).
    // The ViewModel guards against stacking with the init{} refresh.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { AppTopBar(title = "Echipe", onBack = onBack) },
        bottomBar = {
            TeamsFooter(
                enabled = !uiState.isSubmitting,
                onCreate = { showCreateSheet = true },
                onJoin = { showJoinSheet = true },
            )
        },
    ) { padding ->
        TeamsContent(
            teams = uiState.teams,
            isLoading = uiState.isLoading,
            loadFailed = uiState.loadFailed,
            onOpenTeam = onOpenTeam,
            onRetry = viewModel::refresh,
            modifier = Modifier.padding(padding),
        )
    }

    // After create/join: offer to switch the app into that team's workspace.
    uiState.justJoinedTeam?.let { team ->
        ConfirmSheet(
            title = "Treci la ${team.name}?",
            message = "Vei lucra pe lista comună a acestei echipe.",
            confirmLabel = "Treci acum",
            dismissLabel = "Nu acum",
            destructive = false,
            onConfirm = { viewModel.switchToTeam(team) },
            onDismiss = { viewModel.consumeJustJoined() },
        )
    }

    if (showCreateSheet) {
        ModalBottomSheet(onDismissRequest = { showCreateSheet = false }, containerColor = MaterialTheme.colorScheme.surface) {
            TeamInputContent(
                title = "Creează echipă",
                label = "Numele echipei",
                confirmLabel = "Creează",
                onConfirm = {
                    viewModel.createTeam(it)
                    showCreateSheet = false
                },
                onDismiss = { showCreateSheet = false },
            )
        }
    }

    if (showJoinSheet) {
        ModalBottomSheet(onDismissRequest = { showJoinSheet = false }, containerColor = MaterialTheme.colorScheme.surface) {
            TeamInputContent(
                title = "Intră cu un cod",
                label = "Codul de invitație",
                confirmLabel = "Intră în echipă",
                onConfirm = {
                    viewModel.joinTeam(it)
                    showJoinSheet = false
                },
                onDismiss = { showJoinSheet = false },
            )
        }
    }
}

@Composable
internal fun TeamsContent(
    teams: List<TeamDTO>,
    isLoading: Boolean,
    loadFailed: Boolean,
    onOpenTeam: (Long, String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isLoading -> SkeletonRows(
            count = 3,
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        loadFailed && teams.isEmpty() -> AppEmptyState(
            icon = Icons.Rounded.CloudOff,
            title = "Nu am putut încărca echipele",
            message = "Verifică conexiunea și încearcă din nou.",
            modifier = modifier,
            action = { AppSecondaryButton("Încearcă din nou", onRetry, Modifier.width(220.dp)) },
        )
        teams.isEmpty() -> AppEmptyState(
            icon = Icons.Rounded.Groups,
            title = "Nicio echipă încă",
            message = "Creează una sau intră cu un cod de invitație.",
            modifier = modifier,
        )
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
        ) {
            items(teams, key = { it.id }) { team ->
                TeamRow(
                    team = team,
                    onClick = { onOpenTeam(team.id, team.name) },
                    modifier = Modifier
                        .animateItem()
                        .padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TeamRow(team: TeamDTO, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface,
        contentColor = scheme.onSurface,
        interactionSource = source,
        modifier = modifier
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
            Text(
                team.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun TeamsFooter(enabled: Boolean, onCreate: () -> Unit, onJoin: () -> Unit) {
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
                AppPrimaryButton("Creează echipă", onCreate, enabled = enabled)
                AppSecondaryButton("Intră cu un cod", onJoin, enabled = enabled)
            }
        }
    }
}

@Composable
internal fun TeamInputContent(
    title: String,
    label: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    SheetBody {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppPrimaryButton(confirmLabel, { onConfirm(value.trim()) }, enabled = value.isNotBlank())
                AppSecondaryButton("Anulează", onDismiss)
            }
        }
    }
}
