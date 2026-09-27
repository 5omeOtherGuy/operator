package dev.operator.core.grammar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sanity for the test-only GBNF recogniser, so the grammar tests above are not vacuously true. */
class GbnfMatcherTest {

    @Test
    fun `recognises literals, classes, groups and repetitions`() {
        val g = GbnfMatcher.parse(
            """
            root ::= "a" [b-d]{1,2} "e"?
            """.trimIndent(),
        )
        assertTrue(g.accepts("ab"))
        assertTrue(g.accepts("abd"))
        assertTrue(g.accepts("abde"))
        assertTrue(g.accepts("ac"))
        assertFalse(g.accepts("a")) // [b-d]{1,2} needs at least one
        assertFalse(g.accepts("ae")) // 'e' is not in [b-d]
        assertFalse(g.accepts("abdc")) // trailing 'c'
        assertFalse(g.accepts("zab"))
    }

    @Test
    fun `recognises alternatives, references and star`() {
        val g = GbnfMatcher.parse(
            """
            root ::= head tail*
            head ::= "x" | "y"
            tail ::= [0-9]
            """.trimIndent(),
        )
        assertTrue(g.accepts("x"))
        assertTrue(g.accepts("y012"))
        assertFalse(g.accepts("z0"))
    }

    @Test
    fun `a coordinate-looking object fails a grammar without those fields`() {
        val g = GbnfMatcher.parse(
            """
            root ::= "{\"a\":\"tap\",\"i\":" [0-9]+ "}"
            """.trimIndent(),
        )
        assertTrue(g.accepts("""{"a":"tap","i":9}"""))
        assertFalse(g.accepts("""{"a":"tap","x":10,"y":20}"""))
    }
}
