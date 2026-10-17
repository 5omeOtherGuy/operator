package dev.operator.core.grammar

/*
 * JSON schema → GBNF (C11).
 *
 * llama.cpp's grammar sampler cannot express every JSON Schema keyword; C11 fixes the contract: the
 * generator drops the keywords it cannot express and the executor re-validates the full schema before
 * acting (§7.4 item 2). This converter therefore renders a *sound over-approximation* — every string
 * it accepts parses as JSON of the declared shape — while [GbnfConversion.droppedKeywords] records
 * exactly which constraints were left to the executor.
 *
 * Design: §7.2 (capability argument schemas), §7.4 item 2, C11, ADR-0009 item 4.
 */

/** A generated grammar plus the schema keywords it could not express. */
data class GbnfConversion(
    val gbnf: GbnfGrammar,
    val droppedKeywords: Set<String>,
)

/**
 * Renders the subset of JSON Schema 2020-12 that a GBNF grammar can carry. Annotations (`title`,
 * `description`, `default`, …) are ignored silently; any other keyword that is not translated is
 * reported as dropped.
 */
class JsonSchemaToGbnf {

    private val rules = LinkedHashMap<String, String>()
    private var counter = 0
    private var usesWhitespace = false

    /** The default cap on an unconstrained string, when the schema gives no `maxLength`. */
    var defaultMaxString: Int = 400

    fun convert(schema: JsonValue.Obj, rootName: String = "root"): GbnfConversion {
        rules.clear()
        counter = 0
        usesWhitespace = false
        val rootBody = expr(schema)
        if (rootBody != rootName) rules[rootName] = rootBody

        val ordered = ArrayList<Pair<String, String>>()
        ordered.add(rootName to rules.getValue(rootName))
        rules.forEach { (name, body) -> if (name != rootName) ordered.add(name to body) }
        if (usesWhitespace && "ws" !in rules) ordered.add("ws" to "[ \\t\\n\\r]*")
        return GbnfConversion(GbnfGrammar.of(ordered), droppedKeywords(schema))
    }

    // ---- keyword translation -------------------------------------------------

    private fun expr(schema: JsonValue.Obj): String {
        val const = schema.entries["const"]
        if (const != null) return literal(const)

        val enum = schema.entries["enum"]
        if (enum != null) {
            val options = JsonAccess.arr(enum, "\"enum\"").items
            require(options.isNotEmpty()) { "enum must not be empty" }
            return Gbnf.alt(options.map { literal(it) })
        }

        val anyOf = schema.entries["anyOf"] ?: schema.entries["oneOf"]
        if (anyOf != null) {
            val subs = JsonAccess.arr(anyOf, "\"anyOf\"").items.map { JsonAccess.obj(it, "anyOf item") }
            return Gbnf.alt(subs.map { expr(it) })
        }

        val explicitType = (schema.entries["type"] as? JsonValue.Str)?.value
        val type = explicitType ?: inferredType(schema)
        return when (type) {
            "object" -> objectRule(schema)
            "array" -> arrayRule(schema)
            "string" -> stringRule(schema)
            "integer" -> Gbnf.integer()
            "number" -> Gbnf.number()
            "boolean" -> "\"true\" | \"false\""
            "null" -> "\"null\""
            else -> anyJson()
        }
    }

    private fun inferredType(schema: JsonValue.Obj): String? = when {
        "properties" in schema.entries || "additionalProperties" in schema.entries -> "object"
        "items" in schema.entries -> "array"
        else -> null
    }

