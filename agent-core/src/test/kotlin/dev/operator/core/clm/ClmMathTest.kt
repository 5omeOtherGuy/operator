package dev.operator.core.clm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The numeric primitives of the head path, against hand-checked values (03§F1.5-F1.6):
 * exact-erf GELU (never the tanh approximation), LayerNorm with eps 1e-5, L2 normalisation, the
 * binary32 dot product and the clamped score scale.
 */
class ClmMathTest {

    @Test
    fun `erf matches the mathematical values`() {
        // Reference values from the complementary error function's series (and math.erf in CPython).
        assertEquals(0.0, ClmMath.erf(0.0), 0.0)
        assertEquals(0.5204998778130465, ClmMath.erf(0.5), 1e-15)
        assertEquals(0.8427007929497149, ClmMath.erf(1.0), 1e-15)
        assertEquals(-0.8427007929497149, ClmMath.erf(-1.0), 1e-15)
        assertEquals(0.9953222650189527, ClmMath.erf(2.0), 1e-15)
        assertEquals(0.9999779095030014, ClmMath.erf(3.0), 1e-15)
        assertEquals(0.9999999845827421, ClmMath.erf(4.0), 1e-14)
        assertEquals(1.0, ClmMath.erf(6.0), 0.0)
        assertEquals(-1.0, ClmMath.erf(-40.0), 0.0)
    }

    @Test
    fun `gelu is the exact erf form`() {
        val values = floatArrayOf(0f, 1f, -1f, 2f, -2f, 3.5f)
        ClmMath.geluInPlace(values, values.size)
        assertEquals(0.0, values[0].toDouble(), 0.0)
        assertEquals(0.8413447460685429, values[1].toDouble(), 1e-6)
        assertEquals(-0.15865525393145713, values[2].toDouble(), 1e-6)
        assertEquals(1.954499736103642, values[3].toDouble(), 1e-6)
        assertEquals(-0.04550026389635842, values[4].toDouble(), 1e-6)
        assertEquals(3.499185800552368, values[5].toDouble(), 1e-5)

        // The tanh approximation differs by ~1e-4 at x = 2: this code must not be it.
        val x = 2.0
        val tanh = 0.5 * x * (1.0 + kotlin.math.tanh(kotlin.math.sqrt(2.0 / Math.PI) * (x + 0.044715 * x * x * x)))
        assertTrue(
            "exact erf (${values[3]}) and the tanh approximation ($tanh) must be distinguishable",
            abs(tanh - values[3]) > 5e-5,
        )
    }

    @Test
    fun `l2 normalisation returns a unit vector in the same direction`() {
        val v = floatArrayOf(3f, 4f)
        val norm = ClmMath.l2NormalizeInPlace(v, 2)
        assertEquals(5.0, norm.toDouble(), 1e-6)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), v, 1e-6f)

        val big = FloatArray(4096) { (it % 7) - 3f }
        ClmMath.l2NormalizeInPlace(big, big.size)
        var sum = 0.0
        for (value in big) sum += value.toDouble() * value
        assertEquals(1.0, sum, 1e-6)
    }

    @Test
    fun `layernorm with unit weights gives zero mean and unit variance`() {
        val n = 1536
        val x = FloatArray(n) { (it % 13) - 6f }
        val gamma = FloatArray(n) { 1f }
        val beta = FloatArray(n) { 0f }
        val out = FloatArray(n)
        ClmMath.layerNorm(x, n, gamma, beta, ClmHeads.LN_EPS, out, FloatArray(n))
        var mean = 0.0
        for (value in out) mean += value
        assertEquals(0.0, mean / n, 1e-6)
        var variance = 0.0
        for (value in out) variance += (value - mean / n).toDouble() * (value - mean / n)
        assertEquals(1.0, variance / n, 1e-5)
    }

    @Test
    fun `layernorm keeps its eps and its weights`() {
        // A constant vector has zero variance: without the eps the division would be 0/0.
        val n = 4
        val x = FloatArray(n) { 2f }
        val out = FloatArray(n)
        ClmMath.layerNorm(x, n, FloatArray(n) { 1f }, FloatArray(n) { 0f }, ClmHeads.LN_EPS, out, FloatArray(n))
        for (value in out) assertEquals(0.0, value.toDouble(), 1e-6)

        val beta = floatArrayOf(1f, 2f, 3f, 4f)
        ClmMath.layerNorm(x, n, FloatArray(n) { 1f }, beta, ClmHeads.LN_EPS, out, FloatArray(n))
        assertArrayEquals(beta, out, 1e-6f)
    }

    @Test
    fun `matvec accumulates in binary32 and adds the bias`() {
        val w = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val x = floatArrayOf(1f, 2f)
        val b = floatArrayOf(0.25f, -0.25f)
        val out = FloatArray(2)
        ClmMath.matvecAdd(w, 2, 2, x, b, out)
        assertEquals(0.1 + 0.2 * 2 + 0.25, out[0].toDouble(), 1e-6)
        assertEquals(0.3 + 0.4 * 2 - 0.25, out[1].toDouble(), 1e-6)
    }

    @Test
    fun `the score scale is the clamped exponential of the stored logit_scale`() {
        assertEquals(100f, ClmMath.scale(4.61325f, 100f), 0f)
        assertEquals(100f, ClmMath.scale(6.0f, 100f), 0f)
        assertEquals(kotlin.math.exp(1.0).toFloat(), ClmMath.scale(1f, 100f), 1e-6f)
    }

    @Test
    fun `dot is the binary32 dot product`() {
        assertEquals(0f, ClmMath.dot(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f), 2), 0f)
        assertEquals(32f, ClmMath.dot(floatArrayOf(1f, 2f, 3f), floatArrayOf(2f, 3f, 8f), 3), 1e-6f)
        val a = FloatArray(512) { 1f / 512f }
        assertEquals("a unit-vector dot against itself", 1.0 / 512.0, ClmMath.dot(a, a, 512).toDouble(), 1e-6)
    }

    @Test
    fun `a unit projection against itself is a cosine of one`() {
        val tensors = ClmWeights.tensors()
        val heads = ClmHeads.of(
            ClmWeights.head(tensors, ClmHeads.STATE_PREFIX),
            ClmWeights.head(tensors, ClmHeads.ACTION_PREFIX),
            logitScale = 4.61325f,
        )
        val v = heads.stateVector(FloatArray(4096) { (it % 11) - 5f })
        assertEquals("512-d projections", 512, v.size)
        assertEquals(1.0, sqrt(v.fold(0.0) { acc, x -> acc + x.toDouble() * x }), 1e-5)
        assertEquals("cos(v, v) = 1, so the score is the scale", 100f, heads.score(v, v), 1e-3f)
        val negative = FloatArray(v.size) { -v[it] }
        assertEquals("cos(v, -v) = -1", -100f, heads.score(v, negative), 1e-3f)
    }
}
