package io.github.enricof64.scan

/**
 * Real DPI of a captured page (plan §8 item 7).
 *
 * The perspective-warped page's pixel dimensions correspond to the physical
 * size of the sheet, so the real DPI is simply px per inch of the paper:
 * `px * 25.4 / mm`. The estimate assumes A4 (210 × 297 mm), the paper size
 * the app targets; other sizes can be passed explicitly.
 *
 * This is an informational value — it is what the capture actually
 * delivered, not a target. The pipeline never upscales a small capture to
 * fake 300/600 DPI (plan §8 item 7: "niente upscaling a 7000 px finti").
 */
object RealDpi {

    /** A4 width in millimetres. */
    const val A4_WIDTH_MM = 210f

    /** A4 height in millimetres. */
    const val A4_HEIGHT_MM = 297f

    /**
     * Average of the width- and height-derived DPI. Returns 0.0 for empty
     * sizes.
     */
    fun estimate(
        pxWidth: Int,
        pxHeight: Int,
        paperMmWidth: Float = A4_WIDTH_MM,
        paperMmHeight: Float = A4_HEIGHT_MM,
    ): Double {
        if (pxWidth <= 0 || pxHeight <= 0) return 0.0
        val dpiW = pxWidth * 25.4 / paperMmWidth
        val dpiH = pxHeight * 25.4 / paperMmHeight
        return (dpiW + dpiH) / 2.0
    }
}
