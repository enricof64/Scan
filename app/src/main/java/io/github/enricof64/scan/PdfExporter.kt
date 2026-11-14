package io.github.enricof64.scan

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.RectF
import io.github.enricof64.scan.ocr.OcrResult
import java.io.File
import java.io.OutputStream
import kotlin.math.min

/**
 * Builds multi-page PDFs from page JPEGs with the framework PdfDocument.
 * Pages are placed centered on an A4 sheet (595×842 pt, swapped for
 * landscape scans) — and, as everywhere in OpenScan, nothing is ever stamped
 * on top of them.
 *
 * Resolution (item 10): pages decode at up to 3600 px so a full-bleed A4
 * embeds at ~300 DPI (3600 px / 11.69 in); the old 2400 px ceiling topped
 * out near 200 DPI.
 */
object PdfExporter {

    /** Centered, aspect-preserving placement of an image on a page. Pure math — unit-tested. */
    data class Fit(val x: Float, val y: Float, val width: Float, val height: Float)

    fun fitInto(imageWidth: Int, imageHeight: Int, pageWidth: Float, pageHeight: Float): Fit {
        val scale = min(pageWidth / imageWidth, pageHeight / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        return Fit(
            x = (pageWidth - width) / 2f,
            y = (pageHeight - height) / 2f,
            width = width,
            height = height,
        )
    }

    /** Decode bound for PDF pages: covers 300 DPI on A4's long edge. */
    const val PDF_MAX_DIM = 3600

    fun exportPdf(pageFiles: List<File>, out: OutputStream) {
        exportPdf(pageFiles, List(pageFiles.size) { ScanFilter.ORIGINAL }, out)
    }

    /**
     * Filtered export (item 6): each page is enhanced with its effective
     * filter before embedding. A filter that fails (e.g. OOM) falls back to
     * the original page — export never crashes on enhancement.
     *
     * [ocr] (P1: searchable PDF) is the per-page OCR result in the same page
     * order; its lines are written as an INVISIBLE text layer (alpha 0) over
     * the fitted page image so the PDF is selectable/searchable. Pages with
     * a null result (OCR failed) get image-only text.
     */
    fun exportPdf(
        pageFiles: List<File>,
        filters: List<ScanFilter>,
        out: OutputStream,
        ocr: List<OcrResult?> = emptyList(),
    ) {
        val document = PdfDocument()
        try {
            pageFiles.forEachIndexed { index, file ->
                val decoded = Images.decodeScaled(file, maxDim = PDF_MAX_DIM) ?: return@forEachIndexed
                val filter = filters.getOrElse(index) { ScanFilter.ORIGINAL }
                val bitmap = if (filter == ScanFilter.ORIGINAL) {
                    decoded
                } else {
                    ScanFilters.apply(decoded, filter)?.also { decoded.recycle() } ?: decoded
                }
                val landscape = bitmap.width > bitmap.height
                val pageWidth = if (landscape) A4_LONG_PT else A4_SHORT_PT
                val pageHeight = if (landscape) A4_SHORT_PT else A4_LONG_PT

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                val fit = fitInto(bitmap.width, bitmap.height, pageWidth.toFloat(), pageHeight.toFloat())
                canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(fit.x, fit.y, fit.x + fit.width, fit.y + fit.height),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                drawTextLayer(canvas, fit, ocr.getOrElse(index) { null })
                document.finishPage(page)
                bitmap.recycle()
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    const val A4_SHORT_PT = 595
    const val A4_LONG_PT = 842

    // ------------------------------------------------------------------
    // Invisible text layer (P1: searchable PDF)
    // ------------------------------------------------------------------

    /** Placement of one OCR line on the page canvas. Pure math — unit-tested. */
    data class TextPlacement(val x: Float, val y: Float, val size: Float)

    /**
     * Maps a normalized OCR [box] (0..1 in page-image space, corners
     * TL,TR,BR,BL) onto the fitted page rect [fit]: the invisible text is
     * written as one string per line, left-aligned at the box top-left, with
     * a font size matched to the line height so it covers the whole box.
     */
    fun textPlacement(fit: Fit, box: FloatArray): TextPlacement {
        var minX = box[0]
        var maxX = box[0]
        var minY = box[1]
        var maxY = box[1]
        for (i in 1 until 4) {
            val x = box[i * 2]
            val y = box[i * 2 + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val size = ((maxY - minY) * fit.height).coerceAtLeast(2f)
        return TextPlacement(
            x = fit.x + minX * fit.width,
            // Baseline: em box roughly fills the line box (ascent ~0.8 of em).
            y = fit.y + minY * fit.height + size * 0.8f,
            size = size,
        )
    }

    /**
     * Writes the invisible (alpha 0) text layer of one page: selectable and
     * searchable in any PDF viewer, invisible on screen and in print.
     */
    private fun drawTextLayer(canvas: android.graphics.Canvas, fit: Fit, result: OcrResult?) {
        val lines = result?.lines ?: return
        if (lines.isEmpty()) return
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.TRANSPARENT
        }
        for (line in lines) {
            if (line.text.isEmpty()) continue
            val place = textPlacement(fit, line.box)
            paint.textSize = place.size
            canvas.drawText(line.text, place.x, place.y, paint)
        }
    }
}
