package dev.operator.core.decide

import dev.operator.core.api.Clock
import dev.operator.core.api.GenerateRequest
import dev.operator.core.api.GenerationResult
import dev.operator.core.api.LlmPort
import dev.operator.core.api.LlmStats
import dev.operator.core.api.ModelRole
import dev.operator.core.api.ModelSpec
import kotlinx.coroutines.delay

/** One `labelLogits` call, for assertions about ordering and prompts. */
data class LabelCall(
    val reqId: Long,
    val promptParts: List<String>,
    val labelTokenIds: List<List<Int>>,
)

/**
 * An in-process [LlmPort] for S4 decide tests. Only `tokenize` and `labelLogits` do real work; the
 * generation, embedding, state and lifecycle calls are not part of decide() and throw if reached.
 *
 * [tokenIdOf] defaults to `A`–`Z` → 0–25 and `yes`/`no` → 0/1, so each label's token id equals its
 * position and `labelLogits` can return the same score vector for every label (the backend reads
 * `row[tokenId]`).
 */
class FakeLlmPort(
    private val tokenIdOf: (String) -> List<Int> = ::defaultLabelTokens,
    private val rowsFor: (List<String>, List<List<Int>>, Int) -> List<FloatArray>,
    /** §4.5: non-zero delays the label scoring, to exercise the decide deadline. */
    private val labelDelayMs: Long = 0,
) : LlmPort {

    val labelCalls: MutableList<LabelCall> = mutableListOf()
    var tokenizeCalls: Int = 0
        private set

    override suspend fun tokenize(
        role: ModelRole,
        text: String,
        addSpecial: Boolean,
        parseSpecial: Boolean,
    ): List<Int> {
        tokenizeCalls++
        return tokenIdOf(text)
    }

    override suspend fun labelLogits(
        req: GenerateRequest,
        role: ModelRole,
        promptParts: List<String>,
        labelTokenIds: List<List<Int>>,
    ): List<FloatArray> {
        val index = labelCalls.size
        labelCalls += LabelCall(req.id, promptParts, labelTokenIds)
        if (labelDelayMs > 0) delay(labelDelayMs)
        return rowsFor(promptParts, labelTokenIds, index)
    }

    override suspend fun load(spec: ModelSpec) = notReached()

    override suspend fun unload(role: ModelRole) = notReached()

    override suspend fun generate(
        req: GenerateRequest,
        role: ModelRole,
        promptParts: List<String>,
        gbnf: String?,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): GenerationResult = notReached()

    override suspend fun embedLast(req: GenerateRequest, role: ModelRole, tokens: List<Int>): FloatArray = notReached()

    override suspend fun stateSave(role: ModelRole, path: String) = notReached()

    override suspend fun stateRestore(role: ModelRole, path: String) = notReached()

    override suspend fun stateCheckpoint(role: ModelRole, tag: String, partialOnly: Boolean) = notReached()

    override suspend fun abort(reqId: Long) = notReached()

    override suspend fun pid(): Int = notReached()

    override suspend fun stats(): LlmStats = notReached()

    override suspend fun bench(pp: Int, tg: Int, threads: Int): LlmStats = notReached()

    private fun notReached(): Nothing = throw NotImplementedError("decide() must not reach this LlmPort call")
}

private fun defaultLabelTokens(text: String): List<Int> = when {
    text == "yes" -> listOf(0)
    text == "no" -> listOf(1)
    text.length == 1 && text[0] in 'A'..'Z' -> listOf(text[0] - 'A')
    else -> listOf(text.hashCode() and 0x7f)
}

/** A monotonic and wall clock the tests can pin or advance. */
class FakeClock(
    private var wall: Long = 1_700_000_000_000L,
    private var mono: Long = 0L,
) : Clock {
    override fun wallMs(): Long = wall

    override fun monotonicMs(): Long = mono

    fun advance(ms: Long) {
        mono += ms
        wall += ms
    }
}
