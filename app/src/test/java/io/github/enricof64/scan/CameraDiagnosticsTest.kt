package io.github.enricof64.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDiagnosticsTest {

    private val report = CameraDiagnostics.CameraReport(
        id = "0",
        facing = "1",
        lensFacingLabel = "back",
        defaultActiveArray = "8192x6144 (50.3 MP)",
        defaultMaxJpeg = "8192x6144 (50.3 MP)",
        maxResAvailable = true,
        maxResActiveArray = "8192x6144 (50.3 MP)",
        maxResMaxJpeg = "10240x7680 (78.6 MP)",
        ultraHighResolutionSensor = true,
        focalLengthsMm = "5.6",
        sensorSizeMm = "7.6 x 5.7",
    )

    @Test
    fun toTextListsEachCameraAndThePlanBNote() {
        // The dump aligns columns with extra spaces; normalize before matching.
        val text = CameraDiagnostics.toText(listOf(report)).replace(Regex(" +"), " ")
        assertTrue(text.contains("Camera 0 (back)"))
        assertTrue(text.contains("max JPEG via default map"))
        assertTrue(text.contains("max-res mode available (API 31): yes"))
        assertTrue(text.contains("ULTRA_HIGH_RESOLUTION_SENSOR: yes"))
        assertTrue(text.contains("SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION"))
    }

    @Test
    fun toTextOmitsMaxResLinesWhenNotAvailable() {
        val noMaxRes = report.copy(
            maxResAvailable = false,
            maxResActiveArray = null,
            maxResMaxJpeg = null,
        )
        val text = CameraDiagnostics.toText(listOf(noMaxRes)).replace(Regex(" +"), " ")
        assertTrue(text.contains("max-res mode available (API 31): no"))
        assertFalse(text.contains("max JPEG via max-res map"))
    }
}
