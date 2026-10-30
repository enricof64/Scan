package io.github.enricof64.scan

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Motion/blur detection for batch capture (F2, plan §7 "rilevamento
 * mosso/sfocato tramite varianza di Laplace").
 *
 * The classic operator: apply the Laplacian kernel to a downscaled
 * grayscale frame and take the variance of the result. A sharp document
 * (high-contrast text) yields a large variance; a motion-blurred or
 * out-of-focus frame smears the edges and the variance collapses by an
 * order of magnitude or more.
 *
 * The math core ([laplaceVariance] on raw pixels) is pure JVM code so the
 * threshold behaviour is unit-testable; the [Bitmap] wrapper only handles
 * decode and downscale.
 */
object Sharpness {

    /**
     * Variance below which a ~[ANALYSIS_MAX_DIM]-px frame counts as
     * blurry. Chained retakes (up to 3 per shot, see
     * BatchScanViewModel) are triggered per shot, and whatever frame is
     * left after the last attempt is accepted and flagged — so a
     * conservative threshold costs at most a re-capture, never a lost
     * page.
     */
    const val BLUR_THRESHOLD = 120.0f

    /** Long edge the frame is downscaled to before measuring. */
    const val ANALYSIS_MAX_DIM = 800

    /**
     * Laplacian variance of [bitmap]. The frame is downscaled to
     * [ANALYSIS_MAX_DIM] internally (border pixels excluded from the
     * average, so no border bias).
     */
    fun laplaceVariance(bitmap: Bitmap, maxDim: Int = ANALYSIS_MAX_DIM): Float {
        val scaled =
            if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
        try {
            val w = scaled.width
            val h = scaled.height
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            return laplaceVariance(pixels, w, h)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /**
     * Pure-JVM core: Laplacian variance of an ARGB_8888 row-major pixel
     * array. Grayscale via a weighted luma conversion; interior pixels
     * only (no border bias).
     */
    fun laplaceVariance(pixels: IntArray, w: Int, h: Int): Float {
        if (w < 3 || h < 3 || pixels.size < w * h) return 0f
        val gray = LongArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] =
                ((p and 0xFF) * 77L + (p shr 8 and 0xFF) * 150L + (p shr 16 and 0xFF) * 29L) /
                    256L
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0L
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val lap =
                    4.0 * gray[i] - gray[i - w] - gray[i + w] - gray[i - 1] - gray[i + 1]
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat().coerceAtLeast(0f)
    }

    /** True when the frame is sharp enough to accept without a retake. */
    fun isSharp(bitmap: Bitmap, maxDim: Int = ANALYSIS_MAX_DIM): Boolean =
        laplaceVariance(bitmap, maxDim) >= BLUR_THRESHOLD
}
