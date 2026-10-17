package dev.operator.llm

import com.arm.aichat.InferenceEngine

/**
 * Operator's handle-based extension of the pinned upstream API.
 *
 * The upstream interface remains untouched so its sample engine can still be used for the stage-0
 * smoke path.
 */
interface OperatorInferenceEngine : InferenceEngine {
    suspend fun tokenize(text: String, addSpecial: Boolean, parseSpecial: Boolean): IntArray
    suspend fun generate(
        request: GenerateRequest,
        prompt: IntArray,
        gbnf: String?,
        maxTokens: Int,
    ): IntArray

    suspend fun labelLogits(
        request: GenerateRequest,
        prompt: IntArray,
        labels: List<IntArray>,
    ): List<FloatArray>

    suspend fun embedLast(request: GenerateRequest, tokens: IntArray): FloatArray
    suspend fun stateSave(path: String)
    suspend fun stateRestore(path: String)
    suspend fun stateCheckpoint(path: String, partialOnly: Boolean)
    fun abort(requestId: Long)
    fun operatorStats(): LlmStats
}
