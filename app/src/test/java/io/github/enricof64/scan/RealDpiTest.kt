package io.github.enricof64.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-DPI math (plan §8 item 7): px per physical paper size.
 */
class RealDpiTest {

    @Test
    fun `a 300 dpi A4 scan estimates about 300`() {
        // 210 mm at 300 dpi = 2480 px; 297 mm = 3508 px.
        val dpi = RealDpi.estimate(2480, 3508)
        assertTrue("expected ~300, got $dpi", dpi in 299.0..301.0)
    }

    @Test
    fun `an A4-shaped capture maps linearly`() {
        // 2550x3600 is ~1:1.414 like A4 → ~308 dpi.
        val dpi = RealDpi.estimate(2550, 3600)
        assertTrue("expected ~308, got $dpi", dpi in 305.0..310.0)
    }

    @Test
    fun `non-A4 paper can be passed explicitly`() {
        // A5 (148x210 mm) at 150 dpi: 874x1240 px.
        val dpi = RealDpi.estimate(874, 1240, paperMmWidth = 148f, paperMmHeight = 210f)
        assertTrue("expected ~150, got $dpi", dpi in 149.0..151.0)
    }

    @Test
    fun `empty sizes give zero`() {
        assertEquals(0.0, RealDpi.estimate(0, 3508), 0.001)
        assertEquals(0.0, RealDpi.estimate(2480, 0), 0.001)
    }

    @Test
    fun `estimate is deterministic`() {
        val a = RealDpi.estimate(3508, 2480)
        assertEquals(a, RealDpi.estimate(3508, 2480), 0.001)
        assertTrue(a > 0)
    }
}
