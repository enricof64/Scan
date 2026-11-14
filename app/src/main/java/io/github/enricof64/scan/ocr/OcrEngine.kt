package io.github.enricof64.scan.ocr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc

/**
 * On-device OCR — PP-OCRv5 mobile (PaddlePaddle, Apache-2.0), text detection
 * (DB) + recognition (CRNN/CTC), both as ONNX models on the already-bundled
 * onnxruntime-android. No new native AAR: the 16 kB page-size constraint
 * (see docs/RELEASING.md) is untouched.
 *
 * Models under `assets/ocr`:
 *  - `ppocrv5_det.onnx` — in `x` [1,3,H,W] float32 (H,W multiples of 32, long
 *    side ≤ [DET_RESIZE_LONG]); out `fetch_name_0` [1,1,H',W'] probability map.
 *  - `ppocrv5_rec.onnx` — in `x` [N,3,48,W] float32; out `fetch_name_0`
 *    [N,T,18385] logits (18385 = 1 blank + 18383 dict chars + 1 space).
 *  - `rec_char_dict.txt` — the 18383-character dictionary, one per line.
 *
 * Pre/post-processing mirrors the upstream inference recipe (verified against
 * a Python reference implementation on the eval corpus — see
 * `docs/EVALUATION.md`), including the ImageNet-style det normalization and
 * the 0.5/0.5 rec normalization. The DB `unclip` polygon offset (upstream
 * pyclipper JT_ROUND) is realized as the exact Minkowski-sum equivalent for a
 * rectangle: inflate the minAreaRect by `d = area*UNCLIP_RATIO/perimeter` on
 * every side — the minAreaRect of a rect⊕disk(d) is precisely that.
 *
 * The text-direction classifier (cls) is deliberately not bundled: scanned
 * pages are deskewed before OCR and upside-down lines are rare enough that
 * the missing model is not worth 5 MB of assets.
 */
object OcrEngine {

    const val ASSET_DET = "ocr/ppocrv5_det.onnx"
    const val ASSET_REC = "ocr/ppocrv5_rec.onnx"
    const val ASSET_DICT = "ocr/rec_char_dict.txt"

