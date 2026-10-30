package io.github.enricof64.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure enhancement kernels (item 6): every function here works on ARGB
 * [IntArray]s with explicit dimensions — no Android classes — so the math is
 * hermetically unit-tested. The Android wrapper ([ScanFilters]) only moves
 * pixels in and out of [Bitmap]s.
 *
 * Filters:
 * - DOCUMENT: illumination normalization (per-channel gain from a blurred
 *   background estimate — removes shadows AND the paper's color cast,
 *   plan §8 items 3-4) + uniform contrast stretch + mild 3×3 unsharp mask
 *   on luminance (item 10). White neutral background, crisp dark text;
 *   saturated stamps survive because only the background level moves.
 * - BLACK_WHITE: same normalization, then a fixed threshold.
 * - GRAYSCALE: luminance only.
 * - ORIGINAL: untouched.
 */
object FilterPixels {

    /** Background target after normalization (0-255). */
    const val BG_TARGET = 215

    /** Max gain applied by normalization (avoids blowing out dark scenes). */
    const val MAX_GAIN = 3.2f

    /** Min gain applied by normalization (avoids crushing bright channels). */
    const val MIN_GAIN = 0.35f

    /** Fixed threshold for BLACK_WHITE after normalization. */
    const val BW_THRESHOLD = 150

    /** Unsharp amount for DOCUMENT (fraction of the local-contrast delta). */
    const val UNSHARP_AMOUNT = 0.35f

    /** Working size cap for the background-estimation copy. */
    const val BG_MAX_DIM = 600

    fun luminance(argb: Int): Int =
        ((0.299f * red(argb) + 0.587f * green(argb) + 0.114f * blue(argb))).roundToInt()

    fun applyGrayscale(pixels: IntArray): IntArray =
        IntArray(pixels.size) { i ->
            val y = luminance(pixels[i]).coerceIn(0, 255)
            argb(255, y, y, y)
        }

    /**
     * Automatic filter choice (plan §8 item 6): stride-sample the luminance
     * histogram, find the ink/paper split (midway between the 10th and 90th
     * percentiles), and count ink. Dense-ink pages (filled forms, dense
     * paragraphs) earn the small, crisp 1-bit output; anything sparse, or
     * without a clean bimodal split, keeps the color-preserving Document
     * mode. Pure JVM — no bitmap, no allocation beyond the sample list.
     */
    fun pickAuto(pixels: IntArray): ScanFilter {
        val stride = max(1, pixels.size / 4096)
        val samples = ArrayList<Int>(4096)
        var i = 0
        while (i < pixels.size) {
            samples.add(luminance(pixels[i]))
            i += stride
        }
        samples.sort()
        val p10 = samples[(samples.size * 0.10).toInt().coerceIn(0, samples.size - 1)]
        val p90 = samples[(samples.size * 0.90).toInt().coerceIn(0, samples.size - 1)]
        if (p90 - p10 < 24) return ScanFilter.DOCUMENT // no clean bimodal split
        val split = p10 + (p90 - p10) / 2
        var ink = 0
        for (s in samples) if (s < split) ink++
        return if (ink >= samples.size * 0.20) ScanFilter.BLACK_WHITE
        else ScanFilter.DOCUMENT
    }

    /**
     * Adaptive black-and-white: normalize illumination, then threshold.
     * Output pixels are pure black or white (opaque).
     */
    fun applyBlackWhite(pixels: IntArray, width: Int, height: Int): IntArray {
        val normalized = normalize(pixels, width, height)
        return IntArray(pixels.size) { i ->
            if (luminance(normalized[i]) > BW_THRESHOLD) -1 else -0x1000000
        }
    }

    /**
     * High-contrast document mode: [normalize], a uniform contrast stretch
     * (same scale on all channels — hue-preserving), then a mild unsharp
     * mask applied to luminance only.
     */
    fun applyDocument(pixels: IntArray, width: Int, height: Int): IntArray {
        val normalized = normalize(pixels, width, height)
        val stretched = stretch(normalized)
        return unsharp(stretched, width, height, UNSHARP_AMOUNT)
    }

