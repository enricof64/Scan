package io.github.enricof64.scan

import io.github.enricof64.scan.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {

    @Test
    fun labelsAreStable() {
        assertEquals("Auto", ThemeMode.label(ThemeMode.AUTO))
        assertEquals("Light", ThemeMode.label(ThemeMode.LIGHT))
        assertEquals("Dark", ThemeMode.label(ThemeMode.DARK))
    }

    @Test
    fun unknownModesFallBackToAutoLabel() {
        assertEquals("Auto", ThemeMode.label("nonsense"))
        assertEquals("Auto", ThemeMode.label(""))
    }

    @Test
    fun allCoversTheThreeModes() {
        assertEquals(listOf("auto", "light", "dark"), ThemeMode.ALL)
    }
}
