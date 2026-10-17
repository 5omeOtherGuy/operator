package dev.operator.core.clm

import dev.operator.core.api.Question

/*
 * The port of CLM's `schema.py` (FOUNDATION §5.3, §5.4; ADR-0007 decisions 2 and 7; 03§F1.11–F1.12).
 *
 * What the heads were trained on, verbatim from the reference code:
 *  - `state_text = to_text(state) + "\n\n" + to_text(instructions)` — context first, question last;
 *  - boolean (`noul`) candidates: `"true: Yes. This is true: {q}"` and `"false: No. This is false: {q}"`;
 *  - a `choice` candidate is the option's description (or, if empty, its key) with no prefix, a
 *    `score` candidate is a rubric level's text, a `rank` candidate is the candidate string itself;
 *  - plain text only: no BOS, no EOS, no chat template, no special tokens;
 *  - the state keeps its *tail* and a candidate keeps its *head*; at most 2048 tokens (`--max-model-len`
 *    of the reference server, which truncates the prompt at 2048);
 *  - `to_text` renders prose, not JSON; [DecisionState.context] arrives as prose from lane 04, so the
 *    port concatenates rather than serialises.
 *
 * The tokenizer is injected ([ClmTokenizer]): on the phone it is `LlmPort.tokenize(role, text,
 * addSpecial = false, parseSpecial = false)` of S1 (FOUNDATION §5.4, ADR-0007 decision 7), so the gate
 * corpus, the golden fixtures and the device feed the same ids to the same path (R10).
 */

/** A tokenizer as the CLM recipe uses it: plain text in, ids out, nothing added and nothing parsed. */
fun interface ClmTokenizer {
    /** Token ids of [text] without BOS/EOS, special tokens or a chat template (03§F1.11). */
    fun encode(text: String): List<Int>
}

/** The recipe-exact texts and token sequences of the CLM heads. */
object ClmSchema {
    /** `--max-model-len 2048` of the reference server [03§F1.11]: the cap on both sides. */
    const val MAX_TOKENS: Int = 2048

    /** Identifies the rendering (`to_text`/candidate) in cache and calibration keys (F6.5). */
    const val RECIPE_VERSION: String = "clm-v0.1"

    /** `state_text = context + "\n\n" + instructions` (03§F1.12). */
    fun stateText(context: String, instructions: String): String = context + "\n\n" + instructions

    /** A `noul` (boolean) candidate [03§F1.12]: `Yes` is scored against the option named `true`. */
    fun yesNoCandidate(instructions: String, isTrue: Boolean): String =
        if (isTrue) "true: Yes. This is true: $instructions" else "false: No. This is false: $instructions"

    /**
     * The candidate texts of [q], in the order of the question's own options: the answer
     * probabilities and the option ordering follow it.
     */
    fun candidateTexts(q: Question<*>): List<String> = when (q) {
        is Question.YesNo -> listOf(yesNoCandidate(q.instructions, true), yesNoCandidate(q.instructions, false))
        is Question.Choice -> q.options.entries.map { (key, text) -> text.ifBlank { key } }
        is Question.Score -> q.levels
        is Question.Rank -> q.candidates
    }

    /** The candidate key of a `noul` candidate: `true` first, then `false`. */
    fun yesNoKeys(): List<String> = listOf("true", "false")

    /**
     * The tokens the state head is run on: the state keeps its **tail**, capped at [maxTokens]
     * (F1.11: the last tokens of the plain text are the question-last part of the recipe).
     */
    fun stateTokens(
        tokenizer: ClmTokenizer,
        context: String,
        instructions: String,
        maxTokens: Int = MAX_TOKENS,
    ): List<Int> = keepTail(tokenizer.encode(stateText(context, instructions)), maxTokens)

    /** The tokens the action head is run on: a candidate keeps its **head**, capped at [maxTokens]. */
    fun candidateTokens(
        tokenizer: ClmTokenizer,
        candidate: String,
        maxTokens: Int = MAX_TOKENS,
    ): List<Int> = keepHead(tokenizer.encode(candidate), maxTokens)

    /** The tokens of every candidate of [q], in [candidateTexts] order. */
    fun candidateTokens(
        tokenizer: ClmTokenizer,
        q: Question<*>,
        maxTokens: Int = MAX_TOKENS,
    ): List<List<Int>> = candidateTexts(q).map { candidateTokens(tokenizer, it, maxTokens) }

    /** The last [maxTokens] ids (F1.11: a state keeps its tail). */
    fun keepTail(ids: List<Int>, maxTokens: Int = MAX_TOKENS): List<Int> {
        require(maxTokens > 0) { "maxTokens must be positive, was $maxTokens" }
        return if (ids.size <= maxTokens) ids.toList() else ids.subList(ids.size - maxTokens, ids.size).toList()
    }

    /** The first [maxTokens] ids (F1.11: a candidate keeps its head). */
    fun keepHead(ids: List<Int>, maxTokens: Int = MAX_TOKENS): List<Int> {
        require(maxTokens > 0) { "maxTokens must be positive, was $maxTokens" }
        return if (ids.size <= maxTokens) ids.toList() else ids.subList(0, maxTokens).toList()
    }

    /** The prefix two token sequences share, for the KV prefix reuse of §4.5 / F6.6. */
    fun longestCommonPrefix(a: List<Int>, b: List<Int>): Int {
        val n = minOf(a.size, b.size)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }
}