    private fun objectRule(schema: JsonValue.Obj): String {
        if (!usesWhitespace) usesWhitespace = true
        val name = next("obj")
        val properties = (schema.entries["properties"] as? JsonValue.Obj)?.entries ?: emptyMap()
        val additional = schema.entries["additionalProperties"]
        val allowExtra = additional == null || (additional as? JsonValue.Bool)?.value == true

        val members = ArrayList<String>()
        properties.forEach { (key, sub) ->
            val valueExpr = expr(JsonAccess.obj(sub, "property \"$key\""))
            val keyLit = Gbnf.lit(Json.write(JsonValue.Str(key)))
            members.add("$keyLit ws \":\" ws $valueExpr")
        }
        if (allowExtra) {
            val extraName = next("pair")
            rules[extraName] = "${Gbnf.jsonString(0, 64)} ws \":\" ws ${anyJson()}"
            members.add(extraName)
        }

        val body = if (members.isEmpty()) {
            "\"{\" ws \"}\""
        } else {
            val memberExpr = "(${Gbnf.alt(members)})"
            "\"{\" ws ( $memberExpr ( ws \",\" ws $memberExpr )* )? ws \"}\""
        }
        rules[name] = body
        return name
    }

    private fun arrayRule(schema: JsonValue.Obj): String {
        if (!usesWhitespace) usesWhitespace = true
        val name = next("arr")
        val items = schema.entries["items"]?.let { JsonAccess.obj(it, "\"items\"") }
        val itemExpr = items?.let { expr(it) } ?: anyJson()
        val min = (schema.entries["minItems"] as? JsonValue.Num)?.asLong?.toInt()
        val max = (schema.entries["maxItems"] as? JsonValue.Num)?.asLong?.toInt()

        val body = when {
            min != null && max != null && min == 0 && max == 0 -> "\"[\" ws \"]\""
            min != null && min >= 1 -> {
                val lo = min - 1
                val rep = if (max != null) "{$lo,${(max - 1).coerceAtLeast(lo)}}" else "{$lo,}"
                "\"[\" ws $itemExpr ( ws \",\" ws $itemExpr )$rep ws \"]\""
            }

            else -> {
                val rep = if (max != null && max >= 1) "{0,${max - 1}}" else "*"
                "\"[\" ws ( $itemExpr ( ws \",\" ws $itemExpr )$rep )? ws \"]\""
            }
        }
        rules[name] = body
        return name
    }

    private fun stringRule(schema: JsonValue.Obj): String {
        val min = (schema.entries["minLength"] as? JsonValue.Num)?.asLong?.toInt() ?: 0
        val max = (schema.entries["maxLength"] as? JsonValue.Num)?.asLong?.toInt() ?: defaultMaxString
        require(min <= max) { "minLength $min > maxLength $max" }
        return Gbnf.jsonString(min, max)
    }

    private fun anyJson(): String =
        "\"true\" | \"false\" | \"null\" | ${Gbnf.number()} | ${Gbnf.jsonString(0, defaultMaxString)}"

    private fun literal(v: JsonValue): String = Gbnf.lit(Json.write(v))

    private fun next(prefix: String): String = "$prefix-${counter++}"

    // ---- dropped keywords ----------------------------------------------------

    private fun droppedKeywords(schema: JsonValue.Obj): Set<String> {
        val present = LinkedHashSet<String>()
        collectKeywords(schema, present)
        return present - HANDLED - ANNOTATIONS
    }

    private fun collectKeywords(v: JsonValue, into: MutableSet<String>) {
        when (v) {
            is JsonValue.Obj -> v.entries.forEach { (k, child) ->
                into.add(k)
                collectKeywords(child, into)
            }

            is JsonValue.Arr -> v.items.forEach { collectKeywords(it, into) }
            else -> Unit
        }
    }

    private companion object {
        /** Keywords this converter expresses in GBNF. */
        val HANDLED: Set<String> = setOf(
            "type", "properties", "additionalProperties", "items", "enum", "const",
            "minLength", "maxLength", "minItems", "maxItems", "anyOf", "oneOf",
        )

        /** Pure annotations: no validation meaning, ignored rather than reported. */
        val ANNOTATIONS: Set<String> = setOf(
            "title", "description", "default", "examples", "example", "\$schema", "\$comment",
            "\$id", "deprecated", "readOnly", "writeOnly",
        )
    }
}
