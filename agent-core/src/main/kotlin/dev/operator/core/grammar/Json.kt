package dev.operator.core.grammar

/*
 * The little JSON reader/writer the per-step grammar path needs (S6).
 *
 * `:agent-core` is pure JVM and must not grow a serialization dependency for this (FOUNDATION §2.2,
 * the F0 module rule). The short action form of C10 (`{"a":"tap","i":9}`) is a bounded object, and
 * the capability schemas that feed the JSON-schema → GBNF generator (§7.4 item 2) arrive as parsed
 * JSON, so one small strict reader serves both. Strictness is the point on the action path: an
 * unknown key is a parse failure, so a model that emits `x`/`y` coordinates (§7.1, C10) is rejected
 * rather than silently obeyed.
 *
 * Design: §7.1 (model verb → typed call), §7.4 items 1-2 (strict parse, schema ranges), C10, C11.
 */

/** The outcome of strict JSON parsing; [Err] carries a one-line reason for the audit/decision log. */
sealed interface JsonParse {
    data class Ok(val value: JsonValue) : JsonParse

    data class Err(val reason: String) : JsonParse
}

/** A parsed JSON value. [JsonValue.Num] keeps the raw text so integers stay exact. */
sealed interface JsonValue {
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Num(val raw: String) : JsonValue {
        val asDouble: Double get() = raw.toDouble()
        val asLong: Long get() = raw.toDouble().toLong()
        val isIntegral: Boolean get() = !raw.contains('.') && !raw.contains('e') && !raw.contains('E')
    }

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

/** A strict JSON parser: no trailing content, no unknown-ish relaxation, at most [MAX_DEPTH] deep. */
object Json {

    const val MAX_DEPTH: Int = 32

    fun parse(text: String): JsonParse {
        val p = Reader(text)
        return try {
            p.skipWs()
            val v = p.value(0)
            p.skipWs()
            if (!p.atEnd()) JsonParse.Err("trailing content at ${p.pos}")
            else JsonParse.Ok(v)
        } catch (e: JsonError) {
            JsonParse.Err(e.message ?: "invalid JSON")
        }
    }

    private class JsonError(message: String) : Exception(message)

    private class Reader(private val s: String) {
        var pos: Int = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw JsonError("JSON nested deeper than $MAX_DEPTH")
            skipWs()
            if (atEnd()) throw JsonError("unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c in '0'..'9') number() else throw JsonError("unexpected '$c' at $pos")
            }
        }

        private fun <T : JsonValue> literal(text: String, v: T): T {
            if (!s.startsWith(text, pos)) throw JsonError("invalid literal at $pos")
            pos += text.length
            return v
        }

        private fun obj(depth: Int): JsonValue.Obj {
            expect('{')
            val entries = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (peek() == '}') {
                pos++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWs()
                if (peek() != '"') throw JsonError("object key must be a string at $pos")
                val key = string()
                skipWs()
                expect(':')
                val v = value(depth + 1)
                if (entries.put(key, v) != null) throw JsonError("duplicate key \"$key\"")
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.Obj(entries)
                    }

                    else -> throw JsonError("expected ',' or '}' at $pos")
                }
            }
        }

        private fun arr(depth: Int): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWs()
            if (peek() == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(value(depth + 1))
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }

                    else -> throw JsonError("expected ',' or ']' at $pos")
                }
            }
        }

        private fun number(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            if (peek() == '0') pos++ else {
                if (peek() !in '1'..'9') throw JsonError("invalid number at $pos")
                while (peek() in '0'..'9') pos++
            }
            if (peek() == '.') {
                pos++
                if (peek() !in '0'..'9') throw JsonError("invalid fraction at $pos")
                while (peek() in '0'..'9') pos++
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                if (peek() !in '0'..'9') throw JsonError("invalid exponent at $pos")
                while (peek() in '0'..'9') pos++
            }
            return JsonValue.Num(s.substring(start, pos))
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonError("unterminated string")
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonError("unterminated escape")
                        sb.append(
                            when (val e = s[pos++]) {
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                'b' -> '\b'
                                'f' -> '\u000c'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> {
                                    val hex = if (pos + 4 <= s.length) s.substring(pos, pos + 4) else ""
                                    if (hex.length != 4 || hex.any { it !in "0123456789abcdefABCDEF" }) {
                                        throw JsonError("invalid \\u escape")
                                    }
                                    pos += 4
                                    hex.toInt(16).toChar()
                                }

                                else -> throw JsonError("invalid escape '\\$e'")
                            },
                        )
                    }

                    else -> {
                        if (c < ' ') throw JsonError("raw control character in string")
                        sb.append(c)
                    }
                }
            }
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else s[pos]

        private fun expect(c: Char) {
            if (peek() != c) throw JsonError("expected '$c' at $pos")
            pos++
        }
    }

    /** Writes [v] back to text; used by the short-form encoder and the tests. */
    fun write(v: JsonValue): String = buildString { writeTo(this, v) }

    private fun writeTo(sb: StringBuilder, v: JsonValue) {
        when (v) {
            is JsonValue.Obj -> {
                sb.append('{')
                v.entries.entries.forEachIndexed { i, (k, value) ->
                    if (i > 0) sb.append(',')
                    sb.append(quote(k)).append(':')
                    writeTo(sb, value)
                }
                sb.append('}')
            }

            is JsonValue.Arr -> {
                sb.append('[')
                v.items.forEachIndexed { i, value ->
                    if (i > 0) sb.append(',')
                    writeTo(sb, value)
                }
                sb.append(']')
            }

            is JsonValue.Str -> sb.append(quote(v.value))
            is JsonValue.Num -> sb.append(v.raw)
            is JsonValue.Bool -> sb.append(if (v.value) "true" else "false")
            JsonValue.Null -> sb.append("null")
        }
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}

/** Thrown when a parsed [JsonValue] is not the shape the caller requires. */
class JsonShapeException(message: String) : IllegalArgumentException(message)

/** Typed accessors that fail loudly, so a malformed model object never reaches the executor. */
object JsonAccess {

    fun obj(v: JsonValue, what: String): JsonValue.Obj = v as? JsonValue.Obj ?: throw JsonShapeException("$what must be an object")

    fun arr(v: JsonValue, what: String): JsonValue.Arr = v as? JsonValue.Arr ?: throw JsonShapeException("$what must be an array")

    fun str(v: JsonValue, what: String): String = (v as? JsonValue.Str)?.value ?: throw JsonShapeException("$what must be a string")

    fun int(v: JsonValue, what: String): Int {
        val num = v as? JsonValue.Num ?: throw JsonShapeException("$what must be an integer")
        if (!num.isIntegral) throw JsonShapeException("$what must be an integer, was ${num.raw}")
        return num.raw.toLong().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else throw JsonShapeException("$what out of range") }
    }

    /** Guarantees that an object carries exactly [keys]: no missing field, no coordinate-style extra. */
    fun exactKeys(o: JsonValue.Obj, what: String, vararg keys: String) {
        val expected = keys.toSet()
        val extra = o.entries.keys - expected
        val missing = expected - o.entries.keys
        if (missing.isNotEmpty()) throw JsonShapeException("$what is missing ${missing.sorted()}")
        if (extra.isNotEmpty()) throw JsonShapeException("$what has unexpected ${extra.sorted()}")
    }
}
