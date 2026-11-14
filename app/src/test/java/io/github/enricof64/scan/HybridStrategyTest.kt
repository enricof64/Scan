package io.github.enricof64.scan

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F6 hybrid arbitration ([ScanPipeline.hybridPick]): the learned quad wins
 * only if it is plausible (no hard failure) AND out-supports the Canny quad
 * by more than [ScanPipeline.HYBRID_WIN_MARGIN]; every tie, close call and
 * rejected learned answer keeps the Canny quad. Pure geometry, synthetic
 * quads.
 */
class HybridStrategyTest {

    private val w = 4000
    private val h = 3000

    /** A4-ish quad with a 300 px margin and a slight perspective skew. */
    private fun canny() = floatArrayOf(
        500f, 350f, 3520f, 300f, 3480f, 2700f, 470f, 2750f,
    )

    private fun good() = floatArrayOf(
        480f, 360f, 3540f, 310f, 3500f, 2690f, 450f, 2760f,
    )

    private fun same(expected: FloatArray, actual: FloatArray?) {
        assertTrue("expected ${expected.contentToString()}, got ${actual?.contentToString()}",
            expected contentEquals actual)
    }

    @Test
    fun `no learned answer keeps Canny`() {
        same(canny(), ScanPipeline.hybridPick(canny(), null, 0.4, 0.0, w, h))
    }

    @Test
    fun `learned out-supports Canny by more than the margin and wins`() {
        same(good(), ScanPipeline.hybridPick(canny(), good(), 0.40, 0.51, w, h))
    }

    @Test
    fun `exactly the margin is a tie and keeps Canny`() {
        val margin = ScanPipeline.HYBRID_WIN_MARGIN
        same(canny(), ScanPipeline.hybridPick(canny(), good(), 0.40, 0.40 + margin, w, h))
    }

    @Test
    fun `weaker learned support keeps Canny`() {
        same(canny(), ScanPipeline.hybridPick(canny(), good(), 0.60, 0.45, w, h))
    }

    @Test
    fun `non-convex learned answer keeps Canny even with perfect support`() {
        val bowtie = floatArrayOf(200f, 200f, 3800f, 200f, 200f, 2800f, 3800f, 2800f)
        same(canny(), ScanPipeline.hybridPick(canny(), bowtie, 0.20, 1.0, w, h))
    }

    @Test
    fun `tiny learned answer keeps Canny even with perfect support`() {
        val tiny = floatArrayOf(500f, 500f, 700f, 500f, 700f, 700f, 500f, 700f)
        same(canny(), ScanPipeline.hybridPick(canny(), tiny, 0.20, 1.0, w, h))
    }

    @Test
    fun `non-finite learned answer keeps Canny even with perfect support`() {
        val nan = floatArrayOf(Float.NaN, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        same(canny(), ScanPipeline.hybridPick(canny(), nan, 0.20, 1.0, w, h))
    }

    @Test
    fun `margin constant is sane`() {
        // The arbitration must be a real margin: zero margin would let
        // floating noise flip the winner, a margin over 1.0 is unreachable.
        org.junit.Assert.assertTrue(ScanPipeline.HYBRID_WIN_MARGIN > 0.0)
        org.junit.Assert.assertTrue(ScanPipeline.HYBRID_WIN_MARGIN < 1.0)
        org.junit.Assert.assertTrue(ScanPipeline.HYBRID_CANNY_GATE > 0.0)
        org.junit.Assert.assertTrue(ScanPipeline.HYBRID_CANNY_GATE <= 1.0)
    }
}
