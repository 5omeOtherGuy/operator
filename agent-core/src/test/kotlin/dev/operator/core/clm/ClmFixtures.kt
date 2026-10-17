package dev.operator.core.clm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/*
 * Test support for the CLM head path: the golden fixtures `tools/clm/make_goldens.py` writes, a
 * safetensors writer (the reader is the code under test), and the fixture tokenizer.
 */

/** The fixture tokenizer of `make_goldens.py`: one token per UTF-8 byte, nothing added or parsed. */
internal val ByteTokenizer = ClmTokenizer { text -> text.toByteArray(StandardCharsets.UTF_8).map { it.toInt() and 0xFF } }

/** A tokenizer that gives every white-space separated word its own token, for the truncation tests. */
internal class WordTokenizer(private val vocabulary: Map<String, Int>) : ClmTokenizer {
    override fun encode(text: String): List<Int> {
        val out = ArrayList<Int>()
        for (word in text.split(' ')) {
            out.add(vocabulary[word] ?: error("the fixture vocabulary has no '$word'"))
        }
        return out
    }
}

/** Reads a test resource under `src/test/resources/clm/`. */
internal object Fixtures {
    fun text(name: String): String =
        Fixtures::class.java.getResourceAsStream("/clm/$name")?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            ?: error("test resource /clm/$name is missing; run tools/clm/make_goldens.py")

    fun json(name: String): Map<String, JsonValue> =
        MinimalJson.parse(text(name)).asObject() ?: error("/clm/$name is not a JSON object")
}

internal fun JsonValue.entries(): Map<String, JsonValue> = asObject() ?: error("expected a JSON object")

internal fun JsonValue.list(): List<JsonValue> = asArray() ?: error("expected a JSON array")

internal fun JsonValue.string(): String = asString() ?: error("expected a JSON string")

internal fun JsonValue.number(): Double = asDouble() ?: error("expected a JSON number")

internal fun JsonValue.int(): Int = asLong()?.toInt() ?: error("expected a JSON integer")

internal fun JsonValue.floatArray(): FloatArray = list().map { it.number().toFloat() }.toFloatArray()

internal fun JsonValue.floatMatrix(): List<FloatArray> = list().map { it.floatArray() }

/** The head goldens of `tools/clm/make_goldens.py`. */
internal class HeadGoldens(root: Map<String, JsonValue>) {
    val recipeVersion: String = root.getValue("recipe_version").string()
    val weightsSha256: String = root.getValue("weights_sha256").string()
    val generator: Map<String, JsonValue> = root.getValue("generator").entries()
    val shapes: Map<String, List<Long>> = root.getValue("shapes").entries()
        .mapValues { (_, value) -> value.list().map { it.asLong() ?: error("bad shape") } }
    val logitScale: Float = root.getValue("logit_scale").number().toFloat()
    val scale: Float = root.getValue("scale").number().toFloat()
    val lnEps: Float = root.getValue("ln_eps").number().toFloat()
    val inputs: List<FloatArray> = root.getValue("inputs").floatMatrix()
    val candidates: List<FloatArray> = root.getValue("candidates").floatMatrix()
    val expectedStateVectors: List<FloatArray> = root.getValue("expected_state_vectors").floatMatrix()
    val expectedActionVectors: List<FloatArray> = root.getValue("expected_action_vectors").floatMatrix()
    val expectedScores: List<FloatArray> = root.getValue("expected_scores").floatMatrix()

    companion object {
        val INSTANCE = HeadGoldens(Fixtures.json("head_goldens.json"))
    }
}

/** The schema goldens of `tools/clm/make_goldens.py`; one case as the Kotlin port sees it. */
internal class SchemaGolden(private val root: Map<String, JsonValue>) {
    val name: String = root.getValue("name").string()
    val kind: String = root.getValue("kind").string()
    val context: String = root.getValue("context").string()
    val instructions: String = root.getValue("instructions").string()
    val maxTokens: Int = root.getValue("max_tokens").int()
    val stateText: String = root.getValue("state_text").string()
    val candidateTexts: List<String> = root.getValue("candidate_texts").list().map { it.string() }
    val stateTokens: List<Int> = root.getValue("state_tokens").list().map { it.int() }
    val candidateTokens: List<List<Int>> = root.getValue("candidate_tokens").list().map { row -> row.list().map { it.int() } }
    val options: Map<String, String>? = root["options"]?.entries()?.mapValues { (_, value) -> value.string() }
    val levels: List<String>? = root["levels"]?.list()?.map { it.string() }
    val candidates: List<String>? = root["candidates"]?.list()?.map { it.string() }

