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
import kotlin.math.abs
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
 * same run. Per-image overlays, crops, PDFs and a results.json (with a
 * summary block: detection rate, IoU median/p90, share >= 0.9, plus the
 * DocAligner equivalents) land in filesDir/eval for the host to pull.
 * See docs/EVALUATION.md.
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

        val results = JSONArray()
        val pagesByDoc = LinkedHashMap<String, MutableList<File>>()
        val ious = mutableListOf<Double>()
        var detectedCount = 0
        val daIous = mutableListOf<Double>()
        var daDetectedCount = 0
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
                val daT0 = System.currentTimeMillis()
                val daQuad = DocAligner.detectQuad(context, src)
                record.put("daMs", System.currentTimeMillis() - daT0)
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

                val t0 = System.currentTimeMillis()
                val quad = ScanPipeline.detectQuad(src)
                record.put("detectMs", System.currentTimeMillis() - t0)
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
        if (ious.isNotEmpty()) {
            val sorted = ious.sorted()
            summary.put("iouMedian", percentile(sorted, 0.50))
                .put("iouP90", percentile(sorted, 0.90))
                .put("iouAbove09", sorted.count { it >= 0.9 })
        }
        summary.put("daDetected", daDetectedCount)
            .put("daDetectionRate", if (n > 0) daDetectedCount.toDouble() / n else 0.0)
        if (daIous.isNotEmpty()) {
            val sorted = daIous.sorted()
            summary.put("daIouMedian", percentile(sorted, 0.50))
                .put("daIouP90", percentile(sorted, 0.90))
                .put("daIouAbove09", sorted.count { it >= 0.9 })
        }
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

    /** Percentile by rank on an ascending-sorted list. */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        return sorted[(sorted.size * p).toInt().coerceIn(0, sorted.size - 1)]
    }

    // ---- Ground-truth comparison: quad IoU (convex, normalized space) ---

    /**
     * Intersection-over-union of two convex quads given as ordered
     * (cyclic) point pairs in the same normalized space.
     * Sutherland–Hodgman clipping; orientation is normalized to CCW first.
     */
    private fun quadIou(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>): Double {
        val areaA = polyArea(a)
        val areaB = polyArea(b)
        if (areaA <= 1e-9 || areaB <= 1e-9) return 0.0
        val areaI = polyArea(convexClip(a, b))
        if (areaI <= 1e-9) return 0.0
        return areaI / (areaA + areaB - areaI)
    }

    /** Shoelace formula, absolute area. */
    private fun polyArea(pts: List<Pair<Double, Double>>): Double =
        abs(signedArea(pts))

    private fun signedArea(pts: List<Pair<Double, Double>>): Double {
        var s = 0.0
        for (i in pts.indices) {
            val (x1, y1) = pts[i]
            val (x2, y2) = pts[(i + 1) % pts.size]
            s += x1 * y2 - x2 * y1
        }
        return s / 2.0
    }

    private fun ensureCCW(pts: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        if (signedArea(pts) < 0) pts.reversed() else pts

    private fun convexClip(subjectIn: List<Pair<Double, Double>>, clipIn: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        var output = ensureCCW(subjectIn)
        val clip = ensureCCW(clipIn)
        for (e in clip.indices) {
            val p1 = clip[e]
            val p2 = clip[(e + 1) % clip.size]
            val input = output
            if (input.isEmpty()) break
            output = mutableListOf()
            for (i in input.indices) {
                val cur = input[i]
                val prev = input[(i + input.size - 1) % input.size]
                val curIn = side(p1, p2, cur) >= 0
                val prevIn = side(p1, p2, prev) >= 0
                when {
                    curIn && prevIn -> output.add(cur)
                    curIn != prevIn -> output.add(lineLine(prev, cur, p1, p2))
                }
            }
        }
        return output
    }

    /** Cross product of (p2-p1) and (p-p1); >= 0 means p is left of p1->p2. */
    private fun side(p1: Pair<Double, Double>, p2: Pair<Double, Double>, p: Pair<Double, Double>): Double =
        (p2.first - p1.first) * (p.second - p1.second) - (p2.second - p1.second) * (p.first - p1.first)

    /** Intersection of segments a1-a2 and b1-b2 (falls back to a2 if parallel). */
    private fun lineLine(
        a1: Pair<Double, Double>, a2: Pair<Double, Double>,
        b1: Pair<Double, Double>, b2: Pair<Double, Double>,
    ): Pair<Double, Double> {
        val d1x = a2.first - a1.first
        val d1y = a2.second - a1.second
        val d2x = b2.first - b1.first
        val d2y = b2.second - b1.second
        val denom = d1x * d2y - d1y * d2x
        if (abs(denom) < 1e-12) return a2
        val t = ((b1.first - a1.first) * d2y - (b1.second - a1.second) * d2x) / denom
        return (a1.first + t * d1x) to (a1.second + t * d1y)
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

