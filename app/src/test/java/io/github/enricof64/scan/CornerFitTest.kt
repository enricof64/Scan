package io.github.enricof64.scan

import io.github.enricof64.scan.ui.CornerFit
import io.github.enricof64.scan.ui.QuadMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerFitTest {

    @Test
    fun squareInSquareFillsTheBox() {
        val fit = CornerFit.fit(1000, 1000, 1000, 1000)
        assertEquals(0f, fit.left, 0.01f)
        assertEquals(0f, fit.top, 0.01f)
        assertEquals(1000f, fit.drawnWidth, 0.01f)
        assertEquals(1000f, fit.drawnHeight, 0.01f)
    }

    @Test
    fun landscapeImageIsCenteredVertically() {
        val fit = CornerFit.fit(1000, 1000, 1000, 500)
        assertEquals(0f, fit.left, 0.01f)
        assertEquals(250f, fit.top, 0.01f)
        assertEquals(1000f, fit.drawnWidth, 0.01f)
        assertEquals(500f, fit.drawnHeight, 0.01f)
    }

    @Test
    fun roundTripMapsPointsBack() {
        val fit = CornerFit.fit(800, 600, 2000, 1500)
        val screen = fit.toScreen(0.25f, 0.75f)
        val norm = fit.toNorm(screen.x, screen.y)
        assertEquals(0.25f, norm.x, 0.001f)
        assertEquals(0.75f, norm.y, 0.001f)
    }

    @Test
    fun fitRefusesNonPositiveImageDimensions() {
        try {
            CornerFit.fit(100, 100, 0, 100)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}

class QuadMathTest {

    @Test
    fun unitSquareHasUnitArea() {
        val quad = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        assertEquals(1f, QuadMath.area(quad), 0.001f)
    }

    @Test
    fun reversedWindingHasNegativeArea() {
        val quad = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 1f, 1f)
        assertTrue(QuadMath.area(quad) < 0f)
    }

    @Test
    fun collapsedQuadHasNoArea() {
        val quad = floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f, 1f, 1f)
        assertEquals(0f, QuadMath.area(quad), 0.001f)
    }

    @Test
    fun validQuadsPassAndTinyOnesFail() {
        assertTrue(QuadMath.isValid(floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)))
        assertFalse(QuadMath.isValid(floatArrayOf(0.4f, 0.4f, 0.6f, 0.4f, 0.6f, 0.6f, 0.4f, 0.6f)))
    }
}
