package dev.operator.core.grammar

/*
 * The plan short form (§8.2 PLAN/REPLAN): `{"subgoals":[{"s":"…","app":"…"}]}`, at most six
 * subgoals of at most 80 characters each (04§R3). The grammar is generated from the same JSON schema
 * the parser enforces, so the model cannot emit an over-long or over-count plan.
 *
 * Design: §8.1 (PLAN), §8.2 (PLAN/REPLAN generate), 04§R3.
 */

/** One subgoal: what the model wants and, optionally, which app it means. */
data class Subgoal(val want: String, val app: String?)

/** A parsed plan. */
data class Plan(val subgoals: List<Subgoal>)

sealed interface PlanParse {
    data class Ok(val plan: Plan) : PlanParse

    data class Err(val reason: String) : PlanParse
}

/** The plan codec: GBNF in, strict parse out. */
object PlanForm {

    const val MAX_SUBGOALS: Int = 6
    const val MAX_SUBGOAL_CHARS: Int = 80

    /** The GBNF for the plan object, from the plan schema (C11 path). */
    fun grammar(maxSubgoals: Int = MAX_SUBGOALS, maxChars: Int = MAX_SUBGOAL_CHARS): GbnfGrammar =
        JsonSchemaToGbnf().convert(schema(maxSubgoals, maxChars), "plan").gbnf

    fun schema(maxSubgoals: Int = MAX_SUBGOALS, maxChars: Int = MAX_SUBGOAL_CHARS): JsonValue.Obj =
        obj(
            "type" to JsonValue.Str("object"),
            "additionalProperties" to JsonValue.Bool(false),
            "properties" to obj(
                "subgoals" to obj(
                    "type" to JsonValue.Str("array"),
                    "minItems" to JsonValue.Num("1"),
                    "maxItems" to JsonValue.Num(maxSubgoals.toString()),
                    "items" to obj(
                        "type" to JsonValue.Str("object"),
                        "additionalProperties" to JsonValue.Bool(false),
                        "properties" to obj(
                            "s" to obj(
                                "type" to JsonValue.Str("string"),
                                "minLength" to JsonValue.Num("1"),
                                "maxLength" to JsonValue.Num(maxChars.toString()),
                            ),
                            "app" to obj(
                                "type" to JsonValue.Str("string"),
                                "maxLength" to JsonValue.Num("60"),
                            ),
                        ),
                    ),
                ),
            ),
        )

    fun parse(text: String, maxSubgoals: Int = MAX_SUBGOALS, maxChars: Int = MAX_SUBGOAL_CHARS): PlanParse {
        val value = when (val r = Json.parse(text)) {
            is JsonParse.Err -> return PlanParse.Err(r.reason)
            is JsonParse.Ok -> r.value
        }
        return try {
            val root = JsonAccess.obj(value, "plan")
            val subgoalsJson = JsonAccess.arr(root.entries["subgoals"] ?: throw JsonShapeException("plan needs \"subgoals\""), "\"subgoals\"")
            if (subgoalsJson.items.size > maxSubgoals) return PlanParse.Err("plan has more than $maxSubgoals subgoals")
            val subgoals = subgoalsJson.items.map { item ->
                val o = JsonAccess.obj(item, "subgoal")
                val want = JsonAccess.str(o.entries["s"] ?: throw JsonShapeException("subgoal needs \"s\""), "\"s\"")
                if (want.isEmpty()) throw JsonShapeException("subgoal \"s\" is empty")
                if (want.length > maxChars) throw JsonShapeException("subgoal \"s\" longer than $maxChars")
                val app = o.entries["app"]?.let { JsonAccess.str(it, "\"app\"") }
                Subgoal(want, app)
            }
            PlanParse.Ok(Plan(subgoals))
        } catch (e: JsonShapeException) {
            PlanParse.Err(e.message ?: "invalid plan")
        }
    }

    private fun obj(vararg entries: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(linkedMapOf(*entries))
}
