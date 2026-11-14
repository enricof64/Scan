package io.github.enricof64.scan.ocr

/**
 * One recognized text line.
 *
 * [box] holds the four corners TL, TR, BR, BL as `[x0, y0, x1, y1, x2, y2, x3, y3]`
 * NORMALIZED to 0..1 in the page image space (fractions of page width/height),
 * so it maps onto any re-decode of the page — the PDF exporter uses it to
 * place the invisible text layer on the fitted page rect.
 */
data class OcrLine(
    val text: String,
    val score: Float,
    val box: FloatArray,
) {
    val centerX: Float get() = (box[0] + box[2] + box[4] + box[6]) / 4f
    val centerY: Float get() = (box[1] + box[3] + box[5] + box[7]) / 4f
}

/** All text lines of one page, in reading order (top to bottom, left to right). */
data class OcrResult(
    val lines: List<OcrLine>,
    val detMs: Long,
    val recMs: Long,
) {
    val elapsedMs: Long get() = detMs + recMs

    /** Lines joined by newlines — what the "copy text" UI shows. */
    val plainText: String get() = lines.joinToString("\n") { it.text }
}
