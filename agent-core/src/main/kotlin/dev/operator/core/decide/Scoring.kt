package dev.operator.core.decide

import kotlin.math.exp

/*
 * S4 decide(): the confidence formula and the softmax applied after temperature scaling.
 *
 * Design: FOUNDATION §5.1 ("confidence = p_top − mean(p_rest)"), §5.5 (per-kind temperature scaling),
 * research/03-decide.md §F1.12 and §F7.1, ADR-0007 decisions 2 and 9.
 */

/** §5.1, §F1.12: the CLM/TypeSafe wire semantics, ported to Kotlin. */
object Scoring {

    /**
     * §5.1: `confidence = p_top − mean(p_rest)`. For `{A:0.7, B:0.2, C:0.1}` this is
     * `0.7 − (0.2 + 0.1)/2 = 0.55`. A single-option distribution has an empty "rest", so the mean is
     * 0 and the confidence is 1.
     */
    fun confidence(probabilities: DoubleArray): Double {
        require(probabilities.isNotEmpty()) { "confidence needs at least one probability" }
        val top = probabilities.max()
        val restMean = (probabilities.sum() - top) / (probabilities.size - 1).coerceAtLeast(1)
        return top - restMean
    }

    /** §5.1: the map form, used by the answer builders and the log. */
    fun confidence(probabilities: Map<String, Double>): Double =
        confidence(probabilities.values.toDoubleArray())

    /**
     * §5.5, §F7.1: `softmax(logits / temperature)` over the option logits, numerically stable.
     * [temperature] must be positive; a value of 1 is "no scaling".
     */
    fun softmax(logits: FloatArray, temperature: Double): DoubleArray {
        require(temperature > 0.0) { "temperature must be positive, was $temperature" }
        require(logits.isNotEmpty()) { "softmax needs at least one logit" }
        val scaled = DoubleArray(logits.size) { logits[it].toDouble() / temperature }
        val max = scaled.max()
        var sum = 0.0
        for (i in scaled.indices) {
            scaled[i] = exp(scaled[i] - max)
            sum += scaled[i]
        }
        for (i in scaled.indices) scaled[i] /= sum
        return scaled
    }

    /** The index of the largest score; ties resolve to the first option, deterministically. */
    fun argmax(values: DoubleArray): Int {
        var best = 0
        for (i in 1 until values.size) if (values[i] > values[best]) best = i
        return best
    }

    /** One-hot probabilities over an option list: 1.0 at [index], 0 elsewhere. */
    fun oneHot(size: Int, index: Int): DoubleArray {
        require(size > 0) { "oneHot needs at least one option" }
        require(index in 0 until size) { "index $index out of 0..${size - 1}" }
        return DoubleArray(size) { if (it == index) 1.0 else 0.0 }
    }
}
