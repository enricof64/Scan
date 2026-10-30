package io.github.enricof64.scan.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.enricof64.scan.CameraDiagnostics

/**
 * Read-only dump of every camera's resolution capabilities: what CameraX can
 * select (default stream map) vs what a direct Camera2 session in
 * SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION would reach (Plan B of the 50 MP
 * check). No permission required; the text can be copied for the report.
 *
 * The probe is binder IPC into cameraserver (the first call can take seconds
 * on a cold HAL, and phones like the Fold 8 carry 8-10 lenses), so it runs
 * on [Dispatchers.IO] with a loading state — never during composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.IO) {
            CameraDiagnostics.toText(CameraDiagnostics.collect(context))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Camera diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            val report = text
            if (report == null) {
                Text(
                    "Probing cameras… (the first read can take a few seconds)",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                SelectionContainer {
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(
                    onClick = {
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Camera diagnostics", report)
                        )
                    },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Copy to clipboard") }
            }
        }
    }
}
