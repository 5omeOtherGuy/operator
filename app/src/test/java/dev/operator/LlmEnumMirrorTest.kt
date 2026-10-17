package dev.operator

import org.junit.Assert.assertEquals
import org.junit.Test
import dev.operator.core.api.KvType as CoreKvType
import dev.operator.core.api.LlmBackend as CoreLlmBackend
import dev.operator.core.api.ModelRole as CoreModelRole
import dev.operator.llm.KvType as AidlKvType
import dev.operator.llm.LlmBackend as AidlLlmBackend
import dev.operator.llm.ModelRole as AidlModelRole

/**
 * The `:llm-api` enums mirror `dev.operator.core.api` field by field (FOUNDATION §2.3): the
 * Parcelables carry them by name, and the composition root (I1) maps them onto the core types. A
 * constant added or renamed on one side only would break that mapping at runtime, so both sides must
 * keep the same constants in the same order.
 */
class LlmEnumMirrorTest {

    @Test
    fun `ModelRole mirrors the core type`() {
        assertEquals(CoreModelRole.entries.map { it.name }, AidlModelRole.entries.map { it.name })
    }

    @Test
    fun `LlmBackend mirrors the core type`() {
        assertEquals(CoreLlmBackend.entries.map { it.name }, AidlLlmBackend.entries.map { it.name })
    }

    @Test
    fun `KvType mirrors the core type`() {
        assertEquals(CoreKvType.entries.map { it.name }, AidlKvType.entries.map { it.name })
    }
}
