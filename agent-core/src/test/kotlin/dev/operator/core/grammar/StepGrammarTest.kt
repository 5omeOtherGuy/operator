package dev.operator.core.grammar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-step GBNF is generated from the ToolCall catalogue and the current snapshot (§8.3). The
 * acceptance test drives the generator over the FOUNDATION §6.2 screen and checks that every valid
 * short-form example for the catalogue is in the language and that a coordinate form is not.
 */
class StepGrammarTest {

    private val pkg = "com.google.android.apps.messaging"

    private fun grammar(banned: Set<String> = emptySet()): GbnfMatcher = GbnfMatcher.parse(
        StepGrammar(
            snapshot = TestScreens.messagesScreen(),
            appSet = setOf(pkg),
            appAliases = mapOf("Messages" to pkg),
            bannedActionKeys = banned,
        ).build().render(),
    )

    private val validExamples = listOf(
        """{"a":"tap","i":1}""",
        """{"a":"tap","i":3}""",
        """{"a":"tap","i":4}""",
        """{"a":"tap","i":10}""",
        """{"a":"long","i":3}""",
        """{"a":"type","i":9,"text":"Running 10 min late"}""",
        """{"a":"scroll","i":5,"dir":"down"}""",
        """{"a":"scroll","i":5,"dir":"up"}""",
        """{"a":"back"}""",
        """{"a":"home"}""",
        """{"a":"media","act":"play"}""",
        """{"a":"open","app":"Messages"}""",
        """{"a":"wait"}""",
        """{"a":"done","answer":"sent"}""",
        """{"a":"ask","q":"which chat?"}""",
    )

    private val invalidExamples = listOf(
        // §6.2: [11] is disabled, so it is not in the tap enum.
        """{"a":"tap","i":11}""",
        // §8.3: type only on an edit; [1] is a button.
        """{"a":"type","i":1,"text":"x"}""",
        // §8.3: tap only on clickable elements; [2] is text.
        """{"a":"tap","i":2}""",
        // C10: the model never emits coordinates.
        """{"a":"tap","x":10,"y":20}""",
        """{"a":"tap","i":1,"x":3}""",
        """{"a":"tap","i":1,"i":3}""",
        """{"a":"swipe","i":1}""",
        """{"a":"tap","i":"1"}""",
    )

    @Test
    fun `parses every valid short-form example`() {
        val g = grammar()
        for (example in validExamples) {
            assertTrue("the grammar must accept $example", g.accepts(example))
        }
    }

    @Test
    fun `rejects actions outside the catalogue and coordinate forms`() {
        val g = grammar()
        for (example in invalidExamples) {
            assertFalse("the grammar must reject $example", g.accepts(example))
        }
    }

    @Test
    fun `the coordinate form of the acceptance test is rejected`() {
        assertFalse(grammar().accepts("""{"a":"tap","x":10,"y":20}"""))
    }

    @Test
    fun `a banned action leaves the grammar`() {
        val banned = setOf("tap:3")
        val g = grammar(banned)
        assertTrue(g.accepts("""{"a":"tap","i":1}"""))
        assertFalse("a banned (state, action) pair is removed from the enum (§8.4)", g.accepts("""{"a":"tap","i":3}"""))
    }

    @Test
    fun `the disabled button and the edit field shape the verb enums`() {
        val step = StepGrammar(TestScreens.messagesScreen(), setOf(pkg))
        assertTrue(step.tapIndices.containsAll(listOf(1, 3, 4, 10)))
        assertFalse(step.tapIndices.contains(11))
        assertFalse(step.tapIndices.contains(9))
        assertFalse(step.tapIndices.contains(2))
        assertTrue(step.typeIndices == listOf(9))
        assertTrue(step.scrollIndices == listOf(5))
    }
}
