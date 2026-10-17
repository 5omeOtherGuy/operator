package dev.operator.core.clm

import java.security.MessageDigest

/**
 * The seeded head generator of `tools/clm/make_goldens.py`, mirrored exactly.
 *
 * The two heads are 18,887,680 fp32 values (75.5 MB) and the test resources must stay under 30 MB, so
 * `head_goldens.json` commits the generator instead of the weights: seed 0, splitmix64, the
 * checkpoint's tensor order (03§F1.4) and one scale per tensor kind. Both sides rebuild the identical
 * stream; `head_goldens.json`'s `weights_sha256` pins that equality, so a generator that drifts fails
 * before any score is compared.
 */
internal object ClmWeights {
    const val SEED: Long = 0L

    /** 0x9E3779B97F4A7C15, the splitmix64 increment. */
    private val GAMMA: Long = 0x9E3779B97F4A7C15uL.toLong()

    /** 0xBF58476D1CE4E5B9. */
    private val MIX1: Long = 0xBF58476D1CE4E5B9uL.toLong()

    /** 0x94D049BB133111EB. */
    private val MIX2: Long = 0x94D049BB133111EBuL.toLong()

    private const val UNIF_SCALE: Double = 8388608.0

    const val WEIGHT_SCALE: Float = 1.0f

    const val BIAS_SCALE: Float = 0.05f

    const val GAMMA_SCALE: Float = 0.1f

    /** The tensor order of the stream, as `head.name`: state head first, then the action head. */
    val STREAM_ORDER: List<String> = ClmHeads.let { heads ->
        listOf(heads.STATE_PREFIX, heads.ACTION_PREFIX).flatMap { prefix -> heads.tensorNames(prefix) }
    }

    /** `<head>.<name>` -> shape, in [STREAM_ORDER]. */
    val SHAPES: Map<String, List<Long>> = linkedMapOf(
        "${ClmHeads.STATE_PREFIX}.inp.weight" to listOf(1536L, 4096L),
        "${ClmHeads.STATE_PREFIX}.inp.bias" to listOf(1536L),
        "${ClmHeads.STATE_PREFIX}.hidden.0.weight" to listOf(1536L, 1536L),
        "${ClmHeads.STATE_PREFIX}.hidden.0.bias" to listOf(1536L),
        "${ClmHeads.STATE_PREFIX}.norms.0.weight" to listOf(1536L),
        "${ClmHeads.STATE_PREFIX}.norms.0.bias" to listOf(1536L),
        "${ClmHeads.STATE_PREFIX}.out.weight" to listOf(512L, 1536L),
        "${ClmHeads.STATE_PREFIX}.out.bias" to listOf(512L),
        "${ClmHeads.ACTION_PREFIX}.inp.weight" to listOf(1536L, 4096L),
        "${ClmHeads.ACTION_PREFIX}.inp.bias" to listOf(1536L),
        "${ClmHeads.ACTION_PREFIX}.hidden.0.weight" to listOf(1536L, 1536L),
        "${ClmHeads.ACTION_PREFIX}.hidden.0.bias" to listOf(1536L),
        "${ClmHeads.ACTION_PREFIX}.norms.0.weight" to listOf(1536L),
        "${ClmHeads.ACTION_PREFIX}.norms.0.bias" to listOf(1536L),
        "${ClmHeads.ACTION_PREFIX}.out.weight" to listOf(512L, 1536L),
        "${ClmHeads.ACTION_PREFIX}.out.bias" to listOf(512L),
    )

    /** splitmix64 over a signed Long, bit-for-bit the Python reference's unsigned 64-bit state. */
    class Stream(seed: Long = SEED) {
        private var state: Long = seed

        fun nextU64(): Long {
            state += GAMMA
            var z = state
            z = (z xor (z ushr 30)) * MIX1
            z = (z xor (z ushr 27)) * MIX2
            return z xor (z ushr 31)
        }

        /** One draw in [-1, 1); the value is exact in binary32. */
        fun uniform(): Float {
            val m = (nextU64() ushr 40) and 0xFFFFFFL
            return (m.toDouble() / UNIF_SCALE - 1.0).toFloat()
        }

        fun uniform(n: Int): FloatArray = FloatArray(n) { uniform() }
    }

    /** The 16 tensors, `<head>.<name>` -> values, in the order the stream is drawn. */
    fun tensors(stream: Stream = Stream()): LinkedHashMap<String, FloatArray> {
        val out = LinkedHashMap<String, FloatArray>()
        for (key in STREAM_ORDER) {
            val n = SHAPES.getValue(key).fold(1L) { acc, dim -> acc * dim }.toInt()
            val uniform = stream.uniform(n)
            val values = FloatArray(n)
            when {
                key.endsWith("inp.weight") || key.endsWith("hidden.0.weight") || key.endsWith("out.weight") ->
                    for (i in 0 until n) values[i] = uniform[i] * WEIGHT_SCALE
                key.endsWith("norms.0.weight") ->
                    for (i in 0 until n) values[i] = 1.0f + uniform[i] * GAMMA_SCALE
                else ->
                    for (i in 0 until n) values[i] = uniform[i] * BIAS_SCALE
            }
            out[key] = values
        }
        return out
    }

    /** sha256 over the 16 tensors in [STREAM_ORDER], as little-endian fp32 bytes. */
    fun sha256(tensors: Map<String, FloatArray> = tensors()): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (key in STREAM_ORDER) {
            for (value in tensors.getValue(key)) {
                buffer.clear()
                buffer.putFloat(value)
                digest.update(buffer.array())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The two heads of a [tensors] set, for `ClmHeads.of`. */
    fun head(tensors: Map<String, FloatArray>, prefix: String): ClmHead = ClmHead(
        inpWeight = tensors.getValue("$prefix.inp.weight"),
        inpBias = tensors.getValue("$prefix.inp.bias"),
        hiddenWeight = tensors.getValue("$prefix.hidden.0.weight"),
        hiddenBias = tensors.getValue("$prefix.hidden.0.bias"),
        normWeight = tensors.getValue("$prefix.norms.0.weight"),
        normBias = tensors.getValue("$prefix.norms.0.bias"),
        outWeight = tensors.getValue("$prefix.out.weight"),
        outBias = tensors.getValue("$prefix.out.bias"),
    )
}
