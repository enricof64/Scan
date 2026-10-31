package io.github.enricof64.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos

/**
 * Residual rotation of a cropped page (plan §8, item 2: deskew).
 *
 * The perspective crop aligns the sheet's corners, but the sheet (or the text
 * laid out on it) can still be tilted a fraction of a degree to several
 * degrees. The tilt is estimated with a projection-profile search on a
 * binarized copy: for each candidate angle the dark pixels are projected onto
 * the axis perpendicular to that angle and the concentration of the resulting
 * row profile is measured. Text lines concentrate into sharp peaks only at
 * the true angle; diffuse content (shadows, paper noise) does not.
 *
 * Pure JVM (no Android classes) so the math is hermetically unit-tested; the
 * Android-side rotation lives in [ScanPipeline.deskew].
 */
object Deskew {

    /** Tilts at or below this (degrees) are left uncorrected. */
    const val SIGNIFICANT_THRESHOLD_DEG = 0.5

    /** Long-edge size of the binarized working copy. */
    const val ANALYSIS_MAX_DIM = 800

    /** ±10° covers any realistic document tilt with large margin. */
    const val SEARCH_RANGE_DEG = 10.0

    /** Below this many dark pixels there is no content to align. */
    const val MIN_DARK_PIXELS = 50

    /** Dark = below the 30th percentile of luminance (text+shadow vs paper). */
    private const val DARK_PERCENTILE = 0.30

    fun isSignificant(angleDeg: Double): Boolean =
        abs(angleDeg) > SIGNIFICANT_THRESHOLD_DEG

    /**
     * Estimated residual tilt of the text lines in degrees, screen-space
     * convention: positive = the lines run down to the right. Returns 0.0
     * when the image has too little content to estimate.
     *
     * To correct, rotate the bitmap by the negated angle (Android's
     * `Matrix.postRotate` takes positive = clockwise on screen).
     */
    fun estimate(gray: IntArray, width: Int, height: Int): Double {
        require(gray.size == width * height) {
            "pixels ${gray.size} != ${width}x$height"
        }
        val (gw, gh) = dimPair(width, height, ANALYSIS_MAX_DIM)
        val small = downscaleGray(gray, width, height, gw, gh)
        val threshold = percentile(small, DARK_PERCENTILE)
        val xs = IntArray(small.size)
        val ys = IntArray(small.size)
        var n = 0
        for (i in small.indices) {
            if (small[i] < threshold) {
                xs[n] = i % gw
                ys[n] = i / gw
                n++
            }
        }
        if (n < MIN_DARK_PIXELS) return 0.0
        val coarse = search(xs, ys, n, gw, gh, -SEARCH_RANGE_DEG, SEARCH_RANGE_DEG, 0.5)
        return search(xs, ys, n, gw, gh, coarse - 0.5, coarse + 0.5, 0.05)
    }

    private fun dimPair(w: Int, h: Int, maxDim: Int): Pair<Int, Int> {
        val scale = min(1f, maxDim.toFloat() / max(w, h))
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }

    /** Box-average downscale of a gray image (per-bucket mean). */
    private fun downscaleGray(gray: IntArray, w: Int, h: Int, sw: Int, sh: Int): IntArray {
        if (sw == w && sh == h) return gray
        val out = IntArray(sw * sh)
        for (sy in 0 until sh) {
            val y0 = sy * h / sh
            val y1 = max(y0 + 1, (sy + 1) * h / sh)
            for (sx in 0 until sw) {
                val x0 = sx * w / sw
                val x1 = max(x0 + 1, (sx + 1) * w / sw)
                var sum = 0
                var cnt = 0
                for (y in y0 until min(y1, h)) {
                    val row = y * w
                    for (x in x0 until min(x1, w)) {
                        sum += gray[row + x]
                        cnt++
                    }
                }
                out[sy * sw + sx] = if (cnt == 0) 0 else sum / cnt
            }
        }
        return out
    }

    /** Percentile by stride-sampled sort (fast on big images). */
    private fun percentile(values: IntArray, p: Double): Int {
        val stride = max(1, values.size / 4096)
        val samples = ArrayList<Int>(4096)
        var i = 0
        while (i < values.size) {
            samples.add(values[i])
            i += stride
        }
        samples.sort()
        return samples[(samples.size * p).toInt().coerceIn(0, samples.size - 1)]
    }

    /**
     * Projection-profile search over [fromDeg, toDeg] in [stepDeg] steps.
     * Score per angle: Σ rowSum² of the dark-pixel projection — maximized
     * when the text rows are flat, i.e. at the true tilt.
     */
    private fun search(
        xs: IntArray, ys: IntArray, n: Int, w: Int, h: Int,
        fromDeg: Double, toDeg: Double, stepDeg: Double,
    ): Double {
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        // Row index stays within ±radius of the center after any rotation.
        val radius = max(w, h) + 8
        val profile = IntArray(2 * radius + 1)
        var bestAngle = fromDeg
        var bestScore = -1L
        var angle = fromDeg
        while (angle <= toDeg + 1e-9) {
            val rad = Math.toRadians(angle)
            val sinA = sin(rad)
            val cosA = cos(rad)
            profile.fill(0)
            for (i in 0 until n) {
                val dx = xs[i] - cx
                val dy = ys[i] - cy
                val p = (-(dx * sinA) + (dy * cosA) + cy).toInt()
                profile[(p + radius).coerceIn(0, profile.size - 1)]++
            }
            var score = 0L
            for (v in profile) {
                if (v != 0) score += (v * v).toLong()
            }
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
            angle += stepDeg
        }
        return bestAngle
    }
}
