package dev.operator.core.clm

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/*
 * dev.operator.core.clm — the Kotlin CLM-8B head path.
 *
 * Design: FOUNDATION §5.3 (the CLM-8B code and gates ship in M1 as tested code; the backend stays off
 * until M2), §5.4 (the head recipe), ADR-0007 (decisions 2, 7, 8: the schema port, the recipe-exact
 * extraction path and the fp32 heads), docs/design/research/03-decide.md §F1.4–F1.12 (the checkpoint's
 * own architecture, the forward pass and the token/truncation recipe), §F6.1–F6.4 (the safetensors
 * export and the Kotlin fp32 reference).
 *
 * The three files of the path:
 *  - [ClmSchema]  — the port of CLM's `schema.py`: state/candidate texts and the token recipe;
 *  - [SafetensorsFile] — the reader for the exported heads (`tools/clm/convert_heads.py`);
 *  - [ClmHeads]   — the fp32 forward pass (this file holds its primitives).
 */

/**
 * The fp32 primitives of the head recipe. Every operation is an IEEE-754 binary32 operation applied
 * in the order written here, and every dot product accumulates in binary32 in index order, so the
 * Python reference (`tools/clm/make_goldens.py`) reproduces the same bits: it rounds to fp32 after
 * each primitive and accumulates in index order too. `a * b` is a rounded binary32 multiply before
 * the add — no fused multiply-add (the JVM contract; `-XX:+UseFMA` / a contraction-enabled C2 would
 * break the equality the golden tests assert).
 */
object ClmMath {
    /** `1/sqrt(2)` as binary32; the exact-erf GELU's argument factor (03§F1.5). */
    const val INV_SQRT2: Float = 0.70710678f

    private const val TWO_OVER_SQRT_PI = 1.1283791670955126
    private const val SQRT_PI = 1.772453850905516
    private const val TAYLOR_MAX = 3.0
    private const val SATURATION = 6.0
    private const val TAYLOR_TERMS = 48
    private const val CF_TERMS = 60

    /**
     * `out[r] = Σ_k w[r * cols + k] * x[k] + b[r]`, binary32 accumulation in index order.
     */
    fun matvecAdd(w: FloatArray, rows: Int, cols: Int, x: FloatArray, b: FloatArray, out: FloatArray) {
        require(w.size >= rows * cols) { "weights hold ${w.size} values, need ${rows * cols}" }
        require(x.size >= cols) { "input holds ${x.size} values, need $cols" }
        require(b.size >= rows && out.size >= rows) { "bias/out must hold $rows values" }
        for (r in 0 until rows) {
            var acc = 0f
            val off = r * cols
            for (k in 0 until cols) acc += w[off + k] * x[k]
            out[r] = acc + b[r]
        }
    }

    /** Exact-erf GELU [03§F1.5]: `0.5 * x * (1 + erf(x / sqrt(2)))`, in place. */
    fun geluInPlace(v: FloatArray, n: Int) {
        for (i in 0 until n) {
            val x = v[i]
            val e = erf((x * INV_SQRT2).toDouble()).toFloat()
            v[i] = (0.5f * x) * (1.0f + e)
        }
    }

    /**
     * LayerNorm over [n] with the biased variance and the weights `gamma`/`beta` [03§F1.4]:
     * `(x - mean) / sqrt(var + eps) * gamma + beta`. [work] is scratch of length [n].
     */
    fun layerNorm(
        x: FloatArray,
        n: Int,
        gamma: FloatArray,
        beta: FloatArray,
        eps: Float,
        out: FloatArray,
        work: FloatArray,
    ) {
        require(x.size >= n && out.size >= n && work.size >= n)
        var mean = 0f
        for (i in 0 until n) mean += x[i]
        mean /= n.toFloat()
        var variance = 0f
        for (i in 0 until n) {
            val d = x[i] - mean
            work[i] = d
            variance += d * d
        }
        variance /= n.toFloat()
        val invStd = (1.0 / sqrt((variance + eps).toDouble())).toFloat()
        for (i in 0 until n) out[i] = (work[i] * invStd) * gamma[i] + beta[i]
    }

    /**
     * L2-normalises [v] in place and returns the norm before the division (the caller's raw encoder
     * state is normalised once, as `l2()` in the reference server and the training precompute, §F1.8).
     */
    fun l2NormalizeInPlace(v: FloatArray, n: Int): Float {
        var sum = 0f
        for (i in 0 until n) sum += v[i] * v[i]
        val norm = sqrt(sum.toDouble()).toFloat()
        for (i in 0 until n) v[i] = v[i] / norm
        return norm
    }

    /** `Σ_i a[i] * b[i]`, binary32 accumulation in index order. */
    fun dot(a: FloatArray, b: FloatArray, n: Int): Float {
        require(a.size >= n && b.size >= n)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    /** `min(exp(logit_scale), scale_clamp)` [03§F1.6]: the stored 4.61325 clamps to exactly 100. */
    fun scale(logitScale: Float, scaleClamp: Float): Float =
        min(exp(logitScale.toDouble()).toFloat(), scaleClamp)

    /**
     * `erf(x)` in double precision, the same algorithm as the Python reference:
     * a binomial-free Taylor series for `|x| < 3`, the continued fraction of the complementary
     * error function for `3 <= |x| < 6`, and saturation beyond `6` (`1 - erf(6) < 2.3e-17`).
     * The absolute error is below 1e-12 everywhere, i.e. far below one binary32 ulp of the GELU.
     */
    fun erf(x: Double): Double {
        val ax = abs(x)
        if (ax >= SATURATION) return if (x < 0) -1.0 else 1.0
        if (ax < TAYLOR_MAX) {
            val x2 = x * x
            var term = x
            var sum = 0.0
            for (n in 0 until TAYLOR_TERMS) {
                sum += term / (2.0 * n + 1.0)
                term *= -x2 / (n + 1.0)
            }
            return TWO_OVER_SQRT_PI * sum
        }
        // erfc(x) = exp(-x^2) / sqrt(pi) * 1 / (x + (1/2) / (x + 1 / (x + (3/2) / (x + 2 / (...))))).
        var fraction = 0.0
        for (n in CF_TERMS downTo 1) fraction = (n / 2.0) / (ax + fraction)
        val erfc = exp(-ax * ax) / (SQRT_PI * (ax + fraction))
        val e = 1.0 - erfc
        return if (x < 0) -e else e
    }
}
