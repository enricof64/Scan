package io.github.enricof64.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LensPickerTest {

    private fun c(id: String, pixels: Long) = LensCandidate(id, pixels)

    @Test
    fun noCandidatesMeansNoLens() {
        assertNull(LensPicker.pickBackLens(emptyList()))
    }

    @Test
    fun candidatesWithoutJpegSizesAreIgnored() {
        assertNull(LensPicker.pickBackLens(listOf(c("0", 0), c("1", 0))))
    }

    @Test
    fun picksTheLargestBackLens() {
        // The collector only ever offers back lenses; here the main
        // (4080x3060) must beat the ultrawide (1920x1080) as on the Fold 8.
        val picked = LensPicker.pickBackLens(
            listOf(
                c("0", 12_500_000), // main 4080x3060
                c("2", 2_073_600), // ultrawide 1920x1080
            ),
        )
        assertEquals("0", picked?.id)
    }

    @Test
    fun tieGoesToTheLowestCameraId() {
        val picked = LensPicker.pickBackLens(
            listOf(c("2", 12_000_000), c("0", 12_000_000)),
        )
        assertEquals("0", picked?.id)
    }

    @Test
    fun singleCandidateWins() {
        val picked = LensPicker.pickBackLens(listOf(c("3", 4_000_000)))
        assertEquals("3", picked?.id)
    }
}
