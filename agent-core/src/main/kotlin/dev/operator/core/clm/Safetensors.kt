package dev.operator.core.clm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/*
 * The safetensors reader of the CLM heads (FOUNDATION §5.4, 03§F6.1).
 *
 * safetensors is a JSON header plus raw little-endian arrays: 8 bytes of little-endian u64 header
 * length, the header JSON, then the tensor data; each entry's `data_offsets` are relative to the start
 * of the data section. The heads file itself is written by `tools/clm/convert_heads.py` from the
 * published `CLM_v0.1-8B.pt` (sha256-checked), and by the JVM golden tests from the seeded generator.
 *
 * Only fp32 tensors are read: the M1 path is the fp32 reference (03§F6.1, F6.4); an fp16 variant must
 * pass G3/G4 against fp32 before it is used, and that is a later slice.
 */

/** One header entry of a safetensors file. [begin]/[end] are offsets into the data section. */
data class TensorInfo(val dtype: String, val shape: List<Long>, val begin: Long, val end: Long) {
    /** Number of elements. */
    val numel: Long = shape.fold(1L) { acc, d -> acc * d }

    /** Element size in bytes of [dtype], or null when the dtype is not one this reader supports. */
    fun elementSize(): Int? = when (dtype) {
        "F32" -> 4
        "F16" -> 2
        "I32" -> 4
        "I64" -> 8
        else -> null
    }
}

/**
 * A read-only view on a `.safetensors` file. Tensor bytes are copied out by [float32]; the file is
 * closed by [close], so callers use it in a `use { }` block.
 */
