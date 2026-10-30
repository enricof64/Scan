package io.github.enricof64.scan

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.graphics.Rect
import android.util.SizeF
import android.hardware.camera2.params.StreamConfigurationMap
import android.graphics.ImageFormat
import android.os.Build
import android.util.Size
import java.util.Locale

/**
 * On-device camera diagnostics for the 50 MP check (fork phase F0).
 *
 * Answers, per camera, whether the sensor's full-resolution mode is
 * reachable:
 *
 *  - "default JPEG" — the largest JPEG stream configuration in the
 *    standard SCALER_STREAM_CONFIGURATION_MAP. This is all CameraX can
 *    select: CameraX deliberately does not expose the ultra-high
 *    resolutions that live only in the maximum-resolution map (CameraX
 *    ResolutionSelector: "This mode does not allow applications to select
 *    those ultra high resolutions").
 *  - "max-res JPEG" — the largest JPEG in SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
 *    (Android 12+). Reaching it requires a direct Camera2 session with
 *    CaptureRequest.SENSOR_PIXEL_MODE = SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
 *    (Plan B of the 50 MP check).
 *  - "ultra-high-resolution sensor" — REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
 *    (Android 14+).
 *
 * Reading camera characteristics needs no permission — but each read is a
 * binder call into cameraserver (see [collect]), so never call this on the
 * main thread.
 */
object CameraDiagnostics {

    data class CameraReport(
        val id: String,
        val facing: String,
        val lensFacingLabel: String,
        val defaultActiveArray: String,
        val defaultMaxJpeg: String,
        val maxResAvailable: Boolean,
        val maxResActiveArray: String?,
        val maxResMaxJpeg: String?,
        val ultraHighResolutionSensor: Boolean,
        val focalLengthsMm: String,
        val sensorSizeMm: String,
        val error: String? = null,
    )