    companion object {
        fun all(json: Map<String, JsonValue>): List<SchemaGolden> =
            json.getValue("cases").list().map { SchemaGolden(it.entries()) }

        val INSTANCE: List<SchemaGolden> by lazy { all(Fixtures.json("schema_goldens.json")) }
    }
}

/**
 * Writes a safetensors file: 8 bytes of little-endian u64 header length, the header JSON, then the
 * fp32 data. The JVM golden test uses it to hand the seeded weights to the reader under test.
 */
internal class SafetensorsSpec {
    private val tensors = LinkedHashMap<String, Triple<List<Long>, FloatArray, String>>()
    private val metadata = LinkedHashMap<String, String>()

    fun tensor(name: String, shape: List<Long>, values: FloatArray, dtype: String = "F32"): SafetensorsSpec {
        require(shape.fold(1L) { acc, dim -> acc * dim } == values.size.toLong()) {
            "shape $shape does not match ${values.size} values"
        }
        tensors[name] = Triple(shape, values, dtype)
        return this
    }

    fun meta(key: String, value: String): SafetensorsSpec {
        metadata[key] = value
        return this
    }

    /** The recipe metadata of the converted heads file, as `convert_heads.py` writes it. */
    fun recipeMeta(logitScale: Float = HeadGoldens.INSTANCE.logitScale): SafetensorsSpec {
        meta("logit_scale", logitScale.toString())
        meta("scale_clamp", "100.0")
        meta("width", ClmHead.HIDDEN.toString())
        meta("depth", ClmHead.DEPTH.toString())
        meta("activation", "gelu_erf")
        meta("ln_eps", ClmHeads.LN_EPS.toString())
        meta("recipe_version", ClmHeads.RECIPE_VERSION)
        meta("source_sha256", "0".repeat(64))
        return this
    }

    fun json(overrides: Map<String, String> = emptyMap()): String {
        val builder = StringBuilder("{")
        var first = true
        for ((name, entry) in tensors) {
            val (shape, _, declared) = entry
            if (!first) builder.append(',')
            first = false
            val dtype = overrides[name] ?: declared
            builder.append('"').append(escape(name)).append("\":{")
            builder.append("\"dtype\":\"").append(dtype).append("\",")
            builder.append("\"shape\":[").append(shape.joinToString(",")).append("],")
            val begin = dataOffset(name)
            val end = begin + shape.fold(1L) { acc, dim -> acc * dim } * elementSize(dtype)
            builder.append("\"data_offsets\":[").append(begin).append(",").append(end).append("]}")
        }
        if (metadata.isNotEmpty()) {
            if (!first) builder.append(',')
            builder.append("\"__metadata__\":{")
            builder.append(metadata.entries.joinToString(",") { (k, v) -> "\"${escape(k)}\":\"${escape(v)}\"" })
            builder.append('}')
        }
        return builder.append('}').toString()
    }

    fun write(path: Path): Path {
        val header = json().toByteArray(StandardCharsets.UTF_8)
        val data = ByteBuffer.allocate(tensors.values.sumOf { it.second.size * 4 }).order(ByteOrder.LITTLE_ENDIAN)
        for (entry in tensors.values) for (value in entry.second) data.putFloat(value)
        val out = ByteBuffer.allocate(8 + header.size + data.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        out.putLong(header.size.toLong())
        out.put(header)
        out.put(data.array())
        Files.write(path, out.array())
        return path
    }

    private fun dataOffset(name: String): Long {
        var offset = 0L
        for ((key, entry) in tensors) {
            if (key == name) return offset
            offset += entry.second.size * 4L
        }
        error("no tensor $name")
    }

    private fun elementSize(dtype: String): Long = when (dtype) {
        "F32" -> 4L
        "F16" -> 2L
        else -> 4L
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
