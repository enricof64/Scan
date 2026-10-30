package io.github.enricof64.scan

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap

/**
 * One back-lens candidate for capture selection (F2, plan §7 "selezione
 * esplicita della lente").
 */
data class LensCandidate(
    val id: String,
    /**
     * Pixel count of the largest JPEG in the default stream map — the
     * ceiling CameraX can actually select (see CameraDiagnostics for why
     * the max-res map is out of reach for this app).
     */
    val maxJpegPixels: Long,
)

/**
 * Picks which back lens to capture with.
 *
 * `CameraSelector.DEFAULT_BACK_CAMERA` lets the platform choose, which on
 * multi-lens phones is not guaranteed to be the highest-resolution back
 * lens (on the Fold 8 the default is not the 4080×3060 main). Enumerating
 * the back lenses ourselves and taking the one with the largest
 * default-map JPEG gives the biggest still the camera can deliver without
 * a direct Camera2 session.
 */
object LensPicker {

    /**
     * The back lens with the largest default-map JPEG. Ties go to the
     * lexicographically lowest camera id (on Android that is the primary
     * wide lens). Returns null when no back lens reports a usable JPEG
     * size — callers then fall back to [androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA].
     */
    fun pickBackLens(candidates: List<LensCandidate>): LensCandidate? {
        val backs = candidates.filter { it.maxJpegPixels > 0 }
        if (backs.isEmpty()) return null
        return backs.minWith(
            compareByDescending<LensCandidate> { it.maxJpegPixels }.thenBy { it.id },
        )
    }

    /**
     * Enumerate the back lenses and their largest default-map JPEG.
     *
     * Every call here is a binder IPC into cameraserver (see
     * CameraDiagnostics), so run this on a background dispatcher, never on
     * the main thread. A failing camera is skipped, not fatal.
     */
    fun backLensCandidates(context: Context): List<LensCandidate> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            manager.cameraIdList.mapNotNull { id ->
                runCatching {
                    val ch = manager.getCameraCharacteristics(id)
                    if (ch.get(CameraCharacteristics.LENS_FACING) !=
                        CameraCharacteristics.LENS_FACING_BACK
                    ) {
                        return@mapNotNull null
                    }
                    val map: StreamConfigurationMap? = ch.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                    )
                    val size = map?.getOutputSizes(ImageFormat.JPEG)
                        ?.maxByOrNull { it.width.toLong() * it.height }
                        ?: return@mapNotNull null
                    LensCandidate(id, size.width.toLong() * size.height)
                }.getOrNull()
            }
        } catch (t: Throwable) {
            // Throwable, not Exception: a binder OOM is an Error and must
            // degrade to "no candidates" (default selector), not a crash.
            emptyList()
        }
    }
}
