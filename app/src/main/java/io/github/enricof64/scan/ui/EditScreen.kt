package io.github.enricof64.scan.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.enricof64.scan.data.DocumentsRepository
import io.github.enricof64.scan.data.PageEntity
import io.github.enricof64.scan.data.SettingsRepository
import io.github.enricof64.scan.Images
import io.github.enricof64.scan.PageCorners
import io.github.enricof64.scan.PageOcr
import io.github.enricof64.scan.ScanFilter
import io.github.enricof64.scan.ScanFilters
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditViewModel(
    val pageId: Long,
    private val repository: DocumentsRepository,
    private val settings: SettingsRepository,
    private val appContext: Context,
) : ViewModel() {

    val page = MutableStateFlow<PageEntity?>(null)
    val busy = MutableStateFlow(false)
    val ocrText = MutableStateFlow<String?>(null)

    /** Re-detect in flight / last outcome (item 11). */
    val redetecting = MutableStateFlow(false)
    val redetectResult = MutableStateFlow<DocumentsRepository.RedetectOutcome?>(null)
    val redetectError = MutableStateFlow<String?>(null)

    /** Owning document's filter (page default when the page has no override). */
    val documentFilter: StateFlow<String?> = MutableStateFlow(null)

    /** App-wide enhancement default. */
    val appFilterDefault: StateFlow<String> = settings.filterDefault
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ScanFilter.DEFAULT.id,
        )

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val loaded = repository.getPage(pageId)
            page.value = loaded
            (documentFilter as MutableStateFlow).value =
                loaded?.let { repository.getDocument(it.documentId)?.filter }
        }
    }

    /** Bakes a 90° clockwise rotation into the stored JPEG, then reloads. */
    fun rotate() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            page.value?.let { repository.rotatePage(it) }
            page.value = repository.getPage(pageId)
            busy.value = false
        }
    }

    fun recognizeText() {
        val current = page.value ?: return
        viewModelScope.launch {
            ocrText.value = runCatching { PageOcr.recognize(appContext, File(current.filePath)) }
                .getOrElse { "" }
        }
    }

    fun dismissOcr() {
        ocrText.value = null
    }

    fun deletePage(onDeleted: () -> Unit) {
        viewModelScope.launch {
            page.value?.let { repository.deletePage(it) }
            onDeleted()
        }
    }

    /** Per-page enhancement override (item 6); also remembered as default. */
    fun setPageFilter(filterId: String?) {
        viewModelScope.launch {
            repository.setPageFilter(pageId, filterId)
            if (filterId != null) settings.setFilterDefault(filterId)
            page.value = repository.getPage(pageId)
        }
    }

    /** Re-runs detection + refinement over the kept original (item 11). */
    fun redetect() {
        if (busy.value || redetecting.value) return
        redetecting.value = true
        redetectError.value = null
        viewModelScope.launch {
            try {
                redetectResult.value = repository.redetectPage(pageId)
                page.value = repository.getPage(pageId)
            } catch (t: Throwable) {
                redetectError.value = t.message ?: "Re-detect failed"
            } finally {
                redetecting.value = false
            }
        }
    }

    fun dismissRedetect() {
        redetectResult.value = null
        redetectError.value = null
    }

    /**
     * Corner-edit session: the quad is kept normalized (0..1) against the
     * source image (kept original, or the stored page when no original
     * survives), so the drag math never depends on the preview scale.
     */
    data class CornerEdit(
        val sourcePath: String,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val quad: FloatArray,
        val fromStoredCorners: Boolean,
    )

    val cornerEdit = MutableStateFlow<CornerEdit?>(null)
    val cornerError = MutableStateFlow<String?>(null)
    val applyingCorners = MutableStateFlow(false)

    fun startCornerEditing() {
        if (cornerEdit.value != null || applyingCorners.value) return
        viewModelScope.launch {
            val p = page.value ?: return@launch
            val source = repository.cornerEditSource(p)
            val bounds = Images.bounds(source)
            val w = bounds?.first
            val h = bounds?.second
            if (w == null || h == null || w <= 0 || h <= 0) {
                cornerError.value = "Cannot read the source image for this page."
                return@launch
            }
            val stored = PageCorners.decode(p.userCorners)
                ?: PageCorners.decode(p.refinedCorners)
                ?: PageCorners.decode(p.autoCorners)
            val fromStored = stored != null && p.imageWidth == w && p.imageHeight == h
            val quad = if (fromStored) {
                FloatArray(8) { i -> (stored!![i] / w).coerceIn(0f, 1f) }
            } else {
                floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
            }
            cornerEdit.value = CornerEdit(source.absolutePath, w, h, quad, fromStored)
        }
    }

    fun updateCornerQuad(normalized: FloatArray) {
        cornerEdit.value?.let { cornerEdit.value = it.copy(quad = normalized) }
    }

    fun applyCornerEdit() {
        val edit = cornerEdit.value ?: return
        if (applyingCorners.value) return
        if (!QuadMath.isValid(edit.quad)) {
            cornerError.value = "Corner selection is too small or invalid."
            return
        }
        applyingCorners.value = true
        cornerError.value = null
        viewModelScope.launch {
            try {
                val pixels = FloatArray(8) {
                    if (it % 2 == 0) edit.quad[it] * edit.sourceWidth
                    else edit.quad[it] * edit.sourceHeight
                }
                repository.cropPageWithCorners(pageId, pixels, edit.sourceWidth, edit.sourceHeight)
                page.value = repository.getPage(pageId)
                cornerEdit.value = null
            } catch (t: Throwable) {
                cornerError.value = t.message ?: "Re-crop failed"
            } finally {
                applyingCorners.value = false
            }
        }
    }

    fun cancelCornerEdit() {
        cornerEdit.value = null
    }

    fun dismissCornerError() {
        cornerError.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(viewModel: EditViewModel, onBack: () -> Unit) {
    val page by viewModel.page.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val ocrText by viewModel.ocrText.collectAsState()
    val docFilter by viewModel.documentFilter.collectAsState()
    val appDefault by viewModel.appFilterDefault.collectAsState()
    val redetecting by viewModel.redetecting.collectAsState()
    val redetectResult by viewModel.redetectResult.collectAsState()
    val redetectError by viewModel.redetectError.collectAsState()
    val cornerEdit by viewModel.cornerEdit.collectAsState()
    val cornerEditing = cornerEdit
    val applyingCorners by viewModel.applyingCorners.collectAsState()
    val cornerError by viewModel.cornerError.collectAsState()
    var confirmingDelete by remember { mutableStateOf(false) }
    var filterPickerOpen by remember { mutableStateOf(false) }
    val effective = ScanFilter.effective(page?.filter, docFilter, appDefault)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit page") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (cornerEdit != null) {
                        // Corner editing replaces the toolbar: cancel only.
                        IconButton(onClick = { viewModel.cancelCornerEdit() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel corner edit")
                        }
                    } else {
                        IconButton(onClick = { filterPickerOpen = true }) {
                            Icon(Icons.Filled.Tune, contentDescription = "Enhancement: ${effective.title}")
                        }
                        IconButton(onClick = { viewModel.redetect() }, enabled = !busy && !redetecting) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Re-detect corners")
                        }
                        IconButton(onClick = { viewModel.startCornerEditing() }, enabled = !busy && !redetecting) {
                            Icon(Icons.Filled.Crop, contentDescription = "Edit corners")
                        }
                        IconButton(onClick = { viewModel.rotate() }, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate 90 degrees")
                        }
                        IconButton(onClick = { viewModel.recognizeText() }) {
                            Icon(Icons.Filled.TextFields, contentDescription = "Recognize text")
                        }
                        IconButton(onClick = { confirmingDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete page")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = page
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Flagged crops (item 8b/11) ask for review right here.
            if (current?.needsCheck == true) {
                Text(
                    "Detection flagged this crop — review it, adjust, or re-detect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (redetecting) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Re-detecting…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (current == null) {
                    CircularProgressIndicator()
                } else if (cornerEditing != null) {
                    CornerEditorCanvas(
                        edit = cornerEditing,
                        applying = applyingCorners,
                        onQuadChanged = { viewModel.updateCornerQuad(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    PageCanvas(
                        filePath = current.filePath,
                        filter = effective,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            cornerEditing?.let { edit ->
                val valid = QuadMath.isValid(edit.quad)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        if (edit.fromStoredCorners) {
                            "Drag the four corners over the sheet, then apply."
                        } else {
                            "No saved corners found — drag them over the sheet, then apply."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.applyCornerEdit() },
                        enabled = valid && !applyingCorners,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (applyingCorners) "Applying…" else "Apply corners")
                    }
                    if (!valid) {
                        Text(
                            "Selection is too small or invalid — spread the corners.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (filterPickerOpen) {
        FilterPickerDialog(
            title = "Page enhancement",
            selectedId = page?.filter,
            defaultLabel = ScanFilter.effective(null, docFilter, appDefault).title,
            onPick = { id -> viewModel.setPageFilter(id) },
            onDismiss = { filterPickerOpen = false },
        )
    }

    redetectResult?.let { outcome ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissRedetect() },
            title = { Text("Corners re-detected") },
            text = {
                Text(
                    buildString {
                        append(
                            if (outcome.cropChanged) "Crop updated from the kept photo."
                            else "No better crop found — kept as is."
                        )
                        if (outcome.needsCheck) append(" Review the crop above.")
                        if (outcome.usedStoredPage) {
                            append(" No original was kept for this page, so detection ran on the stored page.")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissRedetect() }) { Text("OK") }
            },
        )
    }

    redetectError?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissRedetect() },
            title = { Text("Re-detect failed") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissRedetect() }) { Text("OK") }
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete page?") },
            text = { Text("This page will be removed from its document.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.deletePage(onDeleted = onBack)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }

    ocrText?.let { text ->
        @Suppress("DEPRECATION")
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { viewModel.dismissOcr() },
            title = { Text("Recognized text") },
            text = { Text(text.ifBlank { "No text found on this page." }) },
            confirmButton = {
                if (text.isNotBlank()) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(text))
                        viewModel.dismissOcr()
                    }) { Text("Copy") }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissOcr() }) { Text("Close") }
            },
        )
    }

    cornerError?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissCornerError() },
            title = { Text("Corner edit failed") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissCornerError() }) { Text("OK") }
            },
        )
    }
}

