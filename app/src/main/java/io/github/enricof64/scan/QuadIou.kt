package io.github.enricof64.scan

import kotlin.math.abs

/**
 * Intersection-over-union of two convex document quads (the F5 benchmark
 * verdict metric): pure geometry on ordered (cyclic) corner coordinates in
 * the same space, no image access, fully unit-tested.
 *
 * Both quads must be convex and non-degenerate. Corner order must be cyclic
 * (TL, TR, BR, BL as the detectors emit them); a clockwise or counter-
 * clockwise winding both work, the orientation is normalized first.
 */
object QuadIou {

    /**
     * IoU of two quads given as `[x0, y0, x1, y1, x2, y2, x3, y3]` in cyclic
     * order. 0.0 for degenerate or malformed input (tiny/zero area, wrong
     * size) rather than throwing, so the benchmark can record the failure.
     */
    fun iou(a: FloatArray, b: FloatArray): Double {
        if (a.size != 8 || b.size != 8) return 0.0
        val ptsA = a.toPts()
        val ptsB = b.toPts()
        val areaA = abs(signedArea(ptsA))
        val areaB = abs(signedArea(ptsB))
        if (areaA <= 1e-9 || areaB <= 1e-9) return 0.0
        val areaI = abs(signedArea(convexClip(ensureCCW(ptsA), ensureCCW(ptsB))))
        if (areaI <= 1e-9) return 0.0
        return areaI / (areaA + areaB - areaI)
    }

    private fun FloatArray.toPts(): List<Pair<Double, Double>> =
        (0 until 4).map { (this[it * 2].toDouble() to this[it * 2 + 1].toDouble()) }

    /** Shoelace formula, signed area. */
    private fun signedArea(pts: List<Pair<Double, Double>>): Double {
        var s = 0.0
        for (i in pts.indices) {
            val (x1, y1) = pts[i]
            val (x2, y2) = pts[(i + 1) % pts.size]
            s += x1 * y2 - x2 * y1
        }
        return s / 2.0
    }

    /** Reverses the winding when the signed area is negative. */
    private fun ensureCCW(pts: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        if (signedArea(pts) < 0) pts.reversed() else pts

    /**
     * Sutherland–Hodgman clipping of the (convex) subject by each edge of
     * the (convex) clipper. Both inputs are CCW. On an outside→inside
     * transition the intersection AND the entering point are both added —
     * dropping the entering point degenerates the polygon and silently
     * collapses near-identical quads to a zero-area sliver (F5 regression).
     */
    private fun convexClip(
        subjectIn: List<Pair<Double, Double>>,
        clipIn: List<Pair<Double, Double>>,
    ): List<Pair<Double, Double>> {
        var output = subjectIn
        for (e in clipIn.indices) {
            val p1 = clipIn[e]
            val p2 = clipIn[(e + 1) % clipIn.size]
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
                    curIn && !prevIn -> {
                        output.add(lineLine(prev, cur, p1, p2))
                        output.add(cur)
                    }
                    !curIn && prevIn -> output.add(lineLine(prev, cur, p1, p2))
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
}
