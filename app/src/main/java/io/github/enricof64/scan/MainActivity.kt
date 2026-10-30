package io.github.enricof64.scan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.enricof64.scan.data.AppContainer
import io.github.enricof64.scan.ui.BatchImport
import io.github.enricof64.scan.ui.CameraDiagnosticsScreen
import io.github.enricof64.scan.ui.CaptureRoute
import io.github.enricof64.scan.ui.CaptureViewModel
import io.github.enricof64.scan.ui.DocumentScreen
import io.github.enricof64.scan.ui.DocumentViewModel
import io.github.enricof64.scan.ui.DocumentsScreen
import io.github.enricof64.scan.ui.DocumentsViewModel
import io.github.enricof64.scan.ui.EditScreen
import io.github.enricof64.scan.ui.ExportScreen
import io.github.enricof64.scan.ui.ExportViewModel
import io.github.enricof64.scan.ui.EditViewModel
import io.github.enricof64.scan.ui.SettingsScreen
import io.github.enricof64.scan.ui.SettingsViewModel
import io.github.enricof64.scan.ui.theme.OpenScanTheme
import io.github.enricof64.scan.ui.theme.ThemeMode

object Routes {
    const val DOCUMENTS = "documents"
    const val CAPTURE = "capture?documentId={documentId}&gallery={gallery}"
    const val DOCUMENT = "document/{documentId}"
    const val EXPORT = "export/{documentId}"
    const val EDIT = "edit/{pageId}"
    const val SETTINGS = "settings"
    const val CAMERA_DIAGNOSTICS = "camera_diagnostics"

    fun document(id: Long) = "document/$id"
    fun export(id: Long) = "export/$id"
    fun edit(id: Long) = "edit/$id"

    /**
     * Without [documentId] the capture flow creates a new document; with one
     * it appends. [gallery] skips the scanner and opens the photo picker
     * straight away (the library's Import images entry). Absent arguments are
     * left out of the URI entirely so their nav defaults apply.
     */
    fun capture(documentId: Long? = null, gallery: Boolean = false): String =
        buildString {
            append("capture?gallery=$gallery")
            if (documentId != null) append("&documentId=$documentId")
        }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as OpenScanApp).container
        // One update check per launch; the banner and the settings screen both
        // observe the same app-scoped answer.
        container.updateMonitor.checkAtLaunch()
        // Flush due developer-share uploads (queued while offline or dead).
        container.repository.flushUploads()
        setContent {
            // Read inside the composition so the scheme switches live when
            // the user changes the override in settings.
            val themeMode by container.settings.themeMode.collectAsState(initial = ThemeMode.AUTO)
            OpenScanTheme(themeMode = themeMode) {
                OpenScanNavHost(container)
            }
        }
    }
}

@Composable
fun OpenScanNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    NavHost(navController = navController, startDestination = Routes.DOCUMENTS) {

        composable(Routes.DOCUMENTS) {
            DocumentsScreen(
                viewModel = viewModel { DocumentsViewModel(container.repository) },
                updateMonitor = container.updateMonitor,
                onOpenDocument = { id -> navController.navigate(Routes.document(id)) },
                onScan = { navController.navigate(Routes.capture()) },
                onImportImages = { navController.navigate(Routes.capture(gallery = true)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel { SettingsViewModel(container.settings, container.repository) },
                updateMonitor = container.updateMonitor,
                onBack = { navController.popBackStack() },
                onOpenCameraDiagnostics = { navController.navigate(Routes.CAMERA_DIAGNOSTICS) },
            )
        }

        composable(Routes.CAMERA_DIAGNOSTICS) {
            CameraDiagnosticsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            Routes.CAPTURE,
            arguments = listOf(
                navArgument("documentId") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("gallery") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            // -1 (the default) means "create a new document"; a real id means
            // the scanned pages are appended to that existing document.
            val appendTo = entry.arguments?.getLong("documentId", -1L)?.takeIf { it > 0 }
            CaptureRoute(
                viewModel = viewModel { CaptureViewModel(container.repository) },
                documentId = appendTo,
                galleryOnly = entry.arguments?.getBoolean("gallery", false) ?: false,
                onDone = { documentId, added, failed ->
                    BatchImport.summary.value = added to failed
                    if (appendTo != null) {
                        // The document screen sits right below in the back
                        // stack and picks the new pages up from its own flow.
                        navController.popBackStack()
                    } else {
                        navController.navigate(Routes.document(documentId)) {
                            popUpTo(Routes.DOCUMENTS)
                        }
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }

        composable(
            Routes.DOCUMENT,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType }),
        ) { entry ->
            val documentId = entry.arguments?.getLong("documentId") ?: return@composable
            DocumentScreen(
                viewModel = viewModel(key = "document-$documentId") {
                    DocumentViewModel(
                        documentId, container.repository, container.files,
                        container.settings, container.appVersion, appContext,
                    )
                },
                onEditPage = { pageId -> navController.navigate(Routes.edit(pageId)) },
                onAddPages = { navController.navigate(Routes.capture(documentId)) },
                onExport = { navController.navigate(Routes.export(documentId)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            Routes.EXPORT,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType }),
        ) { entry ->
            val documentId = entry.arguments?.getLong("documentId") ?: return@composable
            ExportScreen(
                viewModel = viewModel(key = "export-$documentId") {
                    ExportViewModel(
                        documentId, container.repository, container.files,
                        container.settings, appContext,
                    )
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            Routes.EDIT,
            arguments = listOf(navArgument("pageId") { type = NavType.LongType }),
        ) { entry ->
            val pageId = entry.arguments?.getLong("pageId") ?: return@composable
            EditScreen(
                viewModel = viewModel(key = "edit-$pageId") {
                    EditViewModel(pageId, container.repository, container.settings, appContext)
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
