package io.github.enricof64.scan.ui

import androidx.compose.ui.geometry.Offset

/**
 * Geometry for the corner editor: maps normalized quad coordinates (0..1 in
 * source-image space) onto the screen rect where the source image is drawn
 * with ContentScale.Fit, and back.
 */
data class CornerFit(
    val left: Float,
    val top: Float,
    val drawnWidth: Float,
    val drawnHeight: Float,
) {
    /** Normalized (image space) point → screen offset. */
    fun toScreen(nx: Float, ny: Float): Offset =
        Offset(left + nx * drawnWidth, top + ny * drawnHeight)

    /** Screen offset → normalized (image space) point. */
    fun toNorm(x: Float, y: Float): Offset =
        Offset((x - left) / drawnWidth, (y - top) / drawnHeight)

    companion object {
        /**
         * The fit rect for an [imageW]×[imageH] image inside a
         * [containerW]×[containerH] box, centered on both axes.
         */
        fun fit(containerW: Int, containerH: Int, imageW: Int, imageH: Int): CornerFit {
            require(imageW > 0 && imageH > 0) { "image dimensions must be positive" }
            val scale = minOf(containerW.toFloat() / imageW, containerH.toFloat() / imageH)
            val drawnW = imageW * scale
            val drawnH = imageH * scale
            return CornerFit(
                left = (containerW - drawnW) / 2f,
                top = (containerH - drawnH) / 2f,
                drawnWidth = drawnW,
                drawnHeight = drawnH,
            )
        }
    }
}

/** Sanity checks for user-dragged quads (normalized space). */
object QuadMath {

    /**
     * Shoelace polygon area of the quad (TL, TR, BR, BL) in unit space.
     * Positive for a correctly ordered quad, negative when the points are
     * wound backwards, zero when they collapse.
     */
    fun area(quad: FloatArray): Float {
        var a = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            a += quad[i * 2] * quad[j * 2 + 1] - quad[j * 2] * quad[i * 2 + 1]
        }
        return a / 2f
    }

    /**
     * A dragged quad is acceptable when it keeps a sane share of the frame:
     * too small (accidental drag) or backwards-wound quads are rejected.
     */
    fun isValid(quad: FloatArray): Boolean = area(quad) >= MIN_AREA

    /** Minimum quad area (fraction of the frame) accepted for a re-crop. */
    const val MIN_AREA = 0.05f
}