    /**
     * Illumination normalization + white balance (plan §8 items 3-4):
     * per-pixel, per-channel gain from a blurred background estimate. Each
     * channel's paper background is driven to the same neutral [BG_TARGET],
     * which removes both the illumination gradient (shadows) and the color
     * cast of the lighting (white balance).
     */
    fun normalize(pixels: IntArray, width: Int, height: Int): IntArray {
        require(pixels.size == width * height) { "pixels ${pixels.size} != ${width}x$height" }
        val bg = channelBackgrounds(pixels, width, height)
        return IntArray(pixels.size) { i ->
            val x = i % width
            val y = i / width
            val gx = (x + 0.5f) * bg.sw / width - 0.5f
            val gy = (y + 0.5f) * bg.sh / height - 0.5f
            val gR = gainAt(bg.r, bg.sw, bg.sh, gx, gy)
            val gG = gainAt(bg.g, bg.sw, bg.sh, gx, gy)
            val gB = gainAt(bg.b, bg.sw, bg.sh, gx, gy)
            if (gR == 1f && gG == 1f && gB == 1f) {
                pixels[i]
            } else {
                argb(
                    255,
                    (red(pixels[i]) * gR).roundToInt().coerceIn(0, 255),
                    (green(pixels[i]) * gG).roundToInt().coerceIn(0, 255),
                    (blue(pixels[i]) * gB).roundToInt().coerceIn(0, 255),
                )
            }
        }
    }

    /** Small per-channel blurred background estimate (visible for tests). */
    data class ChannelBackgrounds(val r: IntArray, val g: IntArray, val b: IntArray,
                                  val sw: Int, val sh: Int)

    /**
     * Per-channel background: box-downscale each channel to [BG_MAX_DIM],
     * box-blur with a wide radius. The gain is then sampled bilinearly per
     * pixel — no full-resolution gain map is ever allocated.
     */
    fun channelBackgrounds(pixels: IntArray, width: Int, height: Int): ChannelBackgrounds {
        val scale = min(1f, BG_MAX_DIM.toFloat() / max(width, height))
        val sw = max(1, (width * scale).roundToInt())
        val sh = max(1, (height * scale).roundToInt())
        val radius = max(4, min(sw, sh) / 8)
        return ChannelBackgrounds(
            boxBlur(boxDownscaleChannel(pixels, width, height, sw, sh) { red(it) }, sw, sh, radius),
            boxBlur(boxDownscaleChannel(pixels, width, height, sw, sh) { green(it) }, sw, sh, radius),
            boxBlur(boxDownscaleChannel(pixels, width, height, sw, sh) { blue(it) }, sw, sh, radius),
            sw,
            sh,
        )
    }

    /** Per-pixel gain for one channel's background field (bilinear sample). */
    fun gainAt(bg: IntArray, sw: Int, sh: Int, gx: Float, gy: Float): Float {
        val bgAt = bilinear(bg, sw, sh, gx, gy)
        return (BG_TARGET.toFloat() / max(bgAt, 1f)).coerceIn(MIN_GAIN, MAX_GAIN)
    }

    /** Box-average downscale of one channel. */
    private fun boxDownscaleChannel(
        pixels: IntArray, w: Int, h: Int, sw: Int, sh: Int,
        channel: (Int) -> Int,
    ): IntArray {
        val out = IntArray(sw * sh)
        for (sy in 0 until sh) {
            val y0 = (sy * h / sh)
            val y1 = max(y0 + 1, ((sy + 1) * h / sh))
            for (sx in 0 until sw) {
                val x0 = (sx * w / sw)
                val x1 = max(x0 + 1, ((sx + 1) * w / sw))
                var sum = 0
                var n = 0
                for (y in y0 until min(y1, h)) {
                    for (x in x0 until min(x1, w)) {
                        sum += channel(pixels[y * w + x])
                        n++
                    }
                }
                out[sy * sw + sx] = if (n == 0) 0 else sum / n
            }
        }
        return out
    }

