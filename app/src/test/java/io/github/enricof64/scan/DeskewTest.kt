package io.github.enricof64.scan

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deskew math tests on synthetic text-line fields: horizontal bars with a
 * known pre-rotation must come back with that rotation (sign included).
 */
class DeskewTest {

    private fun gray(v: Int): Int = FilterPixels.argb(255, v, v, v)

    /**
     * w×h page (paper 220) with [lines] black text bars (30 px high, 30 px
     * pitch), optionally pre-rotated by [deg] (screen convention: positive =
     * down to the right) about the page center.
     */
    private fun barsPage(w: Int = 400, h: Int = 500, lines: Int = 8, deg: Double = 0.0): IntArray {
        val src = IntArray(w * h) { gray(220) }
        val top = (h - lines * 30) / 2
        for (l in 0 until lines) {
            val y0 = top + l * 30
            for (y in y0 until y0 + 8) {
                for (x in 20 until w - 20) {
                    src[y * w + x] = gray(30)
                }
            }
        }
        if (deg == 0.0) return src
        // Rotate the contents by +deg: out[c + v] = in[c + R(-deg)·v].
        val rad = Math.toRadians(-deg)
        val c = cos(rad)
        val s = sin(rad)
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val vx = (x - cx)
                val vy = (y - cy)
                val sx = (cx + vx * c - vy * s)
                val sy = (cy + vx * s + vy * c)
                val xi = sx.toInt()
                val yi = sy.toInt()
                out[y * w + x] = if (xi in 0 until w && yi in 0 until h) src[yi * w + xi] else gray(220)
            }
        }
        return out
    }

    @Test
    fun `horizontal text estimates zero tilt`() {
        val p = barsPage()
        val est = Deskew.estimate(p, 400, 500)
        assertTrue("expected ~0, got $est", kotlin.math.abs(est) < 0.3)
    }

    @Test
    fun `detects a clockwise tilt`() {
        val p = barsPage(deg = 2.5)
        val est = Deskew.estimate(p, 400, 500)
        assertTrue("expected ~+2.5, got $est", est in 2.0..3.0)
    }

    @Test
    fun `detects a negative tilt with the negative sign`() {
        val p = barsPage(deg = -2.0)
        val est = Deskew.estimate(p, 400, 500)
        assertTrue("expected ~-2.0, got $est", est in -3.0..-1.5)
    }

    @Test
    fun `blank page reports no tilt`() {
        val flat = IntArray(300 * 300) { gray(200) }
        assertEquals(0.0, Deskew.estimate(flat, 300, 300), 0.001)
    }

    @Test
    fun `sparse content below the dark-pixel floor reports no tilt`() {
        val p = IntArray(300 * 300) { gray(220) }
        for (i in 0 until 20) p[i * 300 + i] = gray(20)
        assertEquals(0.0, Deskew.estimate(p, 300, 300), 0.001)
    }

    @Test
    fun `significance boundary at half a degree`() {
        assertTrue(!Deskew.isSignificant(0.4))
        assertTrue(!Deskew.isSignificant(-0.4))
        assertTrue(Deskew.isSignificant(0.6))
        assertTrue(Deskew.isSignificant(-0.6))
    }
}
