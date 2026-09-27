package dev.operator.core.grammar

import dev.operator.core.api.ScrollDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C10: the short form is a strict object; coordinates and unknown verbs/keys are failures. */
class ShortActionTest {

    private fun ok(text: String): ShortAction = (ShortForm.parse(text) as ShortFormParse.Ok).action

    private fun err(text: String): String = (ShortForm.parse(text) as ShortFormParse.Err).reason

    @Test
    fun `parses the catalogue verbs`() {
        assertEquals(ShortAction.Tap(9), ok("""{"a":"tap","i":9}"""))
        assertEquals(ShortAction.LongPress(3), ok("""{"a":"long","i":3}"""))
        assertEquals(ShortAction.Type(9, "hi"), ok("""{"a":"type","i":9,"text":"hi"}"""))
        assertEquals(ShortAction.Scroll(5, ScrollDirection.DOWN), ok("""{"a":"scroll","i":5,"dir":"down"}"""))
        assertEquals(ShortAction.Nav(NavVerb.BACK), ok("""{"a":"back"}"""))
        assertEquals(ShortAction.Nav(NavVerb.QUICK_SETTINGS), ok("""{"a":"quick_settings"}"""))
        assertEquals(ShortAction.Wait(null), ok("""{"a":"wait"}"""))
        assertEquals(ShortAction.Done("done"), ok("""{"a":"done","answer":"done"}"""))
        assertEquals(ShortAction.Ask("what?"), ok("""{"a":"ask","q":"what?"}"""))
    }

    @Test
    fun `rejects coordinates and unknown keys`() {
        assertTrue("the coordinate form must not parse", err("""{"a":"tap","x":10,"y":20}""").isNotEmpty())
        assertTrue(err("""{"a":"tap","i":1,"snapshot":14}""").contains("unexpected"))
        assertTrue(err("""{"a":"tap"}""").contains("missing"))
    }

    @Test
    fun `rejects unknown verbs and out-of-range values`() {
        assertTrue(err("""{"a":"swipe","i":1}""").contains("unknown verb"))
        assertTrue(err("""{"a":"scroll","i":5,"dir":"sideways"}""").contains("direction"))
        assertTrue(err("""{"a":"tap","i":"1"}""").contains("integer"))
        assertTrue(err("""{"a":"type","i":9,"text":"${"x".repeat(201)}"}""").contains("longer"))
        assertTrue(err("not json").isNotEmpty())
    }

    @Test
    fun `encode round-trips through parse`() {
        val actions = listOf(
            ShortAction.Tap(4),
            ShortAction.Type(9, "hi \"there\"\n"),
            ShortAction.Scroll(5, ScrollDirection.UP),
            ShortAction.Nav(NavVerb.HOME),
            ShortAction.Done("all set"),
        )
        for (action in actions) {
            assertEquals(action, ok(ShortForm.encode(action)))
        }
    }
}
