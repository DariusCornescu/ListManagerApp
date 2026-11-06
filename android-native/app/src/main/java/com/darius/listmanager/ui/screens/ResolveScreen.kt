package com.darius.listmanager.ui.screens

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.data.local.entity.UnknownProductEntity
import com.darius.listmanager.data.repository.AuthState
import com.darius.listmanager.ui.components.AppEmptyState
import com.darius.listmanager.ui.components.AppIconButton
import com.darius.listmanager.ui.components.AppTextButton
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.ProductFormSheet
import com.darius.listmanager.ui.components.SkeletonRows
import com.darius.listmanager.ui.components.pressScale
import com.darius.listmanager.ui.viewmodel.NeedsReviewViewModel
import com.darius.listmanager.ui.viewmodel.ReviewItem
import com.darius.listmanager.ui.viewmodel.UnknownViewModel
import com.darius.listmanager.util.RankedProduct
import kotlinx.coroutines.launch

/** Everything voice couldn't decide alone: phrases with several matches, and phrases with none. */
@Composable
fun ResolveScreen(
    onBack: () -> Unit,
    reviewViewModel: NeedsReviewViewModel = viewModel(),
    unknownViewModel: UnknownViewModel = viewModel(),
) {
    val review by reviewViewModel.uiState.collectAsState()
    val unknown by unknownViewModel.uiState.collectAsState()
    val role by AuthState.role.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf<UnknownProductEntity?>(null) }

    LaunchedEffect(review.message) {
        review.message?.let {
            snackbarHostState.showSnackbar(it)
            reviewViewModel.clearMessage()
        }
    }
    LaunchedEffect(unknown.saveMessage) {
        unknown.saveMessage?.let {
            snackbarHostState.showSnackbar(it)
            unknownViewModel.clearSaveMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = "De rezolvat",
                subtitle = resolveSubtitle(review.items.size + unknown.unknownProducts.size),
                onBack = onBack,
            )
        },
    ) { padding ->
        ResolveContent(
            reviewItems = review.items,
            unknownItems = unknown.unknownProducts,
            isLoading = review.isLoading || unknown.isLoading,
            hasError = unknown.error != null,
            canAddToCatalog = role == "ADMIN",
            onPick = { item, candidate ->
                reviewViewModel.addCandidate(item.id, item.spokenText, candidate.product.id, candidate.product.name)
            },
            onNoneOfThese = { reviewViewModel.sendToUnknown(it.id, it.spokenText) },
            onAdd = { adding = it },
            onDelete = { item ->
                unknownViewModel.deleteUnknownProduct(item.id)
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = "Șters: „${item.spokenText}”",
                        actionLabel = "Anulează",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) unknownViewModel.restoreUnknownProduct(item.spokenText)
                }
            },
            modifier = Modifier.padding(padding),
        )
    }

    adding?.let { item ->
        ProductFormSheet(
            title = "Adaugă în catalog",
            subtitle = "Ai zis „${item.spokenText}”.",
            initialName = item.spokenText,
            distributors = unknown.distributors,
            onDismiss = { adding = null },
            onSave = { name, distributor, aliases ->
                unknownViewModel.saveProduct(item.id, name, distributor, aliases)
                adding = null
            },
        )
    }
}

internal fun resolveSubtitle(count: Int): String = when (count) {
    0 -> "Nimic de rezolvat"
    1 -> "1 lucru"
    else -> "$count lucruri"
}

@Composable
internal fun ResolveContent(
    reviewItems: List<ReviewItem>,
    unknownItems: List<UnknownProductEntity>,
    isLoading: Boolean,
    hasError: Boolean,
    canAddToCatalog: Boolean,
    onPick: (ReviewItem, RankedProduct) -> Unit,
    onNoneOfThese: (ReviewItem) -> Unit,
    onAdd: (UnknownProductEntity) -> Unit,
    onDelete: (UnknownProductEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isLoading -> SkeletonRows(
            count = 4,
            rowHeight = 96.dp,
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        hasError -> AppEmptyState(
            icon = Icons.Rounded.CloudOff,
            title = "Nu am putut încărca lista",
            message = "Închide ecranul și încearcă din nou.",
            modifier = modifier,
        )
        reviewItems.isEmpty() && unknownItems.isEmpty() -> AppEmptyState(
            icon = Icons.Rounded.Check,
            title = "Nimic de rezolvat",
            message = "Tot ce ai dictat a ajuns unde trebuie.",
            positive = true,
            modifier = modifier,
        )
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 16.dp),
        ) {
            if (reviewItems.isNotEmpty()) {
                item(key = "review-heading") { SectionHeading("De verificat", reviewItems.size, Modifier.animateItem()) }
                items(reviewItems, key = { "review-${it.id}" }) { item ->
                    ReviewCard(
                        item = item,
                        onPick = { onPick(item, it) },
                        onNoneOfThese = { onNoneOfThese(item) },
                        modifier = Modifier
                            .animateItem()
                            .padding(bottom = 8.dp),
                    )
                }
            }
            if (unknownItems.isNotEmpty()) {
                item(key = "unknown-heading") { SectionHeading("Necunoscute", unknownItems.size, Modifier.animateItem()) }
                items(unknownItems, key = { "unknown-${it.id}" }) { item ->
                    UnknownRow(
                        item = item,
                        canAddToCatalog = canAddToCatalog,
                        onAdd = { onAdd(item) },
                        onDelete = { onDelete(item) },
                        modifier = Modifier
                            .animateItem()
                            .padding(bottom = 8.dp),
                    )
                }
                if (!canAddToCatalog) {
                    item(key = "admin-note") {
                        Text(
                            "Doar un administrator poate adăuga produse în catalog.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, count: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = scheme.onSurfaceVariant)
        Surface(shape = CircleShape, color = scheme.surfaceVariant) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun ReviewCard(
    item: ReviewItem,
    onPick: (RankedProduct) -> Unit,
    onNoneOfThese: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surface, shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp)) {
            Text(
                "Ai zis: „${item.spokenText}”",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
            )
            if (item.candidates.isEmpty()) {
                Text(
                    "Nu am găsit nicio potrivire.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                )
            }
            item.candidates.forEach { candidate ->
                val source = remember { MutableInteractionSource() }
                Surface(
                    onClick = { onPick(candidate) },
                    shape = RoundedCornerShape(14.dp),
                    color = scheme.surfaceVariant,
                    contentColor = scheme.onSurface,
                    interactionSource = source,
                    modifier = Modifier
                        .pressScale(source)
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            candidate.product.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.Add, contentDescription = null)
                    }
                }
            }
            AppTextButton("Niciunul, mută la necunoscute", onNoneOfThese)
        }
    }
}

@Composable
private fun UnknownRow(
    item: UnknownProductEntity,
    canAddToCatalog: Boolean,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surface, shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("„${item.spokenText}”", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Nu există în catalog", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (canAddToCatalog) AppTextButton("Adaugă", onAdd)
            AppIconButton(Icons.Rounded.Delete, "Șterge „${item.spokenText}”", onDelete, tint = scheme.onSurfaceVariant)
        }
    }
}
