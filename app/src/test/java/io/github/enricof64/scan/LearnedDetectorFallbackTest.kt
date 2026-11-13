package io.github.enricof64.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5 learned-first strategy: the learned detector's answer is trusted
 * unless it is a [QuadPlausibility] hard failure (null, non-convex, tiny,
 * non-finite). Soft flags (angles, aspect, border) do NOT block — the Canny
 * path raises the same flags on its own answer, and the review UI handles
 * flagged pages. Pure geometry, synthetic quads.
 */
class LearnedDetectorFallbackTest {

    private val w = 4000
    private val h = 3000

    /** A4-ish quad with a 300 px margin and a slight perspective skew. */
    private fun good() = floatArrayOf(
        500f, 350f, 3520f, 300f, 3480f, 2700f, 470f, 2750f,
    )

    @Test
    fun `null learned answer falls back to Canny`() {
        assertFalse(ScanPipeline.acceptLearnedQuad(null, w, h))
    }

    @Test
    fun `plausible learned answer is accepted`() {
        assertTrue(ScanPipeline.acceptLearnedQuad(good(), w, h))
    }

    @Test
    fun `soft flags do not block acceptance`() {
        // A long receipt-like sliver: extreme aspect ratio — flagged, but not
        // a hard failure, so the learned answer is still trusted.
        val sliver = floatArrayOf(100f, 300f, 3900f, 280f, 3900f, 1400f, 100f, 1400f)
        val verdict = QuadPlausibility.check(sliver, w, h)
        assertFalse(verdict.plausible)
        assertFalse(verdict.hardFail)
        assertTrue(ScanPipeline.acceptLearnedQuad(sliver, w, h))
    }

    @Test
    fun `non-convex learned answer falls back to Canny`() {
        val bowtie = floatArrayOf(200f, 200f, 3800f, 200f, 200f, 2800f, 3800f, 2800f)
        assertFalse(ScanPipeline.acceptLearnedQuad(bowtie, w, h))
    }

    @Test
    fun `tiny learned answer falls back to Canny`() {
        val tiny = floatArrayOf(500f, 500f, 700f, 500f, 700f, 700f, 500f, 700f)
        assertFalse(ScanPipeline.acceptLearnedQuad(tiny, w, h))
    }

    @Test
    fun `non-finite learned answer falls back to Canny`() {
        val nan = floatArrayOf(Float.NaN, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertFalse(ScanPipeline.acceptLearnedQuad(nan, w, h))
    }
}
