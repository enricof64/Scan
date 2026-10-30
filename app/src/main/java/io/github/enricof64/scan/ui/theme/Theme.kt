package io.github.enricof64.scan.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val BrandTeal = Color(0xFF0F766E)
private val BrandTealDark = Color(0xFF4DB6AC)

private val LightColors = lightColorScheme(
    primary = BrandTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF4A635F),
    surface = Color(0xFFFBFDFC),
)

private val DarkColors = darkColorScheme(
    primary = BrandTealDark,
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF00504B),
    onPrimaryContainer = Color(0xFF6FD9CE),
    secondary = Color(0xFFB1CCC6),
    surface = Color(0xFF101413),
)

/** Theme modes stored in settings; anything else behaves like AUTO. */
object ThemeMode {
    const val AUTO = "auto"
    const val LIGHT = "light"
    const val DARK = "dark"

    val ALL = listOf(AUTO, LIGHT, DARK)

    fun label(mode: String): String = when (mode) {
        LIGHT -> "Light"
        DARK -> "Dark"
        else -> "Auto"
    }
}

@Composable
fun OpenScanTheme(themeMode: String = ThemeMode.AUTO, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> systemDark
    }
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        useDark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
