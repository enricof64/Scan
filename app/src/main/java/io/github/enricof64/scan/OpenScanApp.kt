package io.github.enricof64.scan

import android.app.Application
import io.github.enricof64.scan.data.AppContainer

class OpenScanApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
