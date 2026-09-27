package dev.operator.core.clm

import java.nio.file.Path

/*
 * The CLM-8B fp32 head path (FOUNDATION §5.3–5.4; ADR-0007 decisions 2, 7, 8; 03§F1.4–F1.6, F6.1–F6.4).
 *
 * One head is a 3-layer fp32 MLP on the L2-normalised 4096-d last-token encoder state:
 *
 *   x = l2(input); x = gelu(inp(x)); x = gelu(norm(hidden(x))); z = l2(out(x))
 *
 * with exact-erf GELU, LayerNorm eps 1e-5, and `inp` 4096→1536, `hidden.0` 1536→1536, `norm` 1536,
 * `out` 1536→512. The score of a (state, candidate) pair is `min(exp(logit_scale), 100) * cos`, which
 * the stored `logit_scale` 4.61325 clamps to exactly 100 (03§F1.6).
 *
 * This is the reference path: it is what the JVM golden tests prove equal to the Python reference, and
 * it is the fallback when a native (ggml/NEON) kernel is not justified (03§F6.3–F6.4). The CLM backend
 * itself is not part of M1 (ADR-0007 decision 5); this is the tested code M2 selects.
 */

/** The eight fp32 tensors of one head, in the checkpoint's own layout (03§F1.4). */
class ClmHead(
    private val inpWeight: FloatArray,
    private val inpBias: FloatArray,
    private val hiddenWeight: FloatArray,
    private val hiddenBias: FloatArray,
    private val normWeight: FloatArray,
    private val normBias: FloatArray,
    private val outWeight: FloatArray,
    private val outBias: FloatArray,
) {
    init {
        require(inpWeight.size == HIDDEN * INPUT) { "inp.weight is ${inpWeight.size}, expected ${HIDDEN * INPUT}" }
        require(inpBias.size == HIDDEN) { "inp.bias is ${inpBias.size}, expected $HIDDEN" }
        require(hiddenWeight.size == HIDDEN * HIDDEN) { "hidden.0.weight is ${hiddenWeight.size}" }
        require(hiddenBias.size == HIDDEN) { "hidden.0.bias is ${hiddenBias.size}" }
        require(normWeight.size == HIDDEN) { "norms.0.weight is ${normWeight.size}" }
        require(normBias.size == HIDDEN) { "norms.0.bias is ${normBias.size}" }
        require(outWeight.size == OUTPUT * HIDDEN) { "out.weight is ${outWeight.size}" }
        require(outBias.size == OUTPUT) { "out.bias is ${outBias.size}" }
    }

    /**
     * Projects the L2-normalised encoder state [unitInput] (03§F1.8) and returns the 512-d
     * L2-normalised projection.
     */
    fun project(unitInput: FloatArray): FloatArray {
        require(unitInput.size == INPUT) { "encoder state is ${unitInput.size}-d, expected $INPUT" }
        val a = FloatArray(HIDDEN)
        val b = FloatArray(HIDDEN)
        ClmMath.matvecAdd(inpWeight, HIDDEN, INPUT, unitInput, inpBias, a)
        ClmMath.geluInPlace(a, HIDDEN)
        ClmMath.matvecAdd(hiddenWeight, HIDDEN, HIDDEN, a, hiddenBias, b)
        // `a` is free again (it held the GELU output of the first layer): it becomes the scratch.
        ClmMath.layerNorm(b, HIDDEN, normWeight, normBias, ClmHeads.LN_EPS, b, a)
        ClmMath.geluInPlace(b, HIDDEN)
        val out = FloatArray(OUTPUT)
        ClmMath.matvecAdd(outWeight, OUTPUT, HIDDEN, b, outBias, out)
        ClmMath.l2NormalizeInPlace(out, OUTPUT)
        return out
    }

    companion object {
        const val INPUT = 4096

        const val HIDDEN = 1536

        const val OUTPUT = 512

        /** Depth 3: `inp` + one `hidden` layer + `out` (03§F1.4). */
        const val DEPTH = 3
    }
}

/**
 * The two heads and the score scale of `clm-heads-v0.1`. Create with [open] (the converted
 * `.pt`-derived safetensors file) or [of] (weights in memory).
 */
