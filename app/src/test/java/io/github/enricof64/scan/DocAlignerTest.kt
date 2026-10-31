package io.github.enricof64.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure post-processing of DocAligner heatmaps: threshold, per-channel
 * centroid, 128→source mapping. (The model run itself is exercised by the
 * on-device eval benchmark.)
 */
class DocAlignerTest {

    private fun heatmapsWithBlobs(vararg centers: IntArray?): FloatArray {
        val hm = FloatArray(4 * DocAligner.HEATMAP_SIZE * DocAligner.HEATMAP_SIZE)
        for (c in 0 until 4) {
            val center = centers[c] ?: continue
            val cx = center[0]
            val cy = center[1]
            for (y in 0 until DocAligner.HEATMAP_SIZE) {
                for (x in 0 until DocAligner.HEATMAP_SIZE) {
                    val d2 = (x - cx).toDouble() * (x - cx) + (y - cy).toDouble() * (y - cy)
                    hm[c * DocAligner.HEATMAP_SIZE * DocAligner.HEATMAP_SIZE + y * DocAligner.HEATMAP_SIZE + x] =
                        (0.9 * Math.exp(-d2 / 8.0)).toFloat()
                }
            }
        }
        return hm
    }

    @Test
    fun `four corner blobs map to the source corners`() {
        // 128-space centers 8/120 → source 800x600: x = 8*800/128 = 50, y = 8*600/128 = 37.5
        val hm = heatmapsWithBlobs(intArrayOf(8, 8), intArrayOf(120, 8), intArrayOf(120, 120), intArrayOf(8, 120))
        val quad = DocAligner.cornersFromHeatmaps(hm, 800, 600)!!
        val expected = floatArrayOf(50f, 37.5f, 750f, 37.5f, 750f, 562.5f, 50f, 562.5f)
        for (i in expected.indices) {
            assertEquals(expected[i], quad[i], 2.0f)
        }
    }

    @Test
    fun `blobs are read in TL TR BR BL channel order`() {
        val hm = heatmapsWithBlobs(
            intArrayOf(30, 20),  // TL
            intArrayOf(90, 20),  // TR
            intArrayOf(90, 100), // BR
            intArrayOf(30, 100), // BL
        )
        val quad = DocAligner.cornersFromHeatmaps(hm, 128, 128)!!
        // 1:1 mapping at 128x128 source: each point ~ its blob center.
        val expectedX = floatArrayOf(30f, 90f, 90f, 30f)
        val expectedY = floatArrayOf(20f, 20f, 100f, 100f)
        for (c in 0 until 4) {
            assertEquals(expectedX[c], quad[c * 2], 2.0f)
            assertEquals(expectedY[c], quad[c * 2 + 1], 2.0f)
        }
    }

    @Test
    fun `a flat channel yields null (no document corner there)`() {
        val hm = heatmapsWithBlobs(intArrayOf(8, 8), intArrayOf(120, 8), intArrayOf(120, 120), null)
        assertNull(DocAligner.cornersFromHeatmaps(hm, 800, 600))
    }

    @Test
    fun `a weak peak below threshold still counts via argmax`() {
        // Fourth-channel blob scaled to a 0.25 peak: no pixel reaches the 0.3
        // threshold, but the peak is clear — the argmax fallback must fire.
        val hm = heatmapsWithBlobs(intArrayOf(8, 8), intArrayOf(120, 8), intArrayOf(120, 120), intArrayOf(8, 120))
        val off = 3 * DocAligner.HEATMAP_SIZE * DocAligner.HEATMAP_SIZE
        for (i in 0 until DocAligner.HEATMAP_SIZE * DocAligner.HEATMAP_SIZE) hm[off + i] *= 0.25f / 0.9f
        val quad = DocAligner.cornersFromHeatmaps(hm, 128, 128)!!
        assertEquals(8f, quad[6], 1.5f)
        assertEquals(120f, quad[7], 1.5f)
    }
}
