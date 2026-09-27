package dev.operator.core.decide

import dev.operator.core.api.BackendId
import dev.operator.core.api.DecisionState
import dev.operator.core.api.Question
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §5.3, §F4: BONSAI_LOGPROB scores letter (and yes/no) labels over `LlmPort.labelLogits`. */
class BonsaiLogprobBackendTest {

    private val state = DecisionState(context = "A chat screen with two buttons.", screenHash = 11L)

    private fun choice(kind: String, vararg options: String) = Question.Choice(
        kind = dev.operator.core.api.DecisionKind(kind),
        instructions = "Pick one option.",
        options = options.associateWith { it },
    )

    @Test
    fun `a low-stakes choice is one forward pass with A-Z labels`() {
        var seenLabels: List<List<Int>>? = null
        val llm = FakeLlmPort(
            rowsFor = { _, labelTokenIds, _ -> seenLabels = labelTokenIds; List(2) { floatArrayOf(2f, 1f) } },
        )
        val backend = BonsaiLogprobBackend(llm, modelId = "sha256-model")

        val scores = runBlocking { backend.rawScores(state, choice("route.api_or_ui", "Use the API", "Drive the UI")) }

        assertArrayEquals(floatArrayOf(2f, 1f), scores, 1e-6f)
        assertEquals(1, llm.labelCalls.size)
        assertEquals(listOf(listOf(0), listOf(1)), seenLabels)
        assertTrue(llm.labelCalls.first().promptParts.first().startsWith(state.context))
        assertTrue(llm.labelCalls.first().promptParts.first().contains("Pick one option."))
    }

    @Test
    fun `a yes-no question uses the yes and no labels`() {
        val llm = FakeLlmPort(rowsFor = { _, _, _ -> List(2) { floatArrayOf(1f, 0f) } })
        val backend = BonsaiLogprobBackend(llm, modelId = "sha256-model")

        runBlocking {
            backend.rawScores(state, Question.YesNo(Kinds.TASK_DONE, "Is the task done?"))
        }

        assertEquals(listOf(listOf(0), listOf(1)), llm.labelCalls.first().labelTokenIds)
        assertTrue(llm.labelCalls.first().promptParts.last().contains("yes"))
    }

    @Test
    fun `a high-stakes kind is averaged over two option orderings`() {
        val llm = FakeLlmPort(
            rowsFor = { _, _, call -> if (call == 0) List(2) { floatArrayOf(3f, 1f) } else List(2) { floatArrayOf(0f, 3f) } },
        )
        val backend = BonsaiLogprobBackend(llm, modelId = "sha256-model")
        val question = choice("action.irreversible", "Delete for everyone", "Cancel")

        val scores = runBlocking { backend.rawScores(state, question) }

        // Pass 1 order [delete, cancel] → [3, 1]; pass 2 order [cancel, delete] → [0, 3] by position
        // → option scores [3, 0]; averaged per option → [3, 0.5].
        assertArrayEquals(floatArrayOf(3f, 0.5f), scores, 1e-6f)
        assertEquals(2, llm.labelCalls.size)
    }

    @Test
    fun `the calibration key is the versioned tuple`() {
        val backend = BonsaiLogprobBackend(FakeLlmPort(rowsFor = { _, _, _ -> emptyList() }), modelId = "sha256-model")

        assertEquals(
            CalibrationKey(BackendId.BONSAI_LOGPROB, "sha256-model", null, BONSAI_LOGPROB_RECIPE_VERSION),
            backend.calibrationKey,
        )
    }

    @Test
    fun `supports rejects a question with more than 26 options`() {
        val backend = BonsaiLogprobBackend(FakeLlmPort(rowsFor = { _, _, _ -> emptyList() }), modelId = "sha256-model")
        val tooMany = choice("ui.target", *Array(27) { "option $it" })

        assertFalse(backend.supports(tooMany))
        assertTrue(backend.supports(choice("ui.target", "one", "two")))
    }
}
