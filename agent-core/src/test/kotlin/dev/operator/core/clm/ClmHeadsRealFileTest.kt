package dev.operator.core.clm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The published head, end to end (FOUNDATION §5.4; 03§F6.1): `tools/clm/convert_heads.py` converts the
 * pinned `CLM_v0.1-8B.pt` (sha256 b2b4a8c9…) to safetensors, and the Kotlin loader reads it.
 *
 * The 75.5 MB file is never committed, so the test runs only when the environment points at it:
 * `clm-gates.yml`'s `heads` job sets `CLM_HEADS_FILE` (the converted file) and `CLM_HEADS_GOLDEN`
 * (`make_goldens.py --real-heads` on the same checkpoint, computed from the `.pt`'s own storages by the
 * Python reference). Without those variables the test reports itself as skipped, which is what the
 * push-triggered `jvm` job of `build.yml` does.
 */
class ClmHeadsRealFileTest {

    @Test
    fun `the converted published heads load and match the Python reference`() {
        val headsFile = System.getenv("CLM_HEADS_FILE")
        val goldenFile = System.getenv("CLM_HEADS_GOLDEN")
        assumeTrue(
            "CLM_HEADS_FILE / CLM_HEADS_GOLDEN are not set; the heads job of clm-gates.yml sets them",
            headsFile != null && goldenFile != null,
        )

        val golden = MinimalJson
            .parse(Files.readString(Path.of(goldenFile!!), StandardCharsets.UTF_8))
            .entries()
        val inputs = golden.getValue("inputs").floatMatrix()
        val candidates = golden.getValue("candidates").floatMatrix()
        val expectedStates = golden.getValue("expected_state_vectors").floatMatrix()
        val expectedActions = golden.getValue("expected_action_vectors").floatMatrix()
        val expectedScores = golden.getValue("expected_scores").floatMatrix()
        val expectedScale = golden.getValue("scale").number().toFloat()
        val expectedLogitScale = golden.getValue("logit_scale").number().toFloat()

        val heads = ClmHeads.open(Path.of(headsFile!!))
        assertEquals("the clamped scale of the published head", expectedScale, heads.scale, 1e-6f)
        assertEquals("logit_scale read from the converted metadata", expectedLogitScale, heads.logitScale, 0f)
        assertEquals("gelu_erf", heads.metadata["activation"])
        assertEquals("1536", heads.metadata["width"])
        assertEquals(ClmHeads.RECIPE_VERSION, heads.metadata["recipe_version"])
        assertEquals(
            "source_sha256 must name the pinned checkpoint",
            "b2b4a8c9c2d39263eff78a351eb909a342ce9b3bf21a3f07c1d1bf15f1c4eda5",
            heads.metadata["source_sha256"],
        )

        val stateVectors = inputs.map { heads.stateVector(it) }
        val actionVectors = candidates.map { heads.actionVector(it) }
        stateVectors.forEachIndexed { i, vector ->
            assertArrayEquals("published state projection $i", expectedStates[i], vector, 1e-4f)
        }
        actionVectors.forEachIndexed { j, vector ->
            assertArrayEquals("published action projection $j", expectedActions[j], vector, 1e-4f)
        }
        var spread = 0f
        expectedScores.forEachIndexed { i, row ->
            val actual = heads.scores(stateVectors[i], actionVectors)
            assertArrayEquals("published scores of state $i", row, actual, 1e-4f)
            spread = maxOf(spread, actual.max() - actual.min())
        }
        assertTrue("the published head must discriminate the fixtures, spread=$spread", spread > 1e-3f)
    }
}
