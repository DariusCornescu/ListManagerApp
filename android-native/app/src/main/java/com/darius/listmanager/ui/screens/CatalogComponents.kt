package com.darius.listmanager.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.ui.components.AppEmptyState
import com.darius.listmanager.ui.components.AppIconButton
import com.darius.listmanager.ui.components.AppTextButton
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.viewmodel.DistributorWithProducts
import java.util.Locale

internal fun catalogSubtitle(productCount: Int, distributorCount: Int): String {
    val locale = Locale.forLanguageTag("ro")
    val products = if (productCount == 1) "1 produs" else String.format(locale, "%,d produse", productCount)
    val distributors = if (distributorCount == 1) "1 distribuitor" else "$distributorCount distribuitori"
    return "$products · $distributors"
}

@Composable
internal fun CatalogTopBar(
    subtitle: String?,
    isAdmin: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onEnterSelection: () -> Unit,
) {
    AppTopBar(
        title = "Catalog",
        subtitle = subtitle,
        onBack = onBack,
        actions = {
            AppIconButton(Icons.Rounded.Refresh, "Reîmprospătează", onRefresh)
            if (isAdmin) AppIconButton(Icons.Rounded.Checklist, "Selectează produse", onEnterSelection)
        },
    )
}

@Composable
internal fun CatalogSelectionTopBar(
    selectedCount: Int,
    totalCount: Int,
    onExit: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
) {
    AppTopBar(
        title = if (selectedCount == 1) "1 selectat" else "$selectedCount selectate",
        navigationIcon = { AppIconButton(Icons.Rounded.Close, "Închide selecția", onExit) },
        actions = {
            if (totalCount > 0 && selectedCount == totalCount) {
                AppTextButton("Niciunul", onClearSelection)
            } else {
                AppTextButton("Toate", onSelectAll)
            }
            AppIconButton(
                Icons.Rounded.Delete,
                "Șterge produsele selectate",
                onDelete,
                enabled = selectedCount > 0,
            )
        },
    )
}

@Composable
internal fun CatalogSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("Caută produse…") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) AppIconButton(Icons.Rounded.Clear, "Golește căutarea", { onQueryChange("") })
        },
        singleLine = true,
        shape = CircleShape,
    )
}

@Composable
internal fun CatalogEmptyState(query: String, modifier: Modifier = Modifier) {
    if (query.isBlank()) {
        AppEmptyState(Icons.Rounded.Inventory2, "Niciun produs în catalog", modifier)
    } else {
        AppEmptyState(Icons.Rounded.SearchOff, "Niciun rezultat", modifier, message = "Încearcă alt nume sau un sinonim.")
    }
}

/**
 * Each product is its own lazy item, so a distributor with thousands of products costs only what's
 * on screen. Groups start closed, and open by themselves during a search or a selection.
 */
@Composable
internal fun CatalogList(
    catalog: List<DistributorWithProducts>,
    searching: Boolean,
    isAdmin: Boolean,
    isSelectionMode: Boolean,
    selectedProductIds: Set<Long>,
    onProductLongPress: (Long) -> Unit,
    onProductClick: (Long) -> Unit,
    onEditProduct: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var opened by rememberSaveable { mutableStateOf(emptySet<Long>().toLongArray()) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 96.dp),
    ) {
        catalog.forEach { group ->
            val id = group.distributor.id
            val isOpen = searching || isSelectionMode || id in opened
            val hasRows = isOpen && group.products.isNotEmpty()
            item(key = "group-$id", contentType = "group") {
                GroupHeader(
                    name = group.distributor.distributorName,
                    count = group.products.size,
                    isOpen = isOpen,
                    closedShape = !hasRows,
                    canToggle = !searching && !isSelectionMode,
                    onToggle = { opened = if (id in opened) (opened.toSet() - id).toLongArray() else opened + id },
                    modifier = Modifier
                        .animateItem()
                        .padding(bottom = if (hasRows) 0.dp else 8.dp),
                )
            }
            if (hasRows) {
                val last = group.products.last().id
                items(group.products, key = { "product-${it.id}" }, contentType = { "product" }) { product ->
                    ProductRow(
                        product = product,
                        isLast = product.id == last,
                        isAdmin = isAdmin,
                        isSelectionMode = isSelectionMode,
                        isSelected = product.id in selectedProductIds,
                        onLongPress = { onProductLongPress(product.id) },
                        onClick = { onProductClick(product.id) },
                        onEdit = { onEditProduct(product.id) },
                        modifier = Modifier
                            .animateItem()
                            .padding(bottom = if (product.id == last) 8.dp else 0.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    name: String,
    count: Int,
    isOpen: Boolean,
    closedShape: Boolean,
    canToggle: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape: Shape = if (closedShape) RoundedCornerShape(18.dp) else RoundedCornerShape(18.dp, 18.dp, 0.dp, 0.dp)
    Surface(
        onClick = onToggle,
        enabled = canToggle,
        shape = shape,
        color = scheme.surface,
        contentColor = scheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = if (isOpen) "Deschis" else "Închis" },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .heightIn(min = 60.dp)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Surface(shape = CircleShape, color = scheme.surfaceVariant) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
            if (canToggle) {
                Icon(
                    if (isOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProductRow(
    product: ProductEntity,
    isLast: Boolean,
    isAdmin: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = if (isLast) RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp) else RoundedCornerShape(0.dp)
    Surface(shape = shape, color = scheme.surface, contentColor = scheme.onSurface, modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = scheme.surfaceVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                    .semantics {
                        contentDescription = if (isSelected) "${product.name}, selectat" else product.name
                    }
                    .heightIn(min = 58.dp)
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                if (isSelectionMode) {
                    Checkbox(checked = isSelected, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(product.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (!product.aliases.isNullOrBlank()) {
                        Text(
                            "Sinonime: ${product.aliases}",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                if (isAdmin) {
                    AppIconButton(Icons.Rounded.Edit, "Editează ${product.name}", onEdit, tint = scheme.onSurfaceVariant)
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}
