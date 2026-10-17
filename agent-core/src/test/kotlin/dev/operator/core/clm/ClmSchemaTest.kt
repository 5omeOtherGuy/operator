package dev.operator.core.clm

import dev.operator.core.api.DecisionKind
import dev.operator.core.api.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The schema port against the Python reference (FOUNDATION §5.3-5.4; ADR-0007 decisions 2 and 7):
 * `state_text`, the candidate texts of all four question kinds, and the token recipe — the state keeps
 * its tail, a candidate keeps its head, and the injected tokenizer decides what a token is.
 */
class ClmSchemaTest {

    private val kind = DecisionKind("test.kind")

    private fun question(golden: SchemaGolden): Question<*> = when (golden.kind) {
        "yesno" -> Question.YesNo(kind, golden.instructions)
        "choice" -> Question.Choice(kind, golden.instructions, LinkedHashMap(golden.options!!))
        "score" -> Question.Score(kind, golden.instructions, golden.levels!!)
        "rank" -> Question.Rank(kind, golden.instructions, golden.candidates!!, 2)
        else -> error("unknown golden kind ${golden.kind}")
    }

    @Test
    fun `schema goldens are present and cover every question kind`() {
        val kinds = SchemaGolden.INSTANCE.map { it.kind }.toSet()
        assertEquals(
            "the goldens must pin every Question kind of the frozen API",
            setOf("yesno", "choice", "score", "rank"),
            kinds,
        )
    }

    @Test
    fun `state and candidate texts equal the Python reference`() {
        for (golden in SchemaGolden.INSTANCE) {
            assertEquals(
                "${golden.name}: state_text is context + \"\\n\\n\" + instructions",
                golden.stateText,
                ClmSchema.stateText(golden.context, golden.instructions),
            )
            assertEquals(
                "${golden.name}: candidate texts",
                golden.candidateTexts,
                ClmSchema.candidateTexts(question(golden)),
            )
        }
    }

    @Test
    fun `token sequences equal the Python reference`() {
        for (golden in SchemaGolden.INSTANCE) {
            assertEquals(
                "${golden.name}: the state keeps its tail at ${golden.maxTokens} tokens",
                golden.stateTokens,
                ClmSchema.stateTokens(ByteTokenizer, golden.context, golden.instructions, golden.maxTokens),
            )
            val candidates = ClmSchema.candidateTexts(question(golden))
            assertEquals(
                "${golden.name}: candidates keep their head at ${golden.maxTokens} tokens",
                golden.candidateTokens,
                candidates.map { ClmSchema.candidateTokens(ByteTokenizer, it, golden.maxTokens) },
            )
        }
    }

    @Test
    fun `the default cap is 2048 tokens on both sides of a sequence`() {
        assertEquals(2048, ClmSchema.MAX_TOKENS)

        // The cap applies to tokens: these ids are deliberately not the byte values of a text.
        val ids = (0 until 3000).toList()
        val tail = ClmSchema.keepTail(ids)
        val head = ClmSchema.keepHead(ids)
        assertEquals(2048, tail.size)
        assertEquals(2048, head.size)
        assertEquals("a state keeps the last ids", (952..2999).toList(), tail)
        assertEquals("a candidate keeps the first ids", (0..2047).toList(), head)

        // An over-long state text through the byte tokenizer is capped the same way.
        val long = "x".repeat(3000)
        assertEquals(2048, ClmSchema.stateTokens(ByteTokenizer, long, "").size)
        assertEquals(2048, ClmSchema.candidateTokens(ByteTokenizer, long).size)
    }

    @Test
    fun `truncation cuts tokens, not characters`() {
        // Three "words", two tokens each, cap 3: the tail is the last three tokens, not the last
        // three characters of the text.
        val tokenizer = WordTokenizer(mapOf("alpha" to 1, "beta" to 2, "gamma" to 3))
        val ids = tokenizer.encode("alpha beta gamma")
        assertEquals(listOf(1, 2, 3), ids)
        assertEquals(listOf(2, 3), ClmSchema.keepTail(ids, 2))
        assertEquals(listOf(1, 2), ClmSchema.keepHead(ids, 2))
        assertEquals(listOf(1, 2, 3), ClmSchema.keepTail(ids, 3))
    }

    @Test
    fun `well formed candidate texts`() {
        val yesNo = Question.YesNo(kind, "Is the switch off?")
        assertEquals(
            listOf(
                "true: Yes. This is true: Is the switch off?",
                "false: No. This is false: Is the switch off?",
            ),
            ClmSchema.candidateTexts(yesNo),
        )
        assertEquals(listOf("true", "false"), ClmSchema.yesNoKeys())

        // A choice option with a blank description falls back to its key (03§F1.12).
        val choice = Question.Choice(kind, "Which row?", linkedMapOf("a" to "Wi-Fi", "b" to "  ", "c" to ""))
        assertEquals(listOf("Wi-Fi", "b", "c"), ClmSchema.candidateTexts(choice))
    }

    @Test
    fun `longest common prefix is the reuse boundary of the KV cache`() {
        assertEquals(3, ClmSchema.longestCommonPrefix(listOf(1, 2, 3, 4), listOf(1, 2, 3, 9)))
        assertEquals(0, ClmSchema.longestCommonPrefix(listOf(1), listOf(2)))
        assertEquals(0, ClmSchema.longestCommonPrefix(emptyList(), listOf(1)))
        assertEquals(2, ClmSchema.longestCommonPrefix(listOf(7, 8), listOf(7, 8)))
    }

    @Test
    fun `a non positive cap is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ClmSchema.keepTail(listOf(1), 0) }
        assertThrows(IllegalArgumentException::class.java) { ClmSchema.keepHead(listOf(1), 0) }
    }
}
