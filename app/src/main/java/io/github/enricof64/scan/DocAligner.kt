package io.github.enricof64.scan

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * DocAligner (DocsaidLab, Apache-2.0) — a learned corner detector, integrated
 * as a second candidate source for the evaluation benchmark (plan F3:
 * "DocAligner + Canny benchmark").
 *
 * Model: `docaligner-lcnet100.onnx` (4.8 MB, LCNet100, heatmap_reg), bundled
 * under `assets/docaligner` with its Apache-2.0 LICENSE and NOTICE.
 *
 * I/O contract (verified against the upstream repo's `heatmap_reg`
 * inference and a reference Python run on real photos):
 *  - in:  `img` — float32 `[1, 3, 256, 256]`, BGR, bilinear-resized to a
 *    256×256 square (aspect NOT preserved), pixel values / 255.
 *  - out: `heatmap` — float32 `[4, 128, 128]`: one corner heatmap per
 *    channel, in TL, TR, BR, BL order.
 *
 * Post-processing follows the upstream recipe: threshold at
 * [HEATMAP_THRESHOLD], take the centroid of the active region per channel,
 * map from 128-space to the source frame. The centroid of all active pixels
 * is used instead of the largest connected component's centroid — identical
 * on the clean single-blob corner heatmaps this model produces, and it keeps
 * the step pure-JVM and unit-testable.
 */
object DocAligner {

    /** Bundled model asset path (LCNet100, 4,767,987 bytes). */
    const val ASSET_MODEL = "assets/docaligner/docaligner-lcnet100.onnx"

    /** Model input edge length (square, no aspect preservation). */
    const val INPUT_SIZE = 256

    /** Model output heatmap edge length (half the input). */
    const val HEATMAP_SIZE = 128

    /** Upstream corner-activation threshold. */
    const val HEATMAP_THRESHOLD = 0.3f

    private class Holder(val environment: OrtEnvironment, val session: OrtSession)

    private var holder: Holder? = null

    /**
     * TL, TR, BR, BL corner coordinates in source-bitmap space, or null when
     * the model is unavailable or no document corner is found. Never throws:
     * runtime failures (native load, OOM) are swallowed and reported as null,
     * so callers can fall back to the Canny detector.
     */
    fun detectQuad(context: Context, src: Bitmap): FloatArray? {
        val heatmaps = heatmap(context, src) ?: return null
        return cornersFromHeatmaps(heatmaps, src.width, src.height)
    }

    /**
     * Runs the model on [src] and returns the raw `[4][128][128]` heatmaps in
     * row-major order (channel-first), or null on any failure.
     */
    fun heatmap(context: Context, src: Bitmap): FloatArray? {
        val h = ensureSession(context) ?: return null
        val rgba = Mat(src.height, src.width, CvType.CV_8UC4)
        val bgr = Mat()
        val small = Mat()
        try {
            val pixels = IntArray(src.width * src.height)
            src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
            rgba.put(0, 0, pixels)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.resize(bgr, small, Size(INPUT_SIZE.toDouble(), INPUT_SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

            val chw = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
            val raw = ByteArray(3 * INPUT_SIZE * INPUT_SIZE)
            small.get(0, 0, raw)
            for (c in 0 until 3) {
                val cOff = c * INPUT_SIZE * INPUT_SIZE
                for (y in 0 until INPUT_SIZE) {
                    val row = y * INPUT_SIZE
                    for (x in 0 until INPUT_SIZE) {
                        chw[cOff + row + x] = raw[(row + x) * 3 + c].toInt().and(0xFF) / 255f
                    }
                }
            }

            val input = OnnxTensor.createTensor(
                h.environment,
                FloatBuffer.wrap(chw),
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
            )
            val result = h.session.run(mapOf("img" to input))
            val out = result.get(0) as ai.onnxruntime.OnnxTensor
            val heatmaps = FloatArray(4 * HEATMAP_SIZE * HEATMAP_SIZE)
            val fb = out.getFloatBuffer()
            fb.position(0)
            for (i in heatmaps.indices) heatmaps[i] = fb.get()
            result.close()
            return heatmaps
        } catch (t: Throwable) {
            return null
        } finally {
            rgba.release()
            bgr.release()
            small.release()
        }
    }

    private fun ensureSession(context: Context): Holder? {
        holder?.let { return it }
        return try {
            val environment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            val bytes = context.assets.open(ASSET_MODEL).use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            val session = environment.createSession(ByteBuffer.wrap(bytes), options)
            Holder(environment, session).also { holder = it }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Pure post-processing (unit-testable): four row-major `[128][128]`
     * heatmaps (channel order TL, TR, BR, BL) → TL/TR/BR/BL points in
     * [srcW]×[srcH] space, or null when any corner is absent (no region
     * above threshold, or a peak below 0.05 — a flat channel).
     */
    fun cornersFromHeatmaps(heatmaps: FloatArray, srcW: Int, srcH: Int): FloatArray? {
        val quad = FloatArray(8)
        for (c in 0 until 4) {
            val off = c * HEATMAP_SIZE * HEATMAP_SIZE
            var sumX = 0.0
            var sumY = 0.0
            var n = 0
            var peak = -1f
            var peakIdx = -1
            for (i in 0 until HEATMAP_SIZE * HEATMAP_SIZE) {
                val v = heatmaps[off + i]
                if (v > peak) {
                    peak = v
                    peakIdx = i
                }
                if (v >= HEATMAP_THRESHOLD) {
                    sumX += (i % HEATMAP_SIZE).toDouble()
                    sumY += (i / HEATMAP_SIZE).toDouble()
                    n++
                }
            }
            when {
                n > 0 -> {
                    quad[c * 2] = ((sumX / n) * srcW / HEATMAP_SIZE).toFloat()
                    quad[c * 2 + 1] = ((sumY / n) * srcH / HEATMAP_SIZE).toFloat()
                }
                peak >= 0.05f -> {
                    // No full region, but a clear peak: trust the argmax.
                    quad[c * 2] = ((peakIdx % HEATMAP_SIZE).toDouble() * srcW / HEATMAP_SIZE).toFloat()
                    quad[c * 2 + 1] = ((peakIdx / HEATMAP_SIZE).toDouble() * srcH / HEATMAP_SIZE).toFloat()
                }
                else -> return null
            }
        }
        return quad
    }
}
