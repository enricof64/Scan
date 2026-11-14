package io.github.enricof64.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.enricof64.scan.PdfExporter
import io.github.enricof64.scan.ScanPipeline
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Corner-detection + PDF-quality benchmark over a corpus of real document
 * photos pushed by the host to /data/local/tmp/openscan-eval (index.json
 * lists the images, their document grouping and, for the MIDV500 subset,
 * the ground-truth corner quad).
 *
 * The harness measures the PRODUCTION pipeline geometry
 * ([ScanPipeline.detectQuad] → [ScanPipeline.crop] → the app's real
 * [PdfExporter.exportPdf] for every document group) and, where ground truth
 * is available, the quad IoU in normalized space. It also runs the
 * DocAligner learned detector ([DocAligner.detectQuad]) on every image as a
 * second candidate source, so the two can be compared per scene from the
 * same run (F3). For the F5 detector verdict it also measures the COMBINED
 * learned-first strategy (`lf*` fields: learned detector with the Canny
 * fallback, exactly what the app runs when the Settings toggle is on —
 * unconditional here, since this run IS the verdict about it) and carries
 * per-strategy latency medians plus the plan's targets and a
 * `meetsTargets` boolean per strategy, so the verdict is read straight out
 * of the summary. Per-image overlays, crops, PDFs and a results.json land
 * in filesDir/eval for the host to pull. See docs/EVALUATION.md (F5).
 */
@RunWith(AndroidJUnit4::class)
class DocumentEvalBenchmark {

    private val indexFile = "/data/local/tmp/openscan-eval/index.json"
    private val maxImages = 120

    @Test
    fun runBenchmark() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val index = JSONObject(File(indexFile).readText())
        val images = index.getJSONArray("images")

        val outDir = File(context.filesDir, "eval").apply { deleteRecursively() }
        val cropsDir = File(outDir, "crops").apply { mkdirs() }
        val overlaysDir = File(outDir, "overlays").apply { mkdirs() }
        val pdfsDir = File(outDir, "pdfs").apply { mkdirs() }
        val poolsDir = File(outDir, "pools").apply { mkdirs() }

        // F5: guarantee the learned-first adapter is in place (OpenScanApp
        // sets the same one; the benchmark must not depend on the process
        // bootstrap).
        ScanPipeline.learnedDetector = { bmp -> DocAligner.detectQuad(context, bmp) }

        val results = JSONArray()
        val pagesByDoc = LinkedHashMap<String, MutableList<File>>()
        val ious = mutableListOf<Double>()
        var detectedCount = 0
        val daIous = mutableListOf<Double>()
        var daDetectedCount = 0
        val lfIous = mutableListOf<Double>()
        var lfDetectedCount = 0
        val detectMsList = mutableListOf<Long>()
        val daMsList = mutableListOf<Long>()
        val lfMsList = mutableListOf<Long>()
        val tAll = System.currentTimeMillis()