class SafetensorsFile private constructor(
    private val channel: FileChannel,
    private val dataStart: Long,
    val tensors: Map<String, TensorInfo>,
    val metadata: Map<String, String>,
) : AutoCloseable {

    /** The fp32 values of [name], as a copy. */
    fun float32(name: String): FloatArray {
        val info = tensors[name] ?: throw IllegalArgumentException(
            "tensor '$name' is not in the file; it holds ${tensors.keys.sorted()}",
        )
        require(info.dtype == "F32") { "tensor '$name' is ${info.dtype}, the fp32 path reads F32 only" }
        require(info.numel <= Int.MAX_VALUE) { "tensor '$name' holds ${info.numel} elements, too many" }
        val bytes = (info.end - info.begin)
        require(bytes == info.numel * 4) {
            "tensor '$name' declares ${info.numel} elements but ${bytes} bytes"
        }
        val buffer = ByteBuffer.allocate(bytes.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        readFully(buffer, dataStart + info.begin)
        buffer.flip()
        val out = FloatArray(info.numel.toInt())
        buffer.asFloatBuffer().get(out)
        return out
    }

    /** The fp32 values of [name], requiring exactly [shape]. */
    fun float32(name: String, vararg shape: Long): FloatArray {
        val info = tensors[name] ?: throw IllegalArgumentException("tensor '$name' is missing from the file")
        require(info.shape == shape.toList()) {
            "tensor '$name' has shape ${info.shape}, expected ${shape.toList()}"
        }
        return float32(name)
    }

    /** The shape of [name], as long as [shape] (a convenience for callers that validate themselves). */
    fun shape(name: String): List<Long> =
        (tensors[name] ?: throw IllegalArgumentException("tensor '$name' is missing from the file")).shape

    /** The number of bytes of the header, for a `tensors + header` size report. */
    val headerBytes: Long get() = dataStart - 8

    override fun close() {
        channel.close()
    }

    private fun readFully(buffer: ByteBuffer, position: Long) = readFullyTo(channel, buffer, position)

    companion object {
        /** A sanity bound on the header length; the real header of the heads file is a few KB. */
        const val MAX_HEADER_BYTES: Long = 32L * 1024 * 1024

        /** Opens [path] and parses its header. The caller closes the returned view. */
        fun open(path: Path): SafetensorsFile {
            val channel = FileChannel.open(path, StandardOpenOption.READ)
            try {
                val size = channel.size()
                require(size >= 8) { "$path is ${size} bytes, too short for a safetensors file" }
                val lenBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                readFullyTo(channel, lenBuffer, 0)
                lenBuffer.flip()
                val headerLen = lenBuffer.getLong()
                require(headerLen in 1..MAX_HEADER_BYTES) { "$path declares a header of $headerLen bytes" }
                require(8 + headerLen <= size) { "$path declares a header larger than the file" }
                val headerBuffer = ByteBuffer.allocate(headerLen.toInt())
                readFullyTo(channel, headerBuffer, 8)
                headerBuffer.flip()
                val header = MinimalJson.parse(StandardCharsets.UTF_8.decode(headerBuffer).toString())
                val obj = header.asObject() ?: throw IllegalArgumentException("$path: the header is not an object")
                val dataLength = size - 8 - headerLen
                val tensors = LinkedHashMap<String, TensorInfo>()
                var metadata: Map<String, String> = emptyMap()
                for ((name, value) in obj) {
                    if (name == "__metadata__") {
                        metadata = (value.asObject() ?: throw IllegalArgumentException("__metadata__ is not an object"))
                            .mapValues { (k, v) ->
                                v.asString() ?: throw IllegalArgumentException("metadata '$k' is not a string")
                            }
                        continue
                    }
                    val entry = value.asObject() ?: throw IllegalArgumentException("tensor '$name' is not an object")
                    val dtype = entry["dtype"]?.asString()
                        ?: throw IllegalArgumentException("tensor '$name' has no dtype")
                    val shape = entry["shape"]?.asArray()?.map {
                        it.asLong() ?: throw IllegalArgumentException("tensor '$name' has a non-integer shape")
                    } ?: throw IllegalArgumentException("tensor '$name' has no shape")
                    val offsets = entry["data_offsets"]?.asArray()?.map {
                        it.asLong() ?: throw IllegalArgumentException("tensor '$name' has non-integer offsets")
                    } ?: throw IllegalArgumentException("tensor '$name' has no data_offsets")
                    require(offsets.size == 2) { "tensor '$name' has ${offsets.size} data_offsets, expected 2" }
                    val (begin, end) = offsets
                    require(begin in 0..end && end <= dataLength) {
                        "tensor '$name' spans $begin..$end of a $dataLength byte data section"
                    }
                    val info = TensorInfo(dtype, shape, begin, end)
                    info.elementSize()?.let { elementSize ->
                        require(info.numel * elementSize == end - begin) {
                            "tensor '$name' is $dtype$shape = ${info.numel * elementSize} bytes " +
                                "but spans ${end - begin} bytes"
                        }
                    }
                    tensors[name] = info
                }
                return SafetensorsFile(channel, 8 + headerLen, tensors, metadata)
            } catch (e: Throwable) {
                channel.close()
                throw e
            }
        }
    }
}

private fun readFullyTo(channel: FileChannel, buffer: ByteBuffer, position: Long) {
    var pos = position
    while (buffer.hasRemaining()) {
        val read = channel.read(buffer, pos)
        check(read >= 0) { "unexpected end of file while reading ${buffer.remaining()} bytes at $pos" }
        pos += read
    }
}

/**
 * The smallest JSON reader that reads a safetensors header and the JVM golden fixtures. Values carry
 * their raw token so a number keeps all the digits the writer produced.
 */
internal sealed interface JsonValue {
    fun asObject(): Map<String, JsonValue>?
    fun asArray(): List<JsonValue>?
    fun asString(): String?
    fun asLong(): Long?
    fun asDouble(): Double?
    fun asBoolean(): Boolean?

    data class Obj(val entries: Map<String, JsonValue>) : JsonValue {
        override fun asObject() = entries
        override fun asArray() = null
        override fun asString() = null
        override fun asLong() = null
        override fun asDouble() = null
        override fun asBoolean() = null
    }

    data class Arr(val items: List<JsonValue>) : JsonValue {
        override fun asObject() = null
        override fun asArray() = items
        override fun asString() = null
        override fun asLong() = null
        override fun asDouble() = null
        override fun asBoolean() = null
    }

    data class Str(val value: String) : JsonValue {
        override fun asObject() = null
        override fun asArray() = null
        override fun asString() = value
        override fun asLong() = null
        override fun asDouble() = null
        override fun asBoolean() = null
    }

    data class Num(val raw: String) : JsonValue {
        override fun asObject() = null
        override fun asArray() = null
        override fun asString() = null
        override fun asLong() = raw.toLongOrNull()
        override fun asDouble() = raw.toDoubleOrNull()
        override fun asBoolean() = null
    }

    data class Bool(val value: Boolean) : JsonValue {
        override fun asObject() = null
        override fun asArray() = null
        override fun asString() = null
        override fun asLong() = null
        override fun asDouble() = null
        override fun asBoolean() = value
    }

    data object Null : JsonValue {
        override fun asObject() = null
        override fun asArray() = null
        override fun asString() = null
        override fun asLong() = null
        override fun asDouble() = null
        override fun asBoolean() = null
    }
}

/** A recursive-descent JSON parser; throws [IllegalArgumentException] with the offset on bad input. */
internal object MinimalJson {
    fun parse(text: String): JsonValue = Parser(text).parseDocument()

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): JsonValue {
            val value = parseValue()
            skipWhitespace()
            require(i == s.length) { "trailing characters at $i" }
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            require(i < s.length) { "unexpected end of input" }
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): JsonValue {
            expect('{')
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                i++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                entries[key] = parseValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return JsonValue.Obj(entries)
                    else -> throw IllegalArgumentException("expected ',' or '}' at ${i - 1}, was '$c'")
                }
            }
        }

        private fun parseArray(): JsonValue {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                i++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> return JsonValue.Arr(items)
                    else -> throw IllegalArgumentException("expected ',' or ']' at ${i - 1}, was '$c'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> when (val esc = next()) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(i + 4 <= s.length) { "truncated \\u escape at $i" }
                            out.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("bad escape '\\$esc' at ${i - 1}")
                    }
                    else -> {
                        require(c.code >= 0x20) { "raw control character in string at ${i - 1}" }
                        out.append(c)
                    }
                }
            }
        }

        private fun parseNumber(): JsonValue {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            require(i > start) { "expected a value at $start, was '${s.getOrNull(start)}'" }
            val raw = s.substring(start, i)
            require(raw.toDoubleOrNull() != null) { "bad number '$raw' at $start" }
            return JsonValue.Num(raw)
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            require(s.startsWith(word, i)) { "expected '$word' at $i" }
            i += word.length
            return value
        }

        private fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun peek(): Char? = s.getOrNull(i)

        private fun next(): Char {
            require(i < s.length) { "unexpected end of input" }
            return s[i++]
        }

        private fun expect(c: Char) {
            require(next() == c) { "expected '$c' at ${i - 1}" }
        }
    }
}
