package io.github.enricof64.scan.ocr

/**
 * Pure CTC greedy decoding for the PP-OCRv5 recognition model.
 *
 * The model emits a logit vector of length [classCount] at every timestep;
 * class 0 is the CTC blank. Greedy decoding = per-timestep argmax, collapse
 * repeated timesteps, drop the blank, then map indices through [chars]
 * (the raw dictionary, WITHOUT the leading blank and trailing space).
 *
 * Index → character mapping (see PaddleOCR `CTCLabelDecode`):
 *  - 0            → blank (dropped)
 *  - 1 .. N       → [chars][0 .. N-1]
 *  - N+1          → " " (the space appended at the end of the label set)
 *
 * where N = [chars].size and classCount = N + 2.
 */
object CtcDecode {

    /**
     * @param logits row-major `[timesteps, classCount]` logits.
     * @return the decoded text and the mean confidence (max-prob) of the kept
     *         timesteps, or 0.0 when nothing survived.
     */
    fun decode(logits: FloatArray, timesteps: Int, classCount: Int, chars: Array<String>): Pair<String, Float> {
        require(timesteps * classCount == logits.size)
        val sb = StringBuilder()
        var prev = -1
        var confSum = 0.0
        var confN = 0
        for (t in 0 until timesteps) {
            val base = t * classCount
            var best = 0
            var bestV = logits[base]
            for (c in 1 until classCount) {
                val v = logits[base + c]
                if (v > bestV) {
                    bestV = v
                    best = c
                }
            }
            // Collapse consecutive duplicates (CTC merge).
            if (best == prev) continue
            prev = best
            if (best == 0) continue // blank
            when (best) {
                classCount - 1 -> sb.append(' ')
                else -> {
                    val idx = best - 1
                    if (idx in chars.indices) sb.append(chars[idx]) else sb.append('\uFFFD')
                }
            }
            // Softmax probability of the winning class (bestV is the row max).
            var sum = 0.0
            for (c in 0 until classCount) sum += kotlin.math.exp((logits[base + c] - bestV).toDouble())
            confSum += 1.0 / sum
            confN++
        }
        return sb.toString() to (if (confN == 0) 0.0f else (confSum / confN).toFloat())
    }
}
