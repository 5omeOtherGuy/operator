package dev.operator.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

/**
 * The §7.2 catalogue must cover the sealed `ToolCall` hierarchy exactly: one risk class per tool,
 * no tool without a class, no class without a tool. The test reflects over the sealed hierarchy, so a
 * new `ToolCall` subclass fails the build until it is catalogued (FOUNDATION §7.2).
 */
class ToolCatalogTest {

    private val toolSubclasses: Set<KClass<out ToolCall>> =
        ToolCall::class.sealedSubclasses.toSet()

    @Test
    fun `tool catalogue covers every ToolCall subclass`() {
        assertTrue("the sealed ToolCall hierarchy is empty", toolSubclasses.isNotEmpty())

        val missing = toolSubclasses - ToolCatalog.base.keys
        val unknown = ToolCatalog.base.keys - toolSubclasses

        assertTrue("ToolCall subclasses without a §7.2 class: ${missing.mapNotNull { it.simpleName }.sorted()}", missing.isEmpty())
        assertTrue("catalogue entries without a ToolCall: ${unknown.mapNotNull { it.simpleName }.sorted()}", unknown.isEmpty())
        assertEquals(toolSubclasses.size, ToolCatalog.base.size)
    }

    @Test
    fun `risers only raise catalogued tools`() {
        val unknown = ToolCatalog.risers.keys - ToolCatalog.base.keys
        assertTrue("risers for uncatalogued tools: ${unknown.mapNotNull { it.simpleName }.sorted()}", unknown.isEmpty())

        ToolCatalog.risers.forEach { (tool, raised) ->
            val base = ToolCatalog.base.getValue(tool)
            assertTrue(
                "riser for ${tool.simpleName} ($raised) does not raise the base class ($base)",
                raised.ordinal > base.ordinal,
            )
        }
    }

    @Test
    fun `catalogue spans R0 to R3 and pins the R3 row of section 7_2`() {
        assertEquals(
            "every risk class of §7.2 must have at least one tool",
            RiskClass.entries.toSet(),
            ToolCatalog.base.values.toSet(),
        )

        // The §7.2 R3 row: install_apk, uninstall, set_permission GRANTED (a riser) and reboot.
        val r3 = (ToolCatalog.base.filterValues { it == RiskClass.R3 }.keys +
            ToolCatalog.risers.filterValues { it == RiskClass.R3 }.keys)
            .mapNotNull { it.simpleName }
            .sorted()
        assertEquals(listOf("InstallApk", "Reboot", "SetPermission", "Uninstall"), r3)
    }
}