        val n = min(images.length(), maxImages)
        for (i in 0 until n) {
            val entry = images.getJSONObject(i)
            val name = entry.getString("file")
            val doc = entry.optString("doc", "single")
            val record = JSONObject().put("file", name).put("doc", doc)
            ScanPipeline.quadDebugSink = { pool ->
                File(poolsDir, "$name.pool.txt").writeText(pool.joinToString("\n") { it.toString() })
            }
            try {
                val decoded = decode(File("/data/local/tmp/openscan-eval/images", name), 2200)
                    ?: error("undecodable image")
                val src = decoded.bitmap
                record.put("srcW", src.width).put("srcH", src.height)
                val gt = entry.optJSONArray("gt_quad")
                record.put("scene", entry.optString("scene", ""))

                // DocAligner as a second candidate source (plan F3).
                // Diagnostics (F5): when the model returns no quad, the
                // per-channel heatmap peaks say whether the model was flat
                // (low confidence) or the pipeline dropped a valid quad.
                val daT0 = System.currentTimeMillis()
                val daHm = DocAligner.heatmap(context, src)
                val daQuad = daHm?.let { DocAligner.cornersFromHeatmaps(it, src.width, src.height) }
                record.put("daMs", System.currentTimeMillis() - daT0)
                if (daQuad == null && daHm != null) {
                    val peaks = (0 until 4).map { c ->
                        String.format("%.3f", daHm.copyOfRange(c * 128 * 128, (c + 1) * 128 * 128).max())
                    }
                    record.put("daPeaks", JSONArray(peaks))
                }
                if (daQuad == null && daHm == null) record.put("daError", DocAligner.lastError ?: "heatmap-null")
                daMsList.add(record.getLong("daMs"))
                if (daQuad == null) {
                    record.put("daOk", false)
                } else {
                    record.put("daOk", true)
                    daDetectedCount++
                    record.put("daQuad", JSONArray(daQuad.map { it.roundToInt() }))
                    if (gt != null && gt.length() == 8) {
                        val daPts = (0 until 4).map {
                            (daQuad[it * 2].toDouble() / src.width) to (daQuad[it * 2 + 1].toDouble() / src.height)
                        }
                        val gtPts = (0 until 4).map {
                            (gt.getDouble(it * 2) / decoded.originalWidth) to (gt.getDouble(it * 2 + 1) / decoded.originalHeight)
                        }
                        val daIou = quadIou(daPts, gtPts)
                        daIous.add(daIou)
                        record.put("daIou", daIou)
                    }
                }

                // F5 combined strategy: learned-first with the Canny fallback
                // (what the app runs when the Settings toggle is on).
                val lfT0 = System.currentTimeMillis()
                val lfQuad = ScanPipeline.detectQuad(src, learnedFirst = true)
                record.put("lfMs", System.currentTimeMillis() - lfT0)
                lfMsList.add(record.getLong("lfMs"))
                if (lfQuad == null) {
                    record.put("lfOk", false)
                } else {
                    record.put("lfOk", true)
                    lfDetectedCount++
                    record.put("lfQuad", JSONArray(lfQuad.map { it.roundToInt() }))
                    saveJpeg(drawOverlay(src, lfQuad), File(overlaysDir, "lf_$name"))
                    if (gt != null && gt.length() == 8) {
                        val lfPts = (0 until 4).map {
                            (lfQuad[it * 2].toDouble() / src.width) to (lfQuad[it * 2 + 1].toDouble() / src.height)
                        }
                        val gtPts = (0 until 4).map {
                            (gt.getDouble(it * 2) / decoded.originalWidth) to (gt.getDouble(it * 2 + 1) / decoded.originalHeight)
                        }
                        val lfIou = quadIou(lfPts, gtPts)
                        lfIous.add(lfIou)
                        record.put("lfIou", lfIou)
                    }
                }

                val t0 = System.currentTimeMillis()
                val quad = ScanPipeline.detectQuad(src)
                record.put("detectMs", System.currentTimeMillis() - t0)
                detectMsList.add(record.getLong("detectMs"))
                if (quad == null) {
                    record.put("ok", false).put("error", "no quad found")
                    if (gt != null && gt.length() == 8) record.put("iou", 0.0)
                } else {
                    record.put("ok", true)
                    detectedCount++
                    record.put("quad", JSONArray(quad.map { it.roundToInt() }))
                    if (gt != null && gt.length() == 8) {
                        val detPts = (0 until 4).map {
                            (quad[it * 2].toDouble() / src.width) to (quad[it * 2 + 1].toDouble() / src.height)
                        }
                        val gtPts = (0 until 4).map {
                            (gt.getDouble(it * 2) / decoded.originalWidth) to (gt.getDouble(it * 2 + 1) / decoded.originalHeight)
                        }
                        val iou = quadIou(detPts, gtPts)
                        ious.add(iou)
                        record.put("iou", iou)
                    }
                    val crop = ScanPipeline.crop(src, quad)
                    val cropFile = File(cropsDir, "${name.substringBeforeLast('.')}_crop.jpg")
                    saveJpeg(crop, cropFile)
                    pagesByDoc.getOrPut(doc) { mutableListOf() }.add(cropFile)
                    saveJpeg(drawOverlay(src, quad), File(overlaysDir, name))
                    crop.recycle()
                }
                src.recycle()
            } catch (e: Exception) {
                record.put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            results.put(record)
        }

        // One PDF per document group through the app's real exporter.
        val pdfs = JSONArray()
        pagesByDoc.forEach { (doc, pages) ->
            try {
                val pdfFile = File(pdfsDir, "$doc.pdf")
                FileOutputStream(pdfFile).use { PdfExporter.exportPdf(pages, it) }
                pdfs.put(JSONObject().put("doc", doc).put("pages", pages.size)
                    .put("file", pdfFile.name).put("bytes", pdfFile.length()))
            } catch (e: Exception) {
                pdfs.put(JSONObject().put("doc", doc).put("error", e.message))
            }
        }

        val summary = JSONObject()
            .put("total", n)
            .put("detected", detectedCount)
            .put("detectionRate", if (n > 0) detectedCount.toDouble() / n else 0.0)
            .put("withGroundTruth", ious.size)
        val cannyIousSorted = ious.sorted()
        val cannyIouMedian: Double? =
            cannyIousSorted.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.50) }
        if (cannyIousSorted.isNotEmpty()) {
            summary.put("iouMedian", cannyIouMedian)
                .put("iouP90", percentile(cannyIousSorted, 0.90))
                .put("iouAbove09", cannyIousSorted.count { it >= 0.9 })
        }
        summary.put("daDetected", daDetectedCount)
            .put("daDetectionRate", if (n > 0) daDetectedCount.toDouble() / n else 0.0)
        val daIousSorted = daIous.sorted()
        val daIouMedian: Double? =
            daIousSorted.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.50) }
        if (daIousSorted.isNotEmpty()) {
            summary.put("daIouMedian", daIouMedian)
                .put("daIouP90", percentile(daIousSorted, 0.90))
                .put("daIouAbove09", daIousSorted.count { it >= 0.9 })
        }
        // F5 combined (learned-first with Canny fallback) strategy.
        summary.put("lfDetected", lfDetectedCount)
            .put("lfDetectionRate", if (n > 0) lfDetectedCount.toDouble() / n else 0.0)
        val lfIousSorted = lfIous.sorted()
        val lfIouMedian: Double? =
            lfIousSorted.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.50) }
        if (lfIousSorted.isNotEmpty()) {
            summary.put("lfIouMedian", lfIouMedian)
                .put("lfIouP90", percentile(lfIousSorted, 0.90))
                .put("lfIouAbove09", lfIousSorted.count { it >= 0.9 })
        }
        // Per-strategy latency (median ms over the run corpus).
        summary.put("detectMsMedian", medianOf(detectMsList))
            .put("daMsMedian", medianOf(daMsList))
            .put("lfMsMedian", medianOf(lfMsList))
        // F5 verdict (plan targets): detection rate >= 95 % and corner IoU
        // median >= 0.9. The third target (manual corrections <= 5 %) needs
        // a user, so it is checked in the review grid, not here. `meets`
        // flags the strategy that becomes the production default.
        val targets = JSONObject()
            .put("detectionRateMin", 0.95)
            .put("iouMedianMin", 0.9)
        fun meets(detectionRate: Double, iouMedian: Double?): Boolean =
            iouMedian != null &&
                detectionRate >= targets.getDouble("detectionRateMin") &&
                iouMedian >= targets.getDouble("iouMedianMin")
        summary.put("targets", targets)
            .put("meets", meets(summary.getDouble("detectionRate"), cannyIouMedian))
            .put("daMeets", meets(summary.getDouble("daDetectionRate"), daIouMedian))
            .put("lfMeets", meets(summary.getDouble("lfDetectionRate"), lfIouMedian))
        File(outDir, "results.json").writeText(
            JSONObject()
                .put("images", results)
                .put("summary", summary)
                .put("pdfs", pdfs)
                .put("totalMs", System.currentTimeMillis() - tAll)
                .toString()
        )
        ScanPipeline.quadDebugSink = null
    }

    private data class Decoded(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)

    private fun decode(source: File, maxDim: Int): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / 2 >= maxDim) { sample *= 2; longest /= 2 }
        val bmp = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return Decoded(bmp, bounds.outWidth, bounds.outHeight)
    }

    /** Median (lower-middle for even counts) of latency samples, 0 when empty. */
    private fun medianOf(samples: List<Long>): Long {
        if (samples.isEmpty()) return 0
        return samples.sorted()[(samples.size - 1) / 2]
    }

    /** Percentile by rank on an ascending-sorted list. */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        return sorted[(sorted.size * p).toInt().coerceIn(0, sorted.size - 1)]
    }

    // ---- Ground-truth comparison -----------------------------------------

    /**
     * Quad IoU of two cyclic point lists in the same (normalized) space,
     * delegated to the unit-tested [QuadIou] (F5: the inlined Sutherland–
     * Hodgman port dropped the entering point on an outside→inside
     * transition and collapsed near-identical quads to a zero-area sliver).
     */
    private fun quadIou(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>): Double {
        val fa = FloatArray(8) { a[it / 2].let { p -> if (it % 2 == 0) p.first.toFloat() else p.second.toFloat() } }
        val fb = FloatArray(8) { b[it / 2].let { p -> if (it % 2 == 0) p.first.toFloat() else p.second.toFloat() } }
        return QuadIou.iou(fa, fb)
    }

    private fun saveJpeg(bitmap: Bitmap, dest: File) {
        FileOutputStream(dest).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }

    private fun drawOverlay(src: Bitmap, quad: FloatArray): Bitmap {
        val scale = 1024f / maxOf(src.width, src.height)
        val small = Bitmap.createScaledBitmap(
            src, (src.width * scale).roundToInt(), (src.height * scale).roundToInt(), true
        ).let { scaled ->
            // A same-size resize returns the immutable decoded source.
            if (scaled === src) scaled.copy(Bitmap.Config.ARGB_8888, true) else scaled
        }
        val canvas = Canvas(small)
        val line = Paint().apply { color = Color.GREEN; strokeWidth = 5f; style = Paint.Style.STROKE }
        val dot = Paint().apply { color = Color.RED; style = Paint.Style.FILL }
        val ordered = quad.toList().chunked(2).map { (x, y) -> x * scale to y * scale }
        ordered.zip(ordered.drop(1) + ordered.take(1)).forEach { (a, b) ->
            canvas.drawLine(a.first, a.second, b.first, b.second, line)
        }
        ordered.forEach { (x, y) -> canvas.drawCircle(x, y, 9f, dot) }
        return small
    }
}

