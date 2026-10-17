package dev.operator.core.clm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

/**
 * The acceptance test of the slice: the Kotlin fp32 head path equals the Python reference.
 *
 * `tools/clm/make_goldens.py` (numpy or pure-Python fp32) writes `head_goldens.json`: the seeded
 * weights' digest, the raw 4096-d inputs, the 512-d projections of both heads and the scaled cosine
 * scores. The weights themselves are rebuilt here from the same documented generator (seed 0,
 * splitmix64, the checkpoint's tensor order) because the real shapes are 75.5 MB of fp32 — over the
 * 30 MB test-resource budget — and the file is written to a scratch safetensors file so the loader
 * under test reads exactly the layout `tools/clm/convert_heads.py` produces.
 *
 * The comparison is 1e-4 absolute on the projections and on the scores: `scale` is 100, so 1e-4 on a
 * score is 1e-6 on a cosine, and any real break — a GELU that is the tanh approximation, a LayerNorm
 * without its eps, a missing L2 normalisation, a transposed weight — moves it by far more.
 */
class ClmHeadsGoldenTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val goldens = HeadGoldens.INSTANCE

    private fun headsFile(): Path {
        val spec = SafetensorsSpec().recipeMeta(goldens.logitScale)
        val tensors = weights
        for (key in ClmWeights.STREAM_ORDER) {
            spec.tensor(key, ClmWeights.SHAPES.getValue(key), tensors.getValue(key))
        }
        return spec.write(temp.newFile("clm-heads.safetensors").toPath())
    }

    @Test
    fun `the seeded weights are the ones the Python reference used`() {
        // The digest covers all 18,887,680 values in the documented order: if the JVM generator drifts
        // from the Python one by a single bit, this fails before any score is compared.
        assertEquals(goldens.weightsSha256, ClmWeights.sha256(weights))
        assertEquals("the golden file must carry the real shapes", 16, goldens.shapes.size)
        assertEquals(listOf(1536L, 4096L), goldens.shapes.getValue("state_head.inp.weight"))
        assertEquals(listOf(512L, 1536L), goldens.shapes.getValue("state_head.out.weight"))
    }

    @Test
    fun `projections equal the Python goldens within 1e-4`() {
        val heads = ClmHeads.open(headsFile())
        assertEquals("logit_scale 4.61325 clamps to exactly 100", goldens.scale, heads.scale, 0f)

        val stateVectors = goldens.inputs.map { heads.stateVector(it) }
        val actionVectors = goldens.candidates.map { heads.actionVector(it) }

        stateVectors.forEachIndexed { i, vector ->
            assertArrayEquals("state projection $i", goldens.expectedStateVectors[i], vector, 1e-4f)
        }
        actionVectors.forEachIndexed { j, vector ->
            assertArrayEquals("action projection $j", goldens.expectedActionVectors[j], vector, 1e-4f)
        }
    }

    @Test
    fun `scores equal the Python goldens within 1e-4`() {
        val heads = ClmHeads.open(headsFile())
        val stateVectors = goldens.inputs.map { heads.stateVector(it) }
        val actionVectors = goldens.candidates.map { heads.actionVector(it) }

        var spread = 0f
        var worst = 0.0
        goldens.expectedScores.forEachIndexed { i, row ->
            val actual = heads.scores(stateVectors[i], actionVectors)
            assertArrayEquals("scores of state $i", row, actual, 1e-4f)
            for (j in row.indices) worst = maxOf(worst, kotlin.math.abs(row[j].toDouble() - actual[j].toDouble()))
            spread = maxOf(spread, actual.max() - actual.min())
        }
        println("ClmHeadsGoldenTest: largest |Kotlin - Python| score difference = $worst (bar 1e-4)")
        // A golden set whose scores are all equal would pass trivially; this one must discriminate.
        assertTrue("the fixture scores must differ across candidates, spread=$spread", spread > 1e-3f)
    }

    @Test
    fun `the golden comparison is sensitive to the input normalisation and to the weights`() {
        val heads = ClmHeads.open(headsFile())

        // Feeding the raw 60-ish-norm state straight into the head (no L2 normalisation, 03§F1.8) is a
        // different projection, and the 1e-4 tolerance must see it.
        val right = heads.stateVector(goldens.inputs[0])
        val unnormalised = heads.stateHead.project(goldens.inputs[0].copyOf())
        var moved = 0f
        for (i in right.indices) moved = maxOf(moved, kotlin.math.abs(right[i] - unnormalised[i]))
        assertTrue("an unnormalised input must move the projection, largest change $moved", moved > 1e-2f)

        // And a different head is a different score.
        val tensors = ClmWeights.tensors()
        val clean = ClmHeads.of(
            ClmWeights.head(tensors, ClmHeads.STATE_PREFIX),
            ClmWeights.head(tensors, ClmHeads.ACTION_PREFIX),
            goldens.logitScale,
        )
        val before = clean.score(clean.stateVector(goldens.inputs[0]), clean.actionVector(goldens.candidates[0]))

        val biases = tensors.getValue("${ClmHeads.ACTION_PREFIX}.out.bias")
        for (i in biases.indices) biases[i] += 50f
        val perturbed = ClmHeads.of(
            ClmWeights.head(tensors, ClmHeads.STATE_PREFIX),
            ClmWeights.head(tensors, ClmHeads.ACTION_PREFIX),
            goldens.logitScale,
        )
        val after = perturbed.score(perturbed.stateVector(goldens.inputs[0]), perturbed.actionVector(goldens.candidates[0]))
        assertTrue("a shifted action head must move the score, was $before now $after", kotlin.math.abs(after - before) > 1e-3f)
    }

    @Test
    fun `projections are unit vectors and the input is normalised inside the path`() {
        val heads = ClmHeads.open(headsFile())
        val vector = heads.stateVector(goldens.inputs[0])
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value
        assertEquals("the projection is L2-normalised", 1.0, kotlin.math.sqrt(sum), 1e-5)

        // The path normalises its input itself (03§F1.8), so scaling the raw state only changes the
        // last bits: the score must stay within the golden tolerance.
        val scaled = FloatArray(goldens.inputs[0].size) { goldens.inputs[0][it] * 8.0f }
        val scaledVector = heads.stateVector(scaled)
        assertArrayEquals(goldens.expectedStateVectors[0], scaledVector, 1e-3f)
    }

    private companion object {
        /** The 16 seeded tensors, rebuilt once for the whole class (75.5 MB, ~19M draws). */
        val weights: LinkedHashMap<String, FloatArray> by lazy { ClmWeights.tensors() }
    }
}
