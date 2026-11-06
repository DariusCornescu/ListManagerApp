package com.darius.listmanager.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.ui.components.AppEmptyState
import com.darius.listmanager.ui.components.AppIconButton
import com.darius.listmanager.ui.components.AppSecondaryButton
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.SkeletonRows
import com.darius.listmanager.ui.viewmodel.PDFsViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PDFsScreen(
    onBack: () -> Unit,
    onGoToList: () -> Unit = {},
    viewModel: PDFsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    fun uriFor(pdf: File) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdf)

    Scaffold(
        topBar = {
            AppTopBar(
                title = "PDF-uri",
                onBack = onBack,
                actions = { AppIconButton(Icons.Rounded.Refresh, "Reîmprospătează", { viewModel.loadPdfs() }) },
            )
        },
    ) { padding ->
        PdfsContent(
            pdfs = uiState.pdfs,
            isLoading = uiState.isLoading,
            hasError = uiState.error != null,
            onOpen = { pdf ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uriFor(pdf), "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Deschide PDF"))
            },
            onShare = { pdf ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uriFor(pdf))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Distribuie PDF"))
            },
            onRetry = { viewModel.loadPdfs() },
            onGoToList = onGoToList,
            modifier = Modifier.padding(padding),
        )
    }
}

internal fun pdfTitle(file: File): String = file.nameWithoutExtension.replace("_", " ")

internal fun pdfDetail(file: File): String {
    val date = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("ro")).format(Date(file.lastModified()))
    return "$date · ${file.length() / 1024} KB"
}

@Composable
internal fun PdfsContent(
    pdfs: List<File>,
    isLoading: Boolean,
    hasError: Boolean,
    onOpen: (File) -> Unit,
    onShare: (File) -> Unit,
    onRetry: () -> Unit,
    onGoToList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isLoading -> SkeletonRows(
            count = 3,
            rowHeight = 128.dp,
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        hasError -> AppEmptyState(
            icon = Icons.Rounded.ErrorOutline,
            title = "Nu am putut citi PDF-urile",
            modifier = modifier,
            action = { AppSecondaryButton("Încearcă din nou", onRetry, Modifier.width(220.dp)) },
        )
        pdfs.isEmpty() -> AppEmptyState(
            icon = Icons.Rounded.Description,
            title = "Niciun PDF încă",
            message = "Generează PDF-uri din lista curentă și apar aici.",
            modifier = modifier,
            action = { AppSecondaryButton("Mergi la listă", onGoToList, Modifier.width(220.dp)) },
        )
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 16.dp),
        ) {
            items(pdfs, key = { it.absolutePath }) { pdf ->
                PdfCard(
                    file = pdf,
                    onOpen = { onOpen(pdf) },
                    onShare = { onShare(pdf) },
                    modifier = Modifier
                        .animateItem()
                        .padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PdfCard(file: File, onOpen: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surface, shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = scheme.surfaceVariant,
                    contentColor = scheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PictureAsPdf, contentDescription = null) }
                }
                Column(Modifier.weight(1f)) {
                    Text(pdfTitle(file), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(pdfDetail(file), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSecondaryButton("Deschide", onOpen, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.OpenInNew)
                AppSecondaryButton("Distribuie", onShare, Modifier.weight(1f), icon = Icons.Rounded.Share)
            }
        }
    }
}
