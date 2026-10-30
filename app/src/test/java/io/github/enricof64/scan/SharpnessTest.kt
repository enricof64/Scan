package io.github.enricof64.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-pixel tests for the F2 blur gate. Synthetic frames: high-contrast
 * vertical stripes (a stand-in for text columns) versus a heavily
 * downscaled-and-upscaled copy of the same frame (a stand-in for motion
 * blur / out-of-focus capture).
 */
class SharpnessTest {

    private fun stripes(w: Int, h: Int, period: Int): IntArray {
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] =
                    if ((x / period) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
        }
        return pixels
    }

    /** Centered box blur (smooth, no new hard edges — like a smear). */
    private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var rSum = 0L; var gSum = 0L; var bSum = 0L; var n = 0L
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val yy = y + dy
                        val xx = x + dx
                        if (xx in 0 until w && yy in 0 until h) {
                            val p = pixels[yy * w + xx]
                            rSum += p and 0xFF
                            gSum += (p shr 8) and 0xFF
                            bSum += (p shr 16) and 0xFF
                            n++
                        }
                    }
                }
                out[y * w + x] = 0xFF000000.toInt() or
                    ((rSum / n).toInt() shl 16) or ((gSum / n).toInt() shl 8) or (bSum / n).toInt()
            }
        }
        return out
    }

    @Test
    fun uniformImageHasNearZeroVariance() {
        val pixels = IntArray(100 * 100) { 0xFF808080.toInt() }
        assertEquals(0f, Sharpness.laplaceVariance(pixels, 100, 100), 1e-3f)
    }

    @Test
    fun sharpFramePassesAndBlurredFrameFails() {
        val w = 200
        val h = 260
        val sharp = stripes(w, h, period = 12)
        // Two 11-px box passes smear the edges the way motion blur does.
        val blurred = boxBlur(boxBlur(sharp, w, h, radius = 5), w, h, radius = 5)
        val vs = Sharpness.laplaceVariance(sharp, w, h)
        val vb = Sharpness.laplaceVariance(blurred, w, h)
        assertTrue("sharp variance $vs must be above the blur threshold", vs >= Sharpness.BLUR_THRESHOLD)
        assertFalse("blurred variance $vb must be below the blur threshold", vb >= Sharpness.BLUR_THRESHOLD)
        assertTrue("sharp ($vs) must outrun blurred ($vb) by an order of magnitude", vs > 10f * vb)
    }

    @Test
    fun tinyAndInvalidFramesYieldZero() {
        assertEquals(0f, Sharpness.laplaceVariance(IntArray(5), 2, 3), 0f)
        assertEquals(0f, Sharpness.laplaceVariance(IntArray(1), 1, 1), 0f)
    }
}
