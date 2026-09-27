package dev.operator.core.clm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * The safetensors reader (FOUNDATION §5.4; 03§F6.1): a JSON header plus raw little-endian fp32
 * arrays. The heads loader must read exactly the file `tools/clm/convert_heads.py` writes, and it must
 * refuse anything else loudly — a wrong dtype, a wrong shape or a truncated file is a broken head
 * identity, not a warning.
 */
class SafetensorsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun small(): Path = SafetensorsSpec()
        .tensor("w", listOf(2L, 3L), floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        .tensor("b", listOf(2L), floatArrayOf(-0.5f, 0.25f))
        .meta("recipe_version", "test")
        .write(temp.newFile("small.safetensors").toPath())

    @Test
    fun `reads shapes, values and metadata`() {
        SafetensorsFile.open(small()).use { file ->
            assertEquals(setOf("w", "b"), file.tensors.keys)
            assertEquals(listOf(2L, 3L), file.shape("w"))
            assertEquals(listOf(2L), file.shape("b"))
            assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), file.float32("w"), 0f)
            assertArrayEquals(floatArrayOf(-0.5f, 0.25f), file.float32("b"), 0f)
            assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), file.float32("w", 2L, 3L), 0f)
            assertEquals("test", file.metadata["recipe_version"])
            assertTrue("the header must be small", file.headerBytes in 1..4096)
        }
    }

    @Test
    fun `rejects a shape the caller expects differently`() {
        SafetensorsFile.open(small()).use { file ->
            val error = assertThrows(IllegalArgumentException::class.java) { file.float32("w", 3L, 2L) }
            assertTrue(error.message!!, error.message!!.contains("shape"))
        }
    }

    @Test
    fun `rejects a dtype the fp32 path cannot read`() {
        // The test writes raw fp32 payloads, so an integer dtype keeps the declared byte count honest
        // while exercising the fp32-only gate.
        val path = SafetensorsSpec()
            .tensor("i32", listOf(2L), floatArrayOf(1f, 2f), dtype = "I32")
            .write(temp.newFile("i32.safetensors").toPath())
        SafetensorsFile.open(path).use { file ->
            val error = assertThrows(IllegalArgumentException::class.java) { file.float32("i32") }
            assertTrue(error.message!!, error.message!!.contains("F32"))
        }
    }

    @Test
    fun `rejects a truncated file`() {
        val path = small()
        val bytes = Files.readAllBytes(path)
        Files.write(path, bytes.copyOf(bytes.size - 8))
        val error = assertThrows(IllegalArgumentException::class.java) { SafetensorsFile.open(path) }
        assertTrue(error.message!!, error.message!!.contains("spans"))
    }

    @Test
    fun `rejects a file that is not safetensors`() {
        val path = temp.newFile("rubbish.safetensors").toPath()
        Files.write(path, byteArrayOf(1, 2, 3))
        assertThrows(IllegalArgumentException::class.java) { SafetensorsFile.open(path) }
    }

    @Test
    fun `rejects a header that is not json`() {
        val path = temp.newFile("badheader.safetensors").toPath()
        val header = "not json".toByteArray()
        val buffer = ByteBuffer.allocate(8 + header.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(header.size.toLong())
        buffer.put(header)
        Files.write(path, buffer.array())
        assertThrows(IllegalArgumentException::class.java) { SafetensorsFile.open(path) }
    }

    @Test
    fun `rejects a missing tensor`() {
        SafetensorsFile.open(small()).use { file ->
            val error = assertThrows(IllegalArgumentException::class.java) { file.float32("nope") }
            assertTrue(error.message!!, error.message!!.contains("nope"))
        }
    }

    @Test
    fun `rejects a heads file whose metadata is not the recipe`() {
        for (broken in listOf("activation" to "gelu_tanh", "width" to "768", "depth" to "2", "ln_eps" to "0.1")) {
            val path = SafetensorsSpec()
                .tensor("state_head.inp.weight", listOf(1536L, 4096L), FloatArray(1536 * 4096))
                .recipeMeta()
                .meta(broken.first, broken.second)
                .write(temp.newFile("broken-${broken.first}.safetensors").toPath())
            val error = assertThrows(IllegalArgumentException::class.java) { ClmHeads.open(path) }
            assertTrue(error.message!!, error.message!!.contains(broken.first))
        }
    }

    @Test
    fun `rejects a heads file without a logit_scale`() {
        val path = SafetensorsSpec()
            .tensor("state_head.inp.weight", listOf(1536L, 4096L), FloatArray(1536 * 4096))
            .meta("scale_clamp", "100.0")
            .meta("width", "1536")
            .meta("depth", "3")
            .meta("activation", "gelu_erf")
            .meta("ln_eps", ClmHeads.LN_EPS.toString())
            .write(temp.newFile("noscale.safetensors").toPath())
        val error = assertThrows(IllegalArgumentException::class.java) { ClmHeads.open(path) }
        assertTrue(error.message!!, error.message!!.contains("logit_scale"))
    }

    @Test
    fun `rejects a heads file that misses a tensor`() {
        val tensors = ClmWeights.tensors()
        val spec = SafetensorsSpec().recipeMeta()
        // Every tensor of the recipe except one.
        for ((name, values) in tensors) {
            if (name == "action_head.out.bias") continue
            spec.tensor(name, ClmWeights.SHAPES.getValue(name), values)
        }
        val path = spec.write(temp.newFile("missing-tensor.safetensors").toPath())
        val error = assertThrows(IllegalArgumentException::class.java) { ClmHeads.open(path) }
        assertTrue(error.message!!, error.message!!.contains("action_head.out.bias"))
    }
}
