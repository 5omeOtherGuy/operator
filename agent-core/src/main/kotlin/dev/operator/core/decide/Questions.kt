package dev.operator.core.decide

import dev.operator.core.api.Answer
import dev.operator.core.api.Question

/*
 * S4 decide(): per-question option order and answer construction.
 *
 * Design: FOUNDATION §5.1 (Question/Answer shapes: YesNo, Choice, Score, Rank), research/03-decide.md
 * §F1.12 (`choice` → argmax key plus the full distribution and `p_top − mean(p_rest)`; `score` →
 * expected level plus the distribution; `noul` → P(true)), §R1 (candidate texts).
 */

/** One option as the model sees it: a stable key (code-supplied) and the text to score. */
internal data class Option(val key: String, val text: String)

/** §5.3: the label alphabet for a non-YesNo question, one letter per option, at most 26 options. */
internal const val LETTERS: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

internal object Questions {

    /** §5.3: `yes`/`no` for a YesNo question, otherwise the first [count] letters (A, B, C, …). */
    fun labels(q: Question<*>, count: Int): List<String> =
        if (q is Question.YesNo) listOf("yes", "no") else LETTERS.take(count).map(Char::toString)

    /** The options in the order the question declares them; the label at position j is the j-th label. */
    fun options(q: Question<*>): List<Option> = when (q) {
        is Question.YesNo -> listOf(Option("true", "Yes"), Option("false", "No"))
        is Question.Choice -> q.options.map { Option(it.key, it.value) }
        is Question.Score -> q.levels.map { Option(it, it) }
        is Question.Rank -> q.candidates.map { Option(it, it) }
    }

    fun optionKeys(q: Question<*>): List<String> = options(q).map { it.key }

    /**
     * The answer for [q] from a probability distribution over its option keys, in option order
     * (§5.1). [confidence] is [Scoring.confidence] of the same distribution.
     */
    fun answer(q: Question<*>, probabilities: Map<String, Double>, confidence: Double): Answer {
        val ordered = LinkedHashMap<String, Double>()
        optionKeys(q).forEach { key -> ordered[key] = probabilities[key] ?: 0.0 }
        val keys = ordered.keys.toList()
        val values = DoubleArray(keys.size) { ordered.getValue(keys[it]) }
        return when (q) {
            is Question.YesNo -> Answer.YesNo(ordered, confidence)
            is Question.Choice -> Answer.Choice(ordered, confidence, keys[Scoring.argmax(values)])
            is Question.Score -> Answer.Score(ordered, confidence, keys[Scoring.argmax(values)])
            is Question.Rank -> {
                val ranking = keys.sortedByDescending { ordered.getValue(it) }
                Answer.Rank(ordered, confidence, ranking)
            }
        }
    }
}

/** Options as a flat list of keys; the first occurrence wins, so a duplicated key is still well-defined. */
internal fun probabilitiesByKey(keys: List<String>, values: DoubleArray): Map<String, Double> {
    require(keys.size == values.size) { "keys (${keys.size}) and values (${values.size}) differ" }
    val map = LinkedHashMap<String, Double>()
    keys.forEachIndexed { i, key -> map.putIfAbsent(key, values[i]) }
    return map
}
