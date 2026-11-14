package io.github.enricof64.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QuadIou (F5 verdict metric): known overlaps, the near-identical regression
 * case (the original Sutherland–Hodgman port dropped the entering point on
 * an outside→inside transition and collapsed near-identical quads to a
 * zero-area sliver), orientation independence and degenerate input.
 */
class QuadIouTest {

    private fun quad(x: Double, y: Double, w: Double, h: Double) =
        floatArrayOf(x.toFloat(), y.toFloat(), (x + w).toFloat(), y.toFloat(),
            (x + w).toFloat(), (y + h).toFloat(), x.toFloat(), (y + h).toFloat())

    @Test
    fun `identical quads give IoU 1`() {
        assertEquals(1.0, QuadIou.iou(quad(0.0, 0.0, 10.0, 20.0), quad(0.0, 0.0, 10.0, 20.0)), 1e-9)
    }

    @Test
    fun `a one pixel shift of a 1400x1800 quad is still above 0_99`() {
        // The exact F5 regression pair from the first on-device run: the
        // detected quad is one pixel inside the ground truth everywhere.
        val det = floatArrayOf(432f, 648f, 888f, 616f, 929f, 1237f, 473f, 1270f)
        val gt = floatArrayOf(433f, 648f, 889f, 616f, 930f, 1239f, 474f, 1271f)
        assertTrue(QuadIou.iou(det, gt) > 0.99)
    }

    @Test
    fun `half overlap of two unit squares is one third`() {
        val a = quad(0.0, 0.0, 10.0, 10.0)
        val b = quad(5.0, 0.0, 10.0, 10.0)
        assertEquals(1.0 / 3.0, QuadIou.iou(a, b), 1e-9)
    }

    @Test
    fun `disjoint quads give IoU 0`() {
        val a = quad(0.0, 0.0, 10.0, 10.0)
        val b = quad(20.0, 20.0, 5.0, 5.0)
        assertEquals(0.0, QuadIou.iou(a, b), 1e-12)
    }

    @Test
    fun `clockwise winding gives the same IoU`() {
        val a = quad(1.0, 2.0, 30.0, 40.0)
        val aCw = a.toList().chunked(2).reversed().flatten().toFloatArray()
        val b = quad(5.0, 10.0, 40.0, 20.0)
        assertEquals(QuadIou.iou(a, b), QuadIou.iou(aCw, b), 1e-9)
    }

    @Test
    fun `a collinear quad is degenerate and gives IoU 0`() {
        val line = floatArrayOf(0f, 0f, 10f, 0f, 10f, 0f, 0f, 0f)
        assertEquals(0.0, QuadIou.iou(line, quad(0.0, 0.0, 10.0, 10.0)), 1e-12)
    }

    @Test
    fun `wrong sized input gives IoU 0`() {
        assertEquals(0.0, QuadIou.iou(floatArrayOf(1f, 2f), quad(0.0, 0.0, 1.0, 1.0)), 1e-12)
    }

    @Test
    fun `containment gives the area ratio`() {
        // b is the inner half of a: intersection = b, union = a.
        val a = quad(0.0, 0.0, 10.0, 10.0)
        val b = quad(2.5, 2.5, 5.0, 5.0)
        assertEquals(0.25, QuadIou.iou(a, b), 1e-9)
    }
}
