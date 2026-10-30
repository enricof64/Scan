package io.github.enricof64.scan.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.enricof64.scan.JpegExporter
import io.github.enricof64.scan.PdfExporter
import io.github.enricof64.scan.ScanFilter
import io.github.enricof64.scan.data.DocumentFiles
import io.github.enricof64.scan.data.DocumentsRepository
import io.github.enricof64.scan.data.PageEntity
import io.github.enricof64.scan.data.SettingsRepository
import io.github.enricof64.scan.util.formatFileSize
import io.github.enricof64.scan.util.formatPageCount
import io.github.enricof64.scan.util.safeFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The export screen of the document flow (plan screen 5): it renders the
 * document and hands the result to the system — Share, Print, Open, Save —
 * instead of mixing the export with the page review.
 */
class ExportViewModel(
    val documentId: Long,
    private val repository: DocumentsRepository,
    private val files: DocumentFiles,
    private val settings: SettingsRepository,
    private val appContext: Context,
) : ViewModel() {

    val document: StateFlow<io.github.enricof64.scan.data.DocumentEntity?> =
        repository.observeDocument(documentId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pages: StateFlow<List<PageEntity>> = repository.observePages(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** App-wide enhancement default (item 6). */
    val appFilterDefault: StateFlow<String> = settings.filterDefault
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanFilter.DEFAULT.id)

    val exporting = MutableStateFlow(false)
    val exportError = MutableStateFlow<String?>(null)

    /** A finished PDF waiting for the user to pick Share/Print/Open/Save. */
    val exportReady = MutableStateFlow<File?>(null)

    /** Explicit share handoff (PDF or JPEGs). */
    val shareRequest = MutableStateFlow<ShareRequest?>(null)

    /** One-shot snackbar notices (saved-to path, …). */
    val notice = MutableStateFlow<String?>(null)

    data class ShareRequest(val file: File, val mimeType: String, val label: String)

    fun sharePdf() {
        exportReady.value?.let {
            shareRequest.value = ShareRequest(it, "application/pdf", "Share PDF")
        }
    }

    fun dismissExportError() {
        exportError.value = null
    }

    fun dismissExportReady() {
        exportReady.value = null
    }

    fun consumeShareRequest() {
        shareRequest.value = null
    }

    fun consumeNotice() {
        notice.value = null
    }

    /** Effective enhancement filter for every page (item 6). */
    suspend fun effectiveFilters(): List<ScanFilter> {
        val doc = repository.getDocument(documentId)
        val default = settings.filterDefault.first()
        return repository.getPages(documentId).map { page ->
            ScanFilter.effective(page.filter, doc?.filter, default)
        }
    }

    /** Renders every page into one enhanced PDF, then offers the actions. */
    fun exportPdf() {
        if (exporting.value) return
        exporting.value = true
        exportError.value = null
        viewModelScope.launch {
            try {
                val title = document.value?.title ?: DocumentsRepository.defaultTitle()
                val pageFiles = collectPageFiles()
                val filters = effectiveFilters()
                val out = files.sharedPdfFile(title)
                withContext(Dispatchers.IO) {
                    FileOutputStream(out).use { stream -> PdfExporter.exportPdf(pageFiles, filters, stream) }
                }
                exportReady.value = out
            } catch (t: Throwable) {
                exportError.value = t.message ?: "PDF export failed"
            } finally {
                exporting.value = false
            }
        }
    }

    /** Copies the ready PDF to the user's SAF destination; confirms inline. */
    fun saveExportTo(destination: Uri) {
        val source = exportReady.value ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(destination)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    } ?: error("Could not open destination")
                }
                notice.value = "PDF saved (${formatFileSize(source)})."
            } catch (t: Throwable) {
                exportError.value = t.message ?: "Save failed"
            }
        }
    }

    /**
     * Exports pages as JPEG images: the stored page file copied as-is for a
     * single page, a zip of JPEGs for several. No re-encoding, no watermark.
     */
    fun exportJpegs() {
        if (exporting.value) return
        exporting.value = true
        exportError.value = null
        viewModelScope.launch {
            try {
                val title = document.value?.title ?: DocumentsRepository.defaultTitle()
                val pageFiles = collectPageFiles()
                if (pageFiles.size == 1) {
                    val image = files.sharedImageFile(title)
                    withContext(Dispatchers.IO) {
                        JpegExporter.exportSingle(pageFiles.first(), FileOutputStream(image))
                    }
                    shareRequest.value = ShareRequest(image, "image/jpeg", "Share image")
                } else {
                    val zip = files.sharedZipFile(title)
                    withContext(Dispatchers.IO) {
                        JpegExporter.exportZip(pageFiles, FileOutputStream(zip))
                    }
                    shareRequest.value = ShareRequest(zip, "application/zip", "Share images")
                }
            } catch (t: Throwable) {
                exportError.value = t.message ?: "JPEG export failed"
            } finally {
                exporting.value = false
            }
        }
    }

    private suspend fun collectPageFiles(): List<File> =
        pages.value
            .ifEmpty { repository.getPages(documentId) }
            .map { File(it.filePath) }
            .filter { it.exists() }
            .ifEmpty { error("No pages to export") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(viewModel: ExportViewModel, onBack: () -> Unit) {
    val document by viewModel.document.collectAsState()
    val pages by viewModel.pages.collectAsState()
    val exporting by viewModel.exporting.collectAsState()
    val exportError by viewModel.exportError.collectAsState()
    val exportReady by viewModel.exportReady.collectAsState()
    val shareRequest by viewModel.shareRequest.collectAsState()
    val notice by viewModel.notice.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Storage-access save-as for the ready PDF.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) viewModel.saveExportTo(uri)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Export") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onBack, enabled = !exporting) {
                        Text("Done", style = MaterialTheme.typography.labelLarge)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (exporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text(
                document?.title ?: "",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${formatPageCount(pages.size)} — " +
                    "the PDF applies the document enhancement, the images are the stored pages as-is.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { viewModel.exportPdf() },
                enabled = !exporting && pages.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Create PDF")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.exportJpegs() },
                enabled = !exporting && pages.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Create images (JPEG)")
            }
        }
    }

    // Hand an explicit share request to the system share sheet.
    LaunchedEffect(shareRequest) {
        val request = shareRequest ?: return@LaunchedEffect
        runCatching { PdfActions.share(context, request.file, request.mimeType, request.label) }
        viewModel.consumeShareRequest()
    }

    // One-shot notices (saved-to confirmation, …).
    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    // The finished PDF and its actions: Share, Print, Open, Save.
    exportReady?.let { pdf ->
        val title = document?.title ?: DocumentsRepository.defaultTitle()
        AlertDialog(
            onDismissRequest = { viewModel.dismissExportReady() },
            title = { Text("PDF ready") },
            text = {
                Text("${safeFileName(title)}.pdf — ${formatPageCount(pages.size)} (${formatFileSize(pdf)}).")
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.sharePdf() }) { Text("Share") }
                    TextButton(onClick = {
                        runCatching {
                            PdfActions.print(context, pdf, safeFileName(title))
                        }
                    }) { Text("Print") }
                    TextButton(onClick = {
                        runCatching {
                            PdfActions.openWith(context, pdf)
                        }
                    }) { Text("Open with…") }
                    TextButton(onClick = {
                        saveLauncher.launch("${safeFileName(title)}.pdf")
                    }) { Text("Save") }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissExportReady() }) { Text("Close") }
            },
        )
    }

    exportError?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissExportError() },
            title = { Text("Export failed") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissExportError() }) { Text("OK") }
            },
        )
    }
}