class ClmHeads private constructor(
    val stateHead: ClmHead,
    val actionHead: ClmHead,
    val logitScale: Float,
    val scaleClamp: Float,
    /** The `__metadata__` of the heads file: `headId`/version inputs of §5.5 and 03§F6.1. */
    val metadata: Map<String, String>,
) {
    /** `min(exp(logit_scale), scale_clamp)`; exactly 100.0 for the published head. */
    val scale: Float = ClmMath.scale(logitScale, scaleClamp)

    /** The state head's projection of a raw encoder state: the input is L2-normalised first (03§F1.8). */
    fun stateVector(rawState: FloatArray): FloatArray = projectNormally(stateHead, rawState)

    /** The action head's projection of a raw candidate state. */
    fun actionVector(rawCandidate: FloatArray): FloatArray = projectNormally(actionHead, rawCandidate)

    /** `scale * cos(stateVector, actionVector)` for two L2-normalised projections (03§F1.6). */
    fun score(stateVector: FloatArray, actionVector: FloatArray): Float {
        require(stateVector.size == ClmHead.OUTPUT && actionVector.size == ClmHead.OUTPUT) {
            "projections are ${stateVector.size}-d and ${actionVector.size}-d, expected ${ClmHead.OUTPUT}"
        }
        return scale * ClmMath.dot(stateVector, actionVector, ClmHead.OUTPUT)
    }

    /** [score] for every candidate, in order. */
    fun scores(stateVector: FloatArray, actionVectors: List<FloatArray>): FloatArray =
        FloatArray(actionVectors.size) { score(stateVector, actionVectors[it]) }

    private fun projectNormally(head: ClmHead, raw: FloatArray): FloatArray {
        require(raw.size == ClmHead.INPUT) { "encoder state is ${raw.size}-d, expected ${ClmHead.INPUT}" }
        val unit = raw.copyOf()
        ClmMath.l2NormalizeInPlace(unit, ClmHead.INPUT)
        return head.project(unit)
    }

    companion object {
        /** Recipe version carried in cache and calibration keys (03§F6.5, F7.4). */
        const val RECIPE_VERSION = "clm-v0.1-heads-1"

        /** LayerNorm eps of the reference head (03§F1.4, torch default). */
        const val LN_EPS: Float = 1e-5f

        /** The clamp on `exp(logit_scale)` (03§F1.6). */
        const val SCALE_CLAMP: Float = 100f

        /** The tensor names of one head inside the heads file: `<prefix>.inp.weight` and so on. */
        fun tensorNames(prefix: String): List<String> = listOf(
            "$prefix.inp.weight",
            "$prefix.inp.bias",
            "$prefix.hidden.0.weight",
            "$prefix.hidden.0.bias",
            "$prefix.norms.0.weight",
            "$prefix.norms.0.bias",
            "$prefix.out.weight",
            "$prefix.out.bias",
        )

        /** The prefixes of the two heads. */
        const val STATE_PREFIX = "state_head"

        const val ACTION_PREFIX = "action_head"

        /** Weights in memory; [metadata] is informational and defaults to the recipe's own values. */
        fun of(
            stateHead: ClmHead,
            actionHead: ClmHead,
            logitScale: Float,
            scaleClamp: Float = SCALE_CLAMP,
            metadata: Map<String, String> = emptyMap(),
        ): ClmHeads = ClmHeads(stateHead, actionHead, logitScale, scaleClamp, metadata)

        /**
         * Reads a converted heads file (`tools/clm/convert_heads.py`). The recipe's metadata is
         * required and checked: a file that is not the pinned `clm-heads-v0.1` export must not load.
         */
        fun open(path: Path): ClmHeads = SafetensorsFile.open(path).use { file -> load(file) }

        /** Reads the heads from an open safetensors view (the tests reuse it; the file stays open). */
        fun load(file: SafetensorsFile): ClmHeads {
            val md = file.metadata
            require(md["activation"] == "gelu_erf") {
                "heads metadata activation is '${md["activation"]}', expected 'gelu_erf' (exact-erf GELU)"
            }
            require(md["width"]?.toIntOrNull() == ClmHead.HIDDEN) {
                "heads metadata width is '${md["width"]}', expected ${ClmHead.HIDDEN}"
            }
            require(md["depth"]?.toIntOrNull() == ClmHead.DEPTH) {
                "heads metadata depth is '${md["depth"]}', expected ${ClmHead.DEPTH}"
            }
            require(md["ln_eps"]?.toFloatOrNull() == LN_EPS) {
                "heads metadata ln_eps is '${md["ln_eps"]}', expected $LN_EPS"
            }
            val logitScale = md["logit_scale"]?.toFloatOrNull()
                ?: throw IllegalArgumentException("heads metadata has no logit_scale (03§F1.6)")
            val clamp = md["scale_clamp"]?.toFloatOrNull() ?: SCALE_CLAMP
            return ClmHeads(readHead(file, STATE_PREFIX), readHead(file, ACTION_PREFIX), logitScale, clamp, md)
        }

        private fun readHead(file: SafetensorsFile, prefix: String): ClmHead = ClmHead(
            inpWeight = file.float32("$prefix.inp.weight", ClmHead.HIDDEN.toLong(), ClmHead.INPUT.toLong()),
            inpBias = file.float32("$prefix.inp.bias", ClmHead.HIDDEN.toLong()),
            hiddenWeight = file.float32(
                "$prefix.hidden.0.weight",
                ClmHead.HIDDEN.toLong(),
                ClmHead.HIDDEN.toLong(),
            ),
            hiddenBias = file.float32("$prefix.hidden.0.bias", ClmHead.HIDDEN.toLong()),
            normWeight = file.float32("$prefix.norms.0.weight", ClmHead.HIDDEN.toLong()),
            normBias = file.float32("$prefix.norms.0.bias", ClmHead.HIDDEN.toLong()),
            outWeight = file.float32("$prefix.out.weight", ClmHead.OUTPUT.toLong(), ClmHead.HIDDEN.toLong()),
            outBias = file.float32("$prefix.out.bias", ClmHead.OUTPUT.toLong()),
        )
    }
}
