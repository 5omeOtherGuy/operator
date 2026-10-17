package dev.operator.llm

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ModelGuardsTest {
    @Test fun tensorAllowlist() {
        assertTrue(ModelGuards.allowed(listOf(41, 0), false))
        // g64 and g128 use type 42; only an independently verified g64 file may opt in.
        assertTrue(ModelGuards.allowed(listOf(42, 0), false, verifiedQ2G64 = true))
        assertFalse(ModelGuards.allowed(listOf(42), false))
        assertFalse(ModelGuards.allowed(listOf(43), false))
        assertTrue(ModelGuards.allowed(listOf(12, 14, 8, 0), true))
    }

    @Test fun prefixAndPayload() {
        assertEquals(2, ModelGuards.commonPrefix(listOf(1, 2, 3, 4), listOf(1, 2, 9)))
        assertTrue(runCatching { PayloadGuard.check("x".repeat(131_100)) }.isFailure)
    }

    @Test fun mismatchCannotLoad() {
        val file = File.createTempFile("model-guard", ".gguf")
        try {
            file.writeText("test")
            assertFalse(ModelGuards.sha256Matches(file, "0".repeat(64)))
            assertFalse(ModelGuards.sha256Matches(file, "not a digest"))
        } finally {
            file.delete()
        }
    }
}
