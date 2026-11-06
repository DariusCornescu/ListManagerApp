package com.darius.listmanager.ui.screens

import android.content.Intent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.network.TeamMemberDTO
import com.darius.listmanager.ui.components.AppDangerSoftButton
import com.darius.listmanager.ui.components.AppEmptyState
import com.darius.listmanager.ui.components.AppIconButton
import com.darius.listmanager.ui.components.AppSecondaryButton
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.ConfirmSheet
import com.darius.listmanager.ui.components.SkeletonRows
import com.darius.listmanager.ui.components.pressScale
import com.darius.listmanager.ui.viewmodel.TeamDetailViewModel

@Composable
fun TeamDetailScreen(
    teamId: Long,
    teamName: String,
    onBack: () -> Unit,
    viewModel: TeamDetailViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmLeave by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<TeamMemberDTO?>(null) }

    LaunchedEffect(teamId) { viewModel.load(teamId) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.leftTeam) {
        if (uiState.leftTeam) onBack()
    }

    // Open the Android share sheet when an invite code arrives.
    LaunchedEffect(uiState.inviteCode) {
        uiState.inviteCode?.let { code ->
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Intră în echipa mea din ListManager, „$teamName”, cu acest cod de invitație:\n\n$code"
                )
                type = "text/plain"
            }
            context.startActivity(Intent.createChooser(sendIntent, "Trimite codul de invitație"))
            viewModel.consumeInviteCode()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = teamName,
                subtitle = if (uiState.isLoading || uiState.loadFailed) null else memberCountLabel(uiState.members.size),
                onBack = onBack,
            )
        },
        bottomBar = {
            TeamDetailFooter(enabled = uiState.myUserId != null, onLeave = { confirmLeave = true })
        },
    ) { padding ->
        TeamDetailContent(
            members = uiState.members,
            myUserId = uiState.myUserId,
            amAdmin = uiState.amAdmin,
            isLoading = uiState.isLoading,
            loadFailed = uiState.loadFailed,
            onInvite = viewModel::generateInvite,
            onRemove = { removing = it },
            onRetry = { viewModel.load(teamId) },
            modifier = Modifier.padding(padding),
        )
    }

    if (confirmLeave) {
        ConfirmSheet(
            title = "Părăsești $teamName?",
            message = "Nu vei mai avea acces la lista comună a acestei echipe.",
            confirmLabel = "Părăsește",
            onConfirm = {
                confirmLeave = false
                uiState.myUserId?.let { viewModel.removeMember(it) }
            },
            onDismiss = { confirmLeave = false },
        )
    }

    removing?.let { member ->
        ConfirmSheet(
            title = "Elimini ${memberName(member, uiState.myUserId)}?",
            message = "Va pierde accesul la lista comună a echipei.",
            confirmLabel = "Elimină",
            onConfirm = {
                viewModel.removeMember(member.user_id)
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
}

internal fun memberCountLabel(count: Int): String = if (count == 1) "1 membru" else "$count membri"

internal fun memberName(member: TeamMemberDTO, myUserId: Long?): String =
    if (member.user_id == myUserId) "Utilizator ${member.user_id} (tu)" else "Utilizator ${member.user_id}"

internal fun teamRoleLabel(role: String): String = when (role) {
    "admin" -> "Administrator"
    "member" -> "Membru"
    else -> role
}

@Composable
internal fun TeamDetailContent(
    members: List<TeamMemberDTO>,
    myUserId: Long?,
    amAdmin: Boolean,
    isLoading: Boolean,
    loadFailed: Boolean,
    onInvite: () -> Unit,
    onRemove: (TeamMemberDTO) -> Unit,
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
        loadFailed -> AppEmptyState(
            icon = Icons.Rounded.CloudOff,
            title = "Nu am putut încărca membrii",
            message = "Verifică conexiunea și încearcă din nou.",
            modifier = modifier,
            action = { AppSecondaryButton("Încearcă din nou", onRetry, Modifier.width(220.dp)) },
        )
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
        ) {
            if (amAdmin) item(key = "invite") { InviteRow(onInvite) }
            item(key = "heading") {
                Text(
                    "Membri",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 8.dp),
                )
            }
            items(members, key = { it.id }) { member ->
                MemberRow(
                    member = member,
                    myUserId = myUserId,
                    canRemove = amAdmin && member.user_id != myUserId,
                    onRemove = { onRemove(member) },
                    modifier = Modifier
                        .animateItem()
                        .padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun InviteRow(onInvite: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onInvite,
        shape = RoundedCornerShape(18.dp),
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Rounded.PersonAdd, contentDescription = null)
            Column {
                Text("Invită pe cineva", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Generează un cod și trimite-l", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MemberRow(
    member: TeamMemberDTO,
    myUserId: Long?,
    canRemove: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val name = memberName(member, myUserId)
    Surface(color = scheme.surface, shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Surface(shape = CircleShape, color = scheme.surfaceVariant, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        member.user_id.toString().take(2),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(teamRoleLabel(member.role), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (canRemove) {
                AppIconButton(Icons.Rounded.PersonRemove, "Elimină $name", onRemove, tint = scheme.onErrorContainer)
            }
        }
    }
}

@Composable
internal fun TeamDetailFooter(enabled: Boolean, onLeave: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = scheme.outline)
        Surface(color = scheme.background) {
            AppDangerSoftButton(
                "Părăsește echipa",
                onLeave,
                // Without our own user id, leaving couldn't target anyone.
                enabled = enabled,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
