package io.github.enricof64.scan.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPureMathTest {

    // ---- CtcDecode -------------------------------------------------------

    // classCount = 5: 0 = blank, 1..3 = a..c, 4 = space (last class).
    private val chars = arrayOf("a", "b", "c")
    private fun row(vararg best: Int): FloatArray =
        List(5) { c -> if (best.contains(c)) 10f else 0f }.toFloatArray()

    @Test
    fun ctc_basic_word() {
        // T=4: a, a, b, blank  ->  "ab"
        val logits = row(1) + row(1) + row(2) + row(0)
        val (text, conf) = CtcDecode.decode(logits, 4, 5, chars)
        assertEquals("ab", text)
        assertTrue(conf > 0f)
    }

    @Test
    fun ctc_blank_run_collapses_and_repeats() {
        // a, blank, blank, a  ->  "aa" (blank-separated repeats are kept)
        val logits = row(1) + row(0) + row(0) + row(1)
        val (text, _) = CtcDecode.decode(logits, 4, 5, chars)
        assertEquals("aa", text)
    }

    @Test
    fun ctc_space_is_last_class() {
        // a, space, b  ->  "a b" (space = last class = classCount-1)
        val logits = row(1) + row(4) + row(2)
        val (text, _) = CtcDecode.decode(logits, 3, 5, chars)
        assertEquals("a b", text)
    }

    @Test
    fun ctc_all_blank_is_empty() {
        val logits = row(0) + row(0)
        val (text, conf) = CtcDecode.decode(logits, 2, 5, chars)
        assertEquals("", text)
        assertEquals(0f, conf, 0f)
    }

    // ---- detResizeDims ---------------------------------------------------

    @Test
    fun detResize_longSideCappedAt960_multipleOf32() {
        val (h, w) = OcrEngine.detResizeDims(1000, 3000)!!
        assertTrue(h % 32 == 0 && w % 32 == 0)
        // 3000 -> 960 ; 1000 -> 960/3000*1000 = 320
        assertEquals(320, h)
        assertEquals(960, w)
    }

    @Test
    fun detResize_smallImageKept() {
        val (h, w) = OcrEngine.detResizeDims(100, 200)!!
        assertEquals(96, h) // round(100/32)*32
        assertEquals(192, w) // round(200/32)*32
    }

    // ---- orderPointsClockwise -------------------------------------------

    @Test
    fun orderPoints_clockwise() {
        // Feed a shuffled quad; expect TL,TR,BR,BL.
        val shuffled = floatArrayOf(
            30f, 0f,  // TR
            0f, 0f,   // TL
            0f, 10f,  // BL
            30f, 10f, // BR
        )
        val o = OcrEngine.orderPointsClockwise(shuffled)
        // TL
        assertEquals(0f, o[0], 0f); assertEquals(0f, o[1], 0f)
        // TR
        assertEquals(30f, o[2], 0f); assertEquals(0f, o[3], 0f)
        // BR
        assertEquals(30f, o[4], 0f); assertEquals(10f, o[5], 0f)
        // BL
        assertEquals(0f, o[6], 0f); assertEquals(10f, o[7], 0f)
    }

    // ---- sortedBoxes -----------------------------------------------------

    @Test
    fun sortedBoxes_topToBottomThenLeftToRight() {
        // Boxes keyed by (y,x).
        val a = floatArrayOf(100f, 200f, 0f, 0f, 0f, 0f, 0f, 0f) // y=200
        val b = floatArrayOf(0f, 100f, 0f, 0f, 0f, 0f, 0f, 0f)   // y=100 (top)
        val c = floatArrayOf(50f, 100f, 0f, 0f, 0f, 0f, 0f, 0f)  // y=100, x=50
        val out = with(OcrEngine) { listOf(a, b, c).sortedBoxes() }
        // First should be y=100; among the two y=100, the smaller x first.
        assertEquals(0f, out[0][0], 0f) // b (x=0)
        assertEquals(50f, out[1][0], 0f) // c
        assertEquals(200f, out[2][1], 0f) // a (y=200)
    }

    // ---- mapBoxBack ------------------------------------------------------

    @Test
    fun mapBoxBack_scalesAndRemovesPadding() {
        // work box at (10,10); scale 0.5; padTop 5; original 100x100.
        val work = floatArrayOf(10f, 10f, 20f, 10f, 20f, 20f, 10f, 20f)
        val out = OcrEngine.mapBoxBack(work, 0.5, 5, 100, 100)
        // x: 10*0.5 = 5 ; y: (10-5)*0.5 = 2.5
        assertEquals(5f, out[0], 0f)
        assertEquals(2.5f, out[1], 0f)
        // clipped to [0, origW-1]
        val far = floatArrayOf(0f, 0f, 1000f, 0f, 1000f, 1000f, 0f, 1000f)
        val o2 = OcrEngine.mapBoxBack(far, 1.0, 0, 100, 100)
        assertEquals(99f, o2[2], 0f)
        assertEquals(99f, o2[5], 0f)
    }
}