    /**
     * Probe every camera. Every call here is a binder IPC into cameraserver
     * and the first one can block for seconds on a cold HAL, so callers must
     * run this on a background dispatcher. One failing camera becomes an
     * [CameraReport.error] line instead of killing the whole dump.
     */
    fun collect(context: Context): List<CameraReport> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            manager.cameraIdList.map { id ->
                runCatching { reportFor(manager, id) }.getOrElse { e ->
                    CameraReport(
                        id = id, facing = "n/a", lensFacingLabel = "n/a",
                        defaultActiveArray = "n/a", defaultMaxJpeg = "n/a",
                        maxResAvailable = false, maxResActiveArray = null, maxResMaxJpeg = null,
                        ultraHighResolutionSensor = false, focalLengthsMm = "n/a",
                        sensorSizeMm = "n/a",
                        error = "${e::class.java.simpleName}: ${e.message ?: "probe failed"}",
                    )
                }
            }
        } catch (e: Exception) {
            listOf(
                CameraReport(
                    id = "?", facing = "n/a", lensFacingLabel = "n/a",
                    defaultActiveArray = "n/a", defaultMaxJpeg = "n/a",
                    maxResAvailable = false, maxResActiveArray = null, maxResMaxJpeg = null,
                    ultraHighResolutionSensor = false, focalLengthsMm = "n/a",
                    sensorSizeMm = "n/a",
                    error = "cameraIdList: ${e::class.java.simpleName}: ${e.message ?: ""}",
                )
            )
        }
    }

    private fun reportFor(manager: CameraManager, id: String): CameraReport {
        val ch = manager.getCameraCharacteristics(id)

        val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> 1
            CameraCharacteristics.LENS_FACING_FRONT -> 0
            else -> 2
        }
        val facingLabel = when (facing) {
            0 -> "front"
            1 -> "back"
            else -> "external"
        }

        val defaultActive: Rect? = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val defaultMap: StreamConfigurationMap? = ch.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )
        val defaultMaxJpeg = largestJpeg(defaultMap)

        var maxResActiveArray: Rect? = null
        var maxResJpeg: Size? = null
        var maxResAvailable = false
        if (Build.VERSION.SDK_INT >= 31) {
            val maxResMap: StreamConfigurationMap? = ch.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
            )
            if (maxResMap != null) {
                maxResAvailable = true
                maxResActiveArray = ch.get(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
                )
                maxResJpeg = largestJpeg(maxResMap)
            }
        }

        val ultraHigh = if (Build.VERSION.SDK_INT >= 34) {
            val caps: IntArray? = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            caps?.contains(
                CaptureRequest.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
            ) == true
        } else {
            false
        }

        val focal: FloatArray? = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensor: SizeF? = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

        return CameraReport(
            id = id,
            facing = facing.toString(),
            lensFacingLabel = facingLabel,
            defaultActiveArray = rectLabel(defaultActive),
            defaultMaxJpeg = sizeLabel(defaultMaxJpeg),
            maxResAvailable = maxResAvailable,
            maxResActiveArray = rectLabel(maxResActiveArray),
            maxResMaxJpeg = sizeLabel(maxResJpeg),
            ultraHighResolutionSensor = ultraHigh,
            focalLengthsMm = if (focal == null) "n/a"
            else focal.joinToString { formatMm(it) },
            sensorSizeMm = if (sensor == null) "n/a"
            else "${formatMm(sensor.width)} x ${formatMm(sensor.height)}",
        )
    }

    private fun largestJpeg(map: StreamConfigurationMap?): Size? =
        map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }

    private fun sizeLabel(size: Size?): String =
        if (size == null) "n/a" else "${size.width}x${size.height} (${formatMp(size)})"

    private fun rectLabel(rect: Rect?): String =
        if (rect == null) "n/a"
        else "${rect.width()}x${rect.height()} (${formatMp(Size(rect.width(), rect.height()))})"

    private fun formatMp(size: Size): String =
        String.format(Locale.US, "%.1f MP", size.width.toLong() * size.height / 1_000_000.0)

    private fun formatMm(value: Float): String =
        String.format(Locale.US, "%.1f", value)

    private fun StringBuilder.field(label: String): StringBuilder {
        append("  ")
        append((label + ":").padEnd(33))
        return this
    }

    /** Human-readable dump (clipboard / logcat). */
    fun toText(reports: List<CameraReport>): String = buildString {
        append("Camera diagnostics (Android ")
            .append(Build.VERSION.SDK_INT)
            .append(", device ")
            .append(Build.MANUFACTURER)
            .append(' ')
            .append(Build.MODEL)
            .append(")\n")
        for (r in reports) {
            append("Camera ").append(r.id).append(" (").append(r.lensFacingLabel).append(")\n")
            if (r.error != null) {
                append(field("error")).append(r.error).append("\n\n")
                continue
            }
            append(field("active array (default mode)"))
                .append(r.defaultActiveArray).append('\n')
            append(field("max JPEG via default map"))
                .append(r.defaultMaxJpeg).append('\n')
            append(field("max-res mode available (API 31)"))
                .append(if (r.maxResAvailable) "yes" else "no").append('\n')
            if (r.maxResAvailable) {
                append(field("active array (max-res mode)"))
                    .append(r.maxResActiveArray ?: "n/a").append('\n')
                append(field("max JPEG via max-res map"))
                    .append(r.maxResMaxJpeg ?: "n/a").append('\n')
            }
            append(field("ULTRA_HIGH_RESOLUTION_SENSOR"))
                .append(if (r.ultraHighResolutionSensor) "yes" else "no").append('\n')
            append(field("focal length (mm)"))
                .append(r.focalLengthsMm).append('\n')
            append(field("sensor size (mm)"))
                .append(r.sensorSizeMm).append("\n\n")
        }
        append(
            "Note: CameraX can only select sizes from the default map. A larger " +
                "max-res JPEG than the default JPEG means the full resolution " +
                "needs a direct Camera2 session with SENSOR_PIXEL_MODE=" +
                "SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION (Plan B)."
        )
    }
}
