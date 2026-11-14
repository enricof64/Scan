package io.github.enricof64.scan

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.enricof64.scan.ocr.OcrEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device OCR quality benchmark (P1: searchable PDF).
 *
 * The host pushes the corpus + the Python reference ground truth to
 * /data/local/tmp/openscan-ocr-eval:
 *   images/          corpus (f5-corpus + docs/eval/samples, .jpg)
 *   ground_truth.json { image: { lines: [ {text, box}, ... ] } }
 * produced by the standalone PP-OCRv5 reference (onnxruntime) — the same
 * models and pre/post recipe the Kotlin engine mirrors.
 *
 * Per image the benchmark runs the PRODUCTION engine and scores:
 *   - lineMatchF1: character-F1 ≥ 0.8 (case-insensitive) line pairing,
 *     precision/recall/F1 vs the reference lines;
 *   - median per-image ms.
 * Recognized text is also written per image for eyeballing. See
 * docs/EVALUATION.md.
 */
@RunWith(AndroidJUnit4::class)
class OcrEvalBenchmark {

    private val root = "/data/local/tmp/openscan-ocr-eval"

    @Test
    fun run() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val imgDir = File(root, "images")
        val gt = JSONObject(File(root, "ground_truth.json").readText())
        val outDir = File(context.filesDir, "ocr-eval").apply { deleteRecursively() }
        val txtDir = File(outDir, "text").apply { mkdirs() }

        val images = imgDir.listFiles().orEmpty().sortedBy { it.name }
        val results = JSONArray()
        val f1s = mutableListOf<Double>()
        val msList = mutableListOf<Long>()
        var linesCorrect = 0
        var linesTotal = 0

        for (img in images) {
            val name = img.name
            val gtObj = gt.optJSONObject(name)
            val gtLines = gtObj?.optJSONArray("lines") ?: JSONArray()

            val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
            val bmp = BitmapFactory.decodeFile(img.absolutePath, opts)
            val t0 = System.currentTimeMillis()
            val res = OcrEngine.recognize(context, bmp)
            val ms = System.currentTimeMillis() - t0
            bmp.recycle()

            if (res == null) {
                results.put(JSONObject().put("image", name).put("error", OcrEngine.lastError ?: "null-result"))
                continue
            }

            val devLines = res.lines.map { it.text }
            File(txtDir, name.removeSuffix(".jpg") + ".txt").writeText(res.plainText)
            msList += ms

            val (p, r, f1, matched) = lineF1(gtLines, devLines)
            f1s += f1
            linesCorrect += matched
            linesTotal += gtLines.length()
            val devText = JSONArray()
            for (l in devLines) devText.put(l)
            val rec = JSONObject().put("image", name).put("ms", ms).put("gtLines", gtLines.length())
            rec.put("devLines", devLines.size).put("precision", round4(p)).put("recall", round4(r))
            rec.put("f1", round4(f1)).put("devText", devText)
            results.put(rec)
        }

        val summary = JSONObject()
            .put("images", images.size)
            .put("medianMs", median(msList))
            .put("meanMs", msList.average().toLong())
            .put("meanF1", round4(f1s.average()))
            .put("minF1", round4(f1s.minOrNull() ?: 0.0))
            .put("linesCorrect", linesCorrect)
            .put("linesTotal", linesTotal)
            .put("lastError", OcrEngine.lastError)
        val report = JSONObject().put("summary", summary).put("images", results)
        File(outDir, "results.json").writeText(report.toString(2))
        println("OCR_EVAL " + summary.toString())
    }

    private fun round4(v: Double): Double = (v * 10000).toLong() / 10000.0

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        return s[s.size / 2]
    }

    /**
     * Greedy line pairing: each reference line matches the device line with
     * the best character F1 (≥ 0.8 = correct). Returns precision, recall, F1
     * and the matched count.
     */
    private fun lineF1(gt: JSONArray, dev: List<String>): Quadruple {
        val gtArr = (0 until gt.length()).map { gt.getString(it) }
        val devArr = dev.toMutableList()
        var matched = 0
        val used = BooleanArray(devArr.size)
        for (g in gtArr) {
            var best = -1
            var bestF = 0.0
            for (d in devArr.indices) {
                if (used[d]) continue
                val f = charF1(g, devArr[d])
                if (f > bestF) {
                    bestF = f
                    best = d
                }
            }
            if (best >= 0 && bestF >= 0.8) {
                used[best] = true
                matched++
            }
        }
        val r = if (gtArr.isEmpty()) 1.0 else matched.toDouble() / gtArr.size
        val p = if (devArr.isEmpty()) 1.0 else matched.toDouble() / devArr.size
        val f = if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
        return Quadruple(p, r, f, matched)
    }

    private fun charF1(a: String, b: String): Double {
        val ca = charFreq(a.lowercase())
        val cb = charFreq(b.lowercase())
        var tp = 0
        for ((c, n) in ca) tp += minOf(n, cb[c] ?: 0)
        val pa = if (tp == 0) 0.0 else tp.toDouble() / (ca.values.sum())
        val ra = if (tp == 0) 0.0 else tp.toDouble() / (cb.values.sum())
        return if (pa + ra == 0.0) 1.0 else 2 * pa * ra / (pa + ra)
    }

    private fun charFreq(s: String): Map<Char, Int> =
        s.filter { !it.isWhitespace() }.groupBy { it }.mapValues { it.value.size }

    private data class Quadruple(val p: Double, val r: Double, val f1: Double, val matched: Int)
}
