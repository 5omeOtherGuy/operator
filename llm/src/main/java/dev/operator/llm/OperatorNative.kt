package dev.operator.llm

/**
 * JNI contract implemented by `llm/src/main/cpp/operator_jni.cpp`.
 *
 * Keep these declarations as the source of truth for the native bridge. In particular, `generate`
 * includes both min-p and grammar ordering; the native implementation must use this exact argument
 * order and the JNI names `Java_dev_operator_llm_OperatorNative_<method>`.
 */
object OperatorNative {
    init {
        System.loadLibrary("ai-chat")
    }

    external fun initialize(nativeLibraryDir: String)
    external fun systemInfo(): String

    external fun loadModel(
        modelPath: String,
        allowedTensorTypes: IntArray,
        useExtraBufts: Boolean,
        /** 0 = CPU, 1 = OpenCL; native must restrict model devices to this backend. */
        backend: Int,
    ): Long

    external fun freeModel(modelHandle: Long)
    external fun newContext(
        modelHandle: Long,
        nCtx: Int,
        nBatch: Int,
        nSeqMax: Int,
        nThreads: Int,
        typeK: Int,
        typeV: Int,
        embeddings: Boolean,
    ): Long

    external fun freeContext(contextHandle: Long)
    external fun tokenize(
        modelHandle: Long,
        text: String,
        addSpecial: Boolean,
        parseSpecial: Boolean,
    ): IntArray

    external fun detokenize(
        modelHandle: Long,
        tokens: IntArray,
        removeSpecial: Boolean,
        unparseSpecial: Boolean,
    ): String

    /** Returns generated token IDs, excluding the terminal EOG token. */
    external fun generate(
        contextHandle: Long,
        sequenceId: Int,
        prompt: IntArray,
        prefixLength: Int,
        gbnf: String?,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        seed: Int,
        grammarFirst: Boolean,
    ): IntArray

    external fun labelLogits(
        contextHandle: Long,
        sourceSequenceId: Int,
        branchSequenceId: Int,
        sourceLength: Int,
        suffix: IntArray,
        labelTokenIds: IntArray,
    ): FloatArray

    external fun embedLast(
        contextHandle: Long,
        sequenceId: Int,
        tokens: IntArray,
        prefixLength: Int,
    ): FloatArray

    external fun clearSequence(contextHandle: Long, sequenceId: Int, from: Int): Boolean
    external fun copySequence(
        contextHandle: Long,
        sourceSequenceId: Int,
        destinationSequenceId: Int,
        from: Int,
        to: Int,
    )

    external fun saveState(contextHandle: Long, sequenceId: Int, flags: Int): ByteArray
    external fun restoreState(contextHandle: Long, sequenceId: Int, flags: Int, state: ByteArray)
    external fun saveStateFile(
        contextHandle: Long,
        sequenceId: Int,
        path: String,
        stateTokens: IntArray,
    ): Long

    external fun restoreStateFile(
        contextHandle: Long,
        sequenceId: Int,
        path: String,
        maxTokens: Int,
    ): IntArray

    external fun abort(contextHandle: Long)
    external fun attachThreadpool(
        contextHandle: Long,
        decodeThreads: Int,
        batchThreads: Int,
        cpuIds: IntArray?,
    )

    /** nPrompt, nGenerated, prefillMs, decodeMs, loadMs, reusedTokens. */
    external fun stats(contextHandle: Long): DoubleArray

    /** prompt tokens/s, generation tokens/s, prefillMs, decodeMs. */
    external fun bench(contextHandle: Long, pp: Int, tg: Int): DoubleArray
}
