package io.github.enricof64.scan

import android.app.Application
import io.github.enricof64.scan.data.AppContainer

class OpenScanApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // F5: learned-detector adapter for learned-first capture. The ONNX
        // session is created lazily on first use (assets load from the APK).
        ScanPipeline.learnedDetector = { bmp -> DocAligner.detectQuad(applicationContext, bmp) }
    }
}