/**
 * The corner editor canvas (plan screen 3, base level): the source image
 * with four draggable corner handles. The 3x loupe magnifier arrives with
 * the later camera phase; this version already re-crops the page from the
 * user-dragged quad.
 */
@Composable
private fun CornerEditorCanvas(
    edit: EditViewModel.CornerEdit,
    applying: Boolean,
    onQuadChanged: (FloatArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Preview decode: normalized coordinates make the preview scale irrelevant.
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = edit.sourcePath) {
        value = withContext(Dispatchers.IO) {
            Images.decodeScaled(File(edit.sourcePath), maxDim = 2048)
        }
    }
    val canvasSize = remember { mutableStateOf(IntSize.Zero) }
    val image = bitmap
    val fit = if (image != null && canvasSize.value.width > 0 && canvasSize.value.height > 0) {
        CornerFit.fit(
            canvasSize.value.width,
            canvasSize.value.height,
            image.width,
            image.height,
        )
    } else {
        null
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (image == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Source image",
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize.value = it },
                contentScale = ContentScale.Fit,
            )
            fit?.let { f ->
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(edit.sourcePath) {
                            if (applying) return@pointerInput
                            while (true) {
                                val down = awaitPointerEventScope { awaitFirstDown() }
                                val startNorm = f.toNorm(down.position.x, down.position.y)
                                var handle = -1
                                for (i in 0 until 4) {
                                    val dx = startNorm.x - edit.quad[i * 2]
                                    val dy = startNorm.y - edit.quad[i * 2 + 1]
                                    if (dx * dx + dy * dy < HIT_RADIUS_SQ) {
                                        handle = i
                                        break
                                    }
                                }
                                if (handle < 0) continue
                                val quad = edit.quad.copyOf()
                                quad[handle * 2] = startNorm.x.coerceIn(0f, 1f)
                                quad[handle * 2 + 1] = startNorm.y.coerceIn(0f, 1f)
                                onQuadChanged(quad)
                                var pressed = true
                                while (pressed) {
                                    val event = awaitPointerEventScope { awaitPointerEvent() }
                                    val change = event.changes.first()
                                    val norm = f.toNorm(change.position.x, change.position.y)
                                    quad[handle * 2] = norm.x.coerceIn(0f, 1f)
                                    quad[handle * 2 + 1] = norm.y.coerceIn(0f, 1f)
                                    onQuadChanged(quad)
                                    pressed = change.pressed
                                }
                            }
                        },
                ) {
                    val quad = edit.quad
                    val path = Path().apply {
                        moveTo(f.toScreen(quad[0], quad[1]).x, f.toScreen(quad[0], quad[1]).y)
                        for (i in 1..4) {
                            val j = i % 4
                            lineTo(f.toScreen(quad[j * 2], quad[j * 2 + 1]).x, f.toScreen(quad[j * 2], quad[j * 2 + 1]).y)
                        }
                        close()
                    }
                    // Double-stroked outline: readable on light and dark sheets.
                    drawPath(path, Color(0xFF000000), style = Stroke(4.dp.toPx()))
                    drawPath(path, Color.White, style = Stroke(2.dp.toPx()))
                    for (i in 0 until 4) {
                        val center = f.toScreen(quad[i * 2], quad[i * 2 + 1])
                        drawCircle(color = Color(0xFF000000), radius = HANDLE_RADIUS_DP + 2.dp.toPx(), center = center)
                        drawCircle(color = Color.White, radius = HANDLE_RADIUS_DP + 1.dp.toPx(), center = center)
                        drawCircle(color = Color(0xFFE53935), radius = HANDLE_RADIUS_DP, center = center)
                    }
                }
            }
        }
    }
}

/** Handle radius in dp for the corner editor. */
private val HANDLE_RADIUS_DP = 10f

/** Squared normalized hit radius around a handle (~8% of the frame). */
private const val HIT_RADIUS_SQ = 0.08f * 0.08f

@Composable
private fun PageCanvas(filePath: String, filter: ScanFilter, modifier: Modifier = Modifier) {
    // Re-decodes whenever the file path or filter changes.
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = filePath, key2 = filter) {
        value = withContext(Dispatchers.IO) {
            val decoded = Images.decodeScaled(File(filePath), maxDim = 2048) ?: return@withContext null
            if (filter == ScanFilter.ORIGINAL) {
                decoded
            } else {
                ScanFilters.apply(decoded, filter)?.also { decoded.recycle() } ?: decoded
            }
        }
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = "Scanned page",
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