    /** Box-average downscale to a gray image (kept for tests). */
    fun boxDownscaleGray(pixels: IntArray, w: Int, h: Int, sw: Int, sh: Int): IntArray =
        boxDownscaleChannel(pixels, w, h, sw, sh) { luminance(it) }

    /** Separable box blur on a gray image via integral image (small inputs). */
    fun boxBlur(gray: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0) return gray.copyOf()
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += gray[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        fun sum(x0: Int, y0: Int, x1: Int, y1: Int): Long {
            val a = integral[y0 * (w + 1) + x0]
            val b = integral[y0 * (w + 1) + x1]
            val c = integral[y1 * (w + 1) + x0]
            val d = integral[y1 * (w + 1) + x1]
            return d - b - c + a
        }
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val x0 = max(0, x - radius)
            val y0 = max(0, y - radius)
            val x1 = min(w, x + radius + 1)
            val y1 = min(h, y + radius + 1)
            (sum(x0, y0, x1, y1) / ((x1 - x0) * (y1 - y0))).toInt()
        }
    }

    private fun bilinear(field: IntArray, w: Int, h: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, w - 1)
        val y0 = y.toInt().coerceIn(0, h - 1)
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val top = field[y0 * w + x0] * (1 - fx) + field[y0 * w + x1] * fx
        val bottom = field[y1 * w + x0] * (1 - fx) + field[y1 * w + x1] * fx
        return top * (1 - fy) + bottom * fy
    }

    /**
     * Uniform contrast stretch: maps the 2nd–98th luminance percentiles to
     * 0–255 with one scale for all channels (hue-preserving).
     */
    fun stretch(pixels: IntArray): IntArray {
        if (pixels.isEmpty()) return pixels
        val stride = max(1, pixels.size / 4096)
        val samples = ArrayList<Int>(4096)
        var i = 0
        while (i < pixels.size) {
            samples.add(luminance(pixels[i]))
            i += stride
        }
        samples.sort()
        val low = samples[(samples.size * 0.02).toInt().coerceIn(0, samples.size - 1)]
        val high = samples[(samples.size * 0.98).toInt().coerceIn(0, samples.size - 1)]
        if (high - low < 8) return pixels.copyOf()
        val scale = 255f / (high - low)
        return IntArray(pixels.size) { k ->
            val p = pixels[k]
            val y = luminance(p)
            val target = ((y - low) * scale).coerceIn(0f, 255f)
            val gain = if (y <= 0) 1f else target / y
            argb(
                255,
                (red(p) * gain).roundToInt().coerceIn(0, 255),
                (green(p) * gain).roundToInt().coerceIn(0, 255),
                (blue(p) * gain).roundToInt().coerceIn(0, 255),
            )
        }
    }

    /**
     * Mild unsharp mask on luminance: y += amount * (y - 3×3 mean), the delta
     * applied to all channels equally (hue-safe).
     */
    fun unsharp(pixels: IntArray, width: Int, height: Int, amount: Float): IntArray {
        if (amount <= 0f || pixels.isEmpty()) return pixels.copyOf()
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                var sum = 0
                var n = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in 0 until height) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx !in 0 until width) continue
                        sum += luminance(pixels[yy * width + xx])
                        n++
                    }
                }
                val mean = sum / max(n, 1)
                val center = luminance(pixels[i])
                val sharpened = (center + amount * (center - mean)).roundToInt().coerceIn(0, 255)
                val delta = sharpened - center
                if (delta == 0) {
                    out[i] = pixels[i]
                } else {
                    out[i] = argb(
                        255,
                        (red(pixels[i]) + delta).coerceIn(0, 255),
                        (green(pixels[i]) + delta).coerceIn(0, 255),
                        (blue(pixels[i]) + delta).coerceIn(0, 255),
                    )
                }
            }
        }
        return out
    }

    fun red(argb: Int): Int = (argb shr 16) and 0xFF
    fun green(argb: Int): Int = (argb shr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b
}
