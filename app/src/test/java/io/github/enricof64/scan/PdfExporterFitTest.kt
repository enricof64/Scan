package io.github.enricof64.scan

import io.github.enricof64.scan.PdfExporter
import org.junit.Assert.assertEquals
import org.junit.Test

/** Placement math behind PDF export: aspect-preserving, centered on the sheet. */
class PdfExporterFitTest {

    @Test
    fun `portrait scan fills the width and centers vertically`() {
        val fit = PdfExporter.fitInto(800, 1000, PdfExporter.A4_SHORT_PT.toFloat(), PdfExporter.A4_LONG_PT.toFloat())
        assertEquals(595f, fit.width, 0.001f)
        assertEquals(743.75f, fit.height, 0.001f)
        assertEquals(0f, fit.x, 0.001f)
        assertEquals(49.125f, fit.y, 0.001f)
    }

    @Test
    fun `a wide scan letterboxes horizontally on a portrait sheet`() {
        val fit = PdfExporter.fitInto(2000, 500, PdfExporter.A4_SHORT_PT.toFloat(), PdfExporter.A4_LONG_PT.toFloat())
        assertEquals(595f, fit.width, 0.001f)
        assertEquals(148.75f, fit.height, 0.001f)
        assertEquals(0f, fit.x, 0.001f)
        assertEquals(346.625f, fit.y, 0.001f)
    }

    // ---- Invisible text layer (P1: searchable PDF) -----------------------

    @Test
    fun `text placement maps a normalized box onto the fitted rect`() {
        // Portrait scan filling the full width; line at normalized (0.1..0.9 x, 0.2..0.25 y).
        val fit = PdfExporter.Fit(x = 0f, y = 49.125f, width = 595f, height = 743.75f)
        val box = floatArrayOf(0.1f, 0.2f, 0.9f, 0.2f, 0.9f, 0.25f, 0.1f, 0.25f)
        val place = PdfExporter.textPlacement(fit, box)
        assertEquals(59.5f, place.x, 0.001f)            // 0.1 * 595
        assertEquals(49.125f + 0.2f * 743.75f + 0.05f * 743.75f * 0.8f, place.y, 0.001f) // top + 0.8 * size
        assertEquals(0.05f * 743.75f, place.size, 0.001f) // line height in pt
    }

    @Test
    fun `text placement degrades to a minimum size for a degenerate box`() {
        val fit = PdfExporter.Fit(x = 10f, y = 10f, width = 100f, height = 100f)
        val box = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
        val place = PdfExporter.textPlacement(fit, box)
        assertEquals(10f + 0.5f * 100f, place.x, 0.001f)
        assertEquals(2f, place.size, 0.001f) // coerceAtLeast(2)
    }
}