    // Det (DB) — upstream PP-OCRv5 mobile inference config.
    const val DET_RESIZE_LONG = 960
    val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f) // BGR channel order
    val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    const val DB_THRESH = 0.3f
    const val BOX_THRESH = 0.6f
    const val UNCLIP_RATIO = 1.5f
    const val MAX_CANDIDATES = 1000

    // Global image prep (upstream RapidOCR defaults).
    const val WORK_MAX_SIDE = 2000
    const val WORK_MIN_SIDE = 30
    const val MAX_ASPECT = 8f

    // Rec (CRNN/CTC).
    const val REC_H = 48
    const val REC_W = 320
    const val REC_BATCH = 6
    const val TEXT_SCORE = 0.5f

    private class Engine(
        val env: OrtEnvironment,
        val det: OrtSession,
        val rec: OrtSession,
        val chars: Array<String>,
    )

    private var engine: Engine? = null

    /** Diagnostics only — last swallowed failure, if any. Never drives logic. */
    @Volatile
    var lastError: String? = null
        private set

    private fun stackOf(t: Throwable, n: Int): String =
        t.stackTraceToString().lineSequence().take(n).joinToString(" / ")

    @Synchronized
    private fun ensureEngine(context: Context): Engine? {
        engine?.let { return it }
        return try {
            val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            fun open(asset: String): OrtSession {
                val bytes = context.assets.open(asset).use { it.readBytes() }
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
                return env.createSession(bytes, opts)
            }
            val dict = context.assets.open(ASSET_DICT).use {
                it.bufferedReader().readLines().filter { l -> l.isNotEmpty() }.toTypedArray()
            }
            Engine(env, open(ASSET_DET), open(ASSET_REC), dict).also { engine = it }
        } catch (t: Throwable) {
            lastError = t.toString() + " | " + stackOf(t, 4)
            null
        }
    }

    /**
     * OCRs a page file (JPEG) — blocking; run on a background dispatcher.
     * Returns null on any failure (missing OpenCV, model load, OOM); see
     * [lastError] for diagnostics.
     */
    fun recognizeFile(context: Context, pageFile: File): OcrResult? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeFile(pageFile.absolutePath, opts)
            ?: return fail("decodeFile failed for ${pageFile.name}")
        try {
            return recognize(context, bmp)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * OCRs a page bitmap — blocking. [src] is the original page (filters and
     * deskew already baked in); all returned boxes are normalized to 0..1
     * in [src] space (corner x/y fractions of the page width/height), so
     * they map onto any re-decode of the same page.
     * Returns null on any failure; see [lastError].
     */
    @Synchronized
    fun recognize(context: Context, src: Bitmap): OcrResult? {
        if (!OpenCVLoader.initLocal()) return fail("OpenCVLoader.initLocal() failed")
        val e = ensureEngine(context) ?: return null
        val rgba = Mat()
        val bgr = Mat()
        var work: Mat? = null
        var small: Mat? = null
        return try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

            // 1) Global prep: cap the max side, raise the min side.
            val w0 = bgr.cols()
            val h0 = bgr.rows()
            var s = 1.0
            val big = maxOf(w0, h0)
            if (big > WORK_MAX_SIDE) {
                s = WORK_MAX_SIDE.toDouble() / big
                work = Mat()
                Imgproc.resize(bgr, work, Size((w0 * s).toInt().toDouble(), (h0 * s).toInt().toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            } else {
                work = bgr.clone()
            }
            var w = work.cols()
            var h = work.rows()
            val smallSide = minOf(w, h)
            if (smallSide < WORK_MIN_SIDE) {
                val s2 = WORK_MIN_SIDE.toDouble() / smallSide
                s *= s2
                val up = Mat()
                Imgproc.resize(work, up, Size((w * s2).toInt().toDouble(), (h * s2).toInt().toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                work.release()
                work = up
                w = up.cols()
                h = up.rows()
            }

            // 2) Letterbox very flat crops (w/h > MAX_ASPECT or h <= 30).
            var padTop = 0
            if (h <= WORK_MIN_SIDE || w / h.toDouble() > MAX_ASPECT) {
                val newH = maxOf(w.toDouble() / MAX_ASPECT, WORK_MIN_SIDE.toDouble()) * 2
                padTop = (kotlin.math.abs(newH - h) / 2).toInt()
                val padded = Mat()
                Core.copyMakeBorder(work, padded, padTop, padTop, 0, 0, Core.BORDER_REPLICATE)
                work.release()
                work = padded
                h = padded.rows()
            }

            val tDet = System.currentTimeMillis()

            // 3) Det input.
            val detDims = detResizeDims(h, w) ?: return fail("bad det dims")
            small = Mat()
            Imgproc.resize(work, small, Size(detDims.second.toDouble(), detDims.first.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val prob = runDet(e, small) ?: return fail("det run failed")
            val ph = prob.size / detDims.second
            val pw = detDims.second

            // 4) DB post-process → boxes in work space.
            val boxes = dbBoxes(prob, ph, pw, w, h)
            val detMs = System.currentTimeMillis() - tDet
            if (boxes.isEmpty()) {
                lastError = null
                return OcrResult(emptyList(), detMs, 0L)
            }
            val ordered = boxes.map { orderPointsClockwise(it) }
                .filter { box ->
                    val bw = Math.hypot((box[0] - box[2]).toDouble(), (box[1] - box[3]).toDouble())
                    val bh = Math.hypot((box[0] - box[6]).toDouble(), (box[1] - box[7]).toDouble())
                    bw > 3 && bh > 3
                }
                .sortedBoxes()

            val tRec = System.currentTimeMillis()
            // 5) Crops → rec (batched, aspect-sorted).
            val crops = ordered.map { cropLine(work!!, it) }
            val recLines = runRec(e, crops)
            val recMs = System.currentTimeMillis() - tRec

            // 6) Keep scored lines, map boxes back to src space, normalize to 0..1.
            val lines = ordered.mapIndexed { i, box ->
                val (text, score) = recLines[i]
                if (score < TEXT_SCORE) return@mapIndexed null
                OcrLine(
                    text = text,
                    score = score,
                    box = mapBoxBack(box, s, padTop, w0, h0)
                        .mapIndexed { k, v -> if (k % 2 == 0) v / w0 else v / h0 }
                        .toFloatArray(),
                )
            }.filterNotNull()
            lastError = null
            OcrResult(lines, detMs, recMs)
        } catch (t: Throwable) {
            fail(t.toString() + " | " + stackOf(t, 6))
        } finally {
            rgba.release()
            bgr.release()
            work?.release()
            small?.release()
        }
    }

    private fun fail(msg: String): OcrResult? {
        lastError = msg
        return null
    }

    // ------------------------------------------------------------------
    // Det
    // ------------------------------------------------------------------

    /** Resize dims (h, w) for the det model: long side ≤ 960, multiples of 32. */
    fun detResizeDims(h: Int, w: Int, limit: Int = DET_RESIZE_LONG): Pair<Int, Int>? {
        val ratio = if (maxOf(h, w) > limit) limit.toDouble() / maxOf(h, w) else 1.0
        val rh = (Math.round(h * ratio / 32.0) * 32).toInt()
        val rw = (Math.round(w * ratio / 32.0) * 32).toInt()
        if (rh <= 0 || rw <= 0) return null
        return rh to rw
    }

    private fun runDet(e: Engine, small: Mat): FloatArray? {
        val h = small.rows()
        val w = small.cols()
        val raw = ByteArray(h * w * 3)
        small.get(0, 0, raw)
        val buf = ByteBuffer.allocateDirect(h * w * 3 * 4).order(ByteOrder.nativeOrder())
        val n = h * w
        for (i in 0 until n) {
            // CHW; channel order BGR as loaded, normalized upstream-style.
            buf.putFloat(((raw[i * 3].toInt() and 0xFF) / 255f - DET_MEAN[0]) / DET_STD[0])
            buf.putFloat(((raw[i * 3 + 1].toInt() and 0xFF) / 255f - DET_MEAN[1]) / DET_STD[1])
            buf.putFloat(((raw[i * 3 + 2].toInt() and 0xFF) / 255f - DET_MEAN[2]) / DET_STD[2])
        }
        buf.flip()
        val input = OnnxTensor.createTensor(
            e.env,
            buf,
            longArrayOf(1L, 3L, h.toLong(), w.toLong()),
            OnnxJavaType.FLOAT,
        )
        try {
            val result = e.det.run(mapOf("x" to input))
            try {
                val out = result.get(0) as ai.onnxruntime.OnnxTensor
                val shape = out.getInfo().shape
                val nOut = shape.drop(2).fold(1L) { a, b -> a * b }
                val fb = out.getFloatBuffer()
                fb.position(0)
                val prob = FloatArray(nOut.toInt())
                for (i in prob.indices) prob[i] = fb.get()
                return prob
            } finally {
                result.close()
            }
        } finally {
            input.close()
        }
    }

    /**
     * DB post-process: threshold → dilate → contours → minAreaRect → score →
     * unclip → scale to work space. Returns boxes as `[x0,y0,...,x3,y3]`
     * (TL, TR, BR, BL), int coordinates in work-image pixels.
     */
    private fun dbBoxes(prob: FloatArray, ph: Int, pw: Int, dstW: Int, dstH: Int): List<FloatArray> {
        val mask = Mat(ph, pw, CvType.CV_8UC1)
        for (i in prob.indices) mask.put(i / pw, i % pw, (if (prob[i] > DB_THRESH) 1.0 else 0.0))
        val kernel = Mat.zeros(2, 2, CvType.CV_8UC1).apply {
            for (i in 0 until 4) put(0, i, 1.0)
        }
        val dilated = Mat()
        Imgproc.dilate(mask, dilated, kernel)
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(dilated, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val boxes = mutableListOf<FloatArray>()
        for (c in contours.take(MAX_CANDIDATES)) {
            val rect = Geometry.minAreaRect(MatOfPoint2f(*c.toArray()))
            val rw = rect.size.width
            val rh = rect.size.height
            val sside = minOf(rw, rh)
            if (sside < 3) continue
            val score = boxScoreFast(prob, ph, pw, rect)
            if (score < BOX_THRESH) continue
            // Unclip: exact Minkowski-sum offset for a rectangle.
            val d = rw * rh * UNCLIP_RATIO / (2 * (rw + rh))
            val infl = RotatedRect(rect.center, Size(rw + 2 * d, rh + 2 * d), rect.angle)
            if (minOf(rw, rh) + 2 * d < 5) continue
            val pts = boxPoints(infl)
            val box = FloatArray(8)
            for (i in 0 until 4) {
                box[i * 2] = Math.round(pts[i].x.toDouble() / pw * dstW).coerceIn(0L, dstW.toLong()).toFloat()
                box[i * 2 + 1] = Math.round(pts[i].y.toDouble() / ph * dstH).coerceIn(0L, dstH.toLong()).toFloat()
            }
            boxes.add(box)
        }
        mask.release()
        kernel.release()
        dilated.release()
        return boxes
    }

    private fun boxScoreFast(prob: FloatArray, ph: Int, pw: Int, rect: RotatedRect): Double {
        val pts = boxPoints(rect).map { Point(it.x, it.y) }
        val xmin = Math.floor(pts.minOf { it.x }).toInt().coerceIn(0, pw - 1)
        val xmax = Math.ceil(pts.maxOf { it.x }).toInt().coerceIn(0, pw - 1)
        val ymin = Math.floor(pts.minOf { it.y }).toInt().coerceIn(0, ph - 1)
        val ymax = Math.ceil(pts.maxOf { it.y }).toInt().coerceIn(0, ph - 1)
        if (xmax < xmin || ymax < ymin) return 0.0
        val mask = Mat(ymax - ymin + 1, xmax - xmin + 1, CvType.CV_8UC1)
        val shiftedPts = pts.map { Point((it.x - xmin).toInt().toDouble(), (it.y - ymin).toInt().toDouble()) }
        val shifted = MatOfPoint(*shiftedPts.toTypedArray())
        Imgproc.fillPoly(mask, listOf(shifted), Scalar(1.0))
        var sum = 0.0
        var n = 0
        val m = ByteArray((ymax - ymin + 1) * (xmax - xmin + 1))
        mask.get(0, 0, m)
        for (y in 0..(ymax - ymin)) {
            for (x in 0..(xmax - xmin)) {
                if (m[y * (xmax - xmin + 1) + x].toInt() != 0) {
                    sum += prob[(y + ymin) * pw + (x + xmin)]
                    n++
                }
            }
        }
        mask.release()
        return if (n == 0) 0.0 else sum / n
    }

    private fun boxPoints(rect: RotatedRect): List<Point> {
        val pts = MatOfPoint2f()
        Geometry.boxPoints(rect, pts)
        return (0 until 4).map { i ->
            val xy = pts.get(i, 0)
            Point(xy[0], xy[1])
        }
    }

    // ------------------------------------------------------------------
    // Crops + rec
    // ------------------------------------------------------------------

    private fun cropLine(work: Mat, box: FloatArray): Mat? {
        val srcPts = MatOfPoint2f(
            Point(box[0].toDouble(), box[1].toDouble()), Point(box[2].toDouble(), box[3].toDouble()),
            Point(box[4].toDouble(), box[5].toDouble()), Point(box[6].toDouble(), box[7].toDouble()),
        )
        val cw = Math.max(
            Math.hypot((box[0] - box[2]).toDouble(), (box[1] - box[3]).toDouble()),
            Math.hypot((box[4] - box[6]).toDouble(), (box[5] - box[7]).toDouble()),
        ).toInt().coerceAtLeast(1)
        val ch = Math.max(
            Math.hypot((box[0] - box[6]).toDouble(), (box[1] - box[7]).toDouble()),
            Math.hypot((box[2] - box[4]).toDouble(), (box[3] - box[5]).toDouble()),
        ).toInt().coerceAtLeast(1)
        val dstPts = MatOfPoint2f(Point(0.0, 0.0), Point(cw.toDouble(), 0.0), Point(cw.toDouble(), ch.toDouble()), Point(0.0, ch.toDouble()))
        val m = Geometry.getPerspectiveTransform(srcPts, dstPts)
        val dst = Mat()
        Imgproc.warpPerspective(
            work, dst, m, Size(cw.toDouble(), ch.toDouble()),
            Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar(0.0),
        )
        if (ch.toDouble() / cw >= 1.5) {
            val rotated = Mat()
            Core.rotate(dst, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            dst.release()
            return rotated
        }
        return dst
    }

    private fun runRec(e: Engine, crops: List<Mat?>): List<Pair<String, Float>> {
        val valid = crops.indices.filter { crops[it] != null }
        if (valid.isEmpty()) return emptyList()
        val wh = valid.associateWith { crops[it]!!.width().toDouble() / crops[it]!!.height() }
        val order = valid.sortedBy { wh[it]!! }
        val out = (0 until crops.size).associate { i -> i to ("" to 0f) }.toMutableMap()

        var beg = 0
        while (beg < order.size) {
            val end = minOf(order.size, beg + REC_BATCH)
            val batchIdx = order.subList(beg, end)
            val maxWh = maxOf(REC_W.toDouble() / REC_H, batchIdx.maxOf { wh[it]!! })
            val imgW = (REC_H * maxWh).toInt()
            val t = ByteBuffer.allocateDirect(batchIdx.size * 3 * REC_H * imgW * 4).order(ByteOrder.nativeOrder())
            for (idx in batchIdx) {
                val crop = crops[idx]!!
                val ratio = wh[idx]!!
                val resizedW = if (Math.ceil(REC_H * ratio) > imgW) imgW else Math.ceil(REC_H * ratio).toInt()
                val r = Mat()
                Imgproc.resize(crop, r, Size(resizedW.toDouble(), REC_H.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                val raw = ByteArray(REC_H * resizedW * 3)
                r.get(0, 0, raw)
                // CHW into the batch buffer.
                val plane = REC_H * imgW
                for (c in 0 until 3) {
                    for (y in 0 until REC_H) {
                        for (x in 0 until imgW) {
                            val v = if (x < resizedW) (raw[(y * resizedW + x) * 3 + c].toInt() and 0xFF) else 0
                            t.putFloat(((v / 255f) - 0.5f) / 0.5f)
                        }
                    }
                }
                r.release()
            }
            t.flip()
            val input = OnnxTensor.createTensor(
                e.env, t,
                longArrayOf(batchIdx.size.toLong(), 3L, REC_H.toLong(), imgW.toLong()),
                OnnxJavaType.FLOAT,
            )
            try {
                val result = e.rec.run(mapOf("x" to input))
                try {
                    val o = result.get(0) as ai.onnxruntime.OnnxTensor
                    val shape = o.getInfo().shape
                    val T = shape[1].toInt()
                    val C = 18385
                    val fb = o.getFloatBuffer()
                    fb.position(0)
                    val logits = FloatArray(batchIdx.size * T * C)
                    for (i in logits.indices) logits[i] = fb.get()
                    for (k in batchIdx.indices) {
                        val idx = batchIdx[k]
                        val row = FloatArray(T * C)
                        System.arraycopy(logits, k * T * C, row, 0, T * C)
                        out[idx] = CtcDecode.decode(row, T, C, e.chars)
                    }
                } finally {
                    result.close()
                }
            } finally {
                input.close()
            }
            beg = end
        }
        return (0 until crops.size).map { i -> out[i]!! }
    }

    // ------------------------------------------------------------------
    // Pure geometry helpers (unit-tested)
    // ------------------------------------------------------------------

    /** TL, TR, BR, BL order from a box in any corner order. */
    fun orderPointsClockwise(box: FloatArray): FloatArray {
        val pts = Array(4) { i -> doubleArrayOf(box[i * 2].toDouble(), box[i * 2 + 1].toDouble()) }
        val xs = pts.sortedBy { it[0] }
        val leftY = if (xs[1][1] > xs[0][1]) intArrayOf(0, 1) else intArrayOf(1, 0)
        val rightY = if (xs[3][1] > xs[2][1]) intArrayOf(2, 3) else intArrayOf(3, 2)
        val tl = xs[leftY[0]]; val bl = xs[leftY[1]]; val tr = xs[rightY[0]]; val br = xs[rightY[1]]
        return floatArrayOf(
            tl[0].toFloat(), tl[1].toFloat(),
            tr[0].toFloat(), tr[1].toFloat(),
            br[0].toFloat(), br[1].toFloat(),
            bl[0].toFloat(), bl[1].toFloat(),
        )
    }

    /** Reading order: top to bottom, then left to right (with the upstream bubble fix). */
    fun List<FloatArray>.sortedBoxes(): List<FloatArray> {
        val sb = toMutableList().apply { sortWith(compareBy({ it[1] }, { it[0] })) }
        for (i in sb.size - 1 downTo 1) {
            for (j in i downTo 1) {
                if (Math.abs((sb[j][1] - sb[j - 1][1]).toDouble()) < 10 && sb[j][0] < sb[j - 1][0]) {
                    val t = sb[j]; sb[j] = sb[j - 1]; sb[j - 1] = t
                } else break
            }
        }
        return sb
    }

    /** Work-space box (after letterbox) → original src-space box, clipped. */
    fun mapBoxBack(box: FloatArray, scale: Double, padTop: Int, origW: Int, origH: Int): FloatArray {
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[i * 2] = ((box[i * 2] * scale).coerceIn(0.0, (origW - 1).toDouble())).toFloat()
            out[i * 2 + 1] = (((box[i * 2 + 1] - padTop) * scale).coerceIn(0.0, (origH - 1).toDouble())).toFloat()
        }
        return out
    }
}
