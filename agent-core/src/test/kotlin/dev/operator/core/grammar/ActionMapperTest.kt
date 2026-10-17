package dev.operator.core.grammar

import dev.operator.core.api.ScrollDirection
import dev.operator.core.api.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C10: the host maps the short form onto a typed call and stamps the snapshot id; the model's own
 * coordinates and snapshot numbers are never read.
 */
class ActionMapperTest {

    private val pkg = "com.google.android.apps.messaging"
    private val screen = TestScreens.messagesScreen(id = 14)
    private val mapper = ActionMapper(screen, setOf(pkg), mapOf("Messages" to pkg))

    private fun mapped(action: ShortAction): ToolCall = (mapper.map(action) as ActionResult.Mapped).call

    private fun rejected(action: ShortAction): String = (mapper.map(action) as ActionResult.Rejected).reason

    @Test
    fun `stamps the snapshot id and the host element key`() {
        val click = mapped(ShortAction.Tap(10)) as ToolCall.Click
        assertEquals("the host stamps the snapshot id (§7.1, C10)", 14L, click.snapshotId)
        assertEquals(10, click.elementIndex)
        assertEquals(ElementKeyOf.node10(), click.elementKey)
        assertEquals("Add attachment", click.label)
    }

    @Test
    fun `maps the rest of the catalogue`() {
        assertEquals(14L, (mapped(ShortAction.Type(9, "hi")) as ToolCall.SetText).snapshotId)
        assertEquals(ScrollDirection.DOWN, (mapped(ShortAction.Scroll(5, ScrollDirection.DOWN)) as ToolCall.Scroll).direction)
        assertEquals(ToolCall.Back, mapped(ShortAction.Nav(NavVerb.BACK)))
        assertEquals(ToolCall.Media(dev.operator.core.api.MediaAction.PLAY), mapped(ShortAction.Media(dev.operator.core.api.MediaAction.PLAY)))
        assertEquals(ToolCall.LaunchApp(pkg), mapped(ShortAction.Open("Messages")))
        assertEquals(ToolCall.Finish("x"), mapped(ShortAction.Done("x")))
        assertEquals(ToolCall.AskOwner("q?"), mapped(ShortAction.Ask("q?")))
    }

    @Test
    fun `refuses an action that does not fit the element`() {
        assertTrue(rejected(ShortAction.Tap(11)).contains("disabled"))
        assertTrue(rejected(ShortAction.Tap(2)).contains("does not support"))
        assertTrue(rejected(ShortAction.Type(1, "x")).contains("edit element"))
        assertTrue(rejected(ShortAction.Tap(99)).contains("not on the current screen"))
        assertTrue(rejected(ShortAction.Open("Chrome")).contains("app set"))
    }

    /** The fixture's node 10 key, kept here so the assertion is explicit rather than reflective. */
    private object ElementKeyOf {
        fun node10() = TestScreens.messagesScreen(id = 14).nodes.first { it.index == 10 }.key
    }
}
