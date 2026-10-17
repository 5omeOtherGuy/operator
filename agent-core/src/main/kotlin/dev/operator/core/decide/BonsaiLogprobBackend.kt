package dev.operator.core.decide

import dev.operator.core.api.BackendId
import dev.operator.core.api.DecideBackend
import dev.operator.core.api.DecisionKind
import dev.operator.core.api.DecisionState
import dev.operator.core.api.GenerateRequest
import dev.operator.core.api.LlmPort
import dev.operator.core.api.ModelRole
import dev.operator.core.api.Question
import dev.operator.core.api.Sampling
import java.util.concurrent.atomic.AtomicLong

/*
 * S4 decide(): the BONSAI_LOGPROB backend (router step 2, the M1 learned backend).
 *
 * Design: FOUNDATION §5.1 (the router order), §5.3 ("M1: RULES + BONSAI_LOGPROB ... Letter labels
 * (A–Z, yes/no) scored on a branch of the agent's KV. Per-kind temperature fitted on the synthetic
 * decision set, conservative τ. High-stakes kinds are averaged over two option orderings to counter
 * position bias"), §4.5 (the branch sequence and prefix reuse), ADR-0007 decision 5 and 7,
 * research/03-decide.md §F4 (label scoring: put the question and labelled options in the prompt, one
 * forward pass, read the option-label logits) and §F4.3 (option-position bias → two permutations).
 *
 * The backend returns raw per-option logits and nothing else; calibration and thresholds are the
 * router's steps 3-4 (§5.1). It scores over `LlmPort.labelLogits`, which S1 implements over a branch
 * of the agent's KV cache copied from sequence 0 with `llama_memory_seq_cp`.
 *
 * Contract with the port (S4 assumes it; S1 owns the native side): `labelLogits` returns one logits
 * row per label, indexed by token id; the score of label j is `row_j[labelTokenIds_j.last()]`.
 */

/** The prompt/rendering recipe version this backend scores under; part of the calibration key (§F6.5). */
const val BONSAI_LOGPROB_RECIPE_VERSION: String = "logprob-v0"

class BonsaiLogprobBackend(
    private val llm: LlmPort,
    /** sha256 of the planner weights (§4.8); the `modelId` in the meta and the log. */
    override val modelId: String,
    private val role: ModelRole = ModelRole.PLANNER,
    override val recipeVersion: String? = BONSAI_LOGPROB_RECIPE_VERSION,
    /** §4.5: >0 is a decide branch copied from the task sequence 0. */
    private val branchSequenceId: Int = 1,
    /** §4.5, §5.3: kinds scored twice with the option order swapped, whatever `DecidePolicy` says. */
    private val highStakesKinds: Set<DecisionKind> = HIGH_STAKES_KINDS,
) : DecideBackend, BackendIdentity, DoubleAskBackend {

    override val id: BackendId = BackendId.BONSAI_LOGPROB

    /** No separate head: the label logits come from the planner's own LM head. */
    override val headId: String? = null

    private val nextRequestId = AtomicLong(1)

    /** §5.5: the tuple this backend's calibration file must be versioned by. */
    val calibrationKey: CalibrationKey = CalibrationKey(id, modelId, headId, recipeVersion ?: "")

    /** The Bonsai logprob backend scores every question with 1–26 options (letters) or 2 (yes/no). */
    override fun supports(q: Question<*>): Boolean = Questions.options(q).size in 1..LETTERS.length

    override suspend fun rawScores(s: DecisionState, q: Question<*>): FloatArray =
        rawScores(s, q, doubleAsk = false)

    override suspend fun rawScores(s: DecisionState, q: Question<*>, doubleAsk: Boolean): FloatArray {
        val options = Questions.options(q)
        require(options.size in 1..LETTERS.length) {
            "BONSAI_LOGPROB scores 1..26 options with letters, got ${options.size}"
        }
        val labels = Questions.labels(q, options.size)
        val labelTokenIds = labels.map { llm.tokenize(role, it, addSpecial = false, parseSpecial = false) }

        val forward = scoreInOrder(s, q, options, labels, labelTokenIds, options.indices.toList())
        val double = doubleAsk || q.kind in highStakesKinds
        if (!double || options.size < 2) return forward

        val reversed = options.indices.reversed().toList()
        val backward = scoreInOrder(s, q, options, labels, labelTokenIds, reversed)
        return FloatArray(options.size) { (forward[it] + backward[it]) / 2f }
    }

    /**
     * One forward pass with [order] as the presentation order: position j shows `order[j]` under
     * label `labels[j]`. The result is indexed by option index, so two orderings average directly.
     */
    private suspend fun scoreInOrder(
        s: DecisionState,
        q: Question<*>,
        options: List<Option>,
        labels: List<String>,
        labelTokenIds: List<List<Int>>,
        order: List<Int>,
    ): FloatArray {
        val byPosition = order.map { options[it] }
        val promptParts = listOf(header(s, q), optionBlock(byPosition, labels))
        val rows = llm.labelLogits(request(), role, promptParts, labelTokenIds)
        require(rows.size == options.size) {
            "labelLogits returned ${rows.size} rows for ${options.size} labels"
        }
        val scores = FloatArray(options.size)
        order.forEachIndexed { position, optionIndex ->
            val tokenIds = labelTokenIds[position]
            require(tokenIds.isNotEmpty()) { "label ${labels[position]} tokenised to nothing" }
            val row = rows[position]
            val tokenId = tokenIds.last()
            require(tokenId in row.indices) { "label ${labels[position]} token $tokenId outside its logits row (${row.size})" }
            scores[optionIndex] = row[tokenId]
        }
        return scores
    }

    /** §5.4 recipe: the state is context first, question last (`context + "\n\n" + instructions`). */
    private fun header(s: DecisionState, q: Question<*>): String = s.context + "\n\n" + q.instructions

    private fun optionBlock(byPosition: List<Option>, labels: List<String>): String = buildString {
        append("Options:")
        byPosition.forEachIndexed { position, option ->
            append('\n').append(labels[position]).append(". ").append(option.text)
        }
        append("\nAnswer with one label")
        append(if (labels.first() == "yes") " (yes or no)." else " (A, B, C, …).")
    }

    /** §4.5: a decide branch is a fresh copy of sequence 0, greedy, with prefix reuse on. */
    private fun request(): GenerateRequest = GenerateRequest(
        id = nextRequestId.getAndIncrement(),
        sampling = Sampling(greedy = true),
        sequenceId = branchSequenceId,
        reusePrefix = true,
        grammarFirst = false,
    )
}
