package dev.operator.core.grammar

/*
 * GBNF rendering helpers (S6). The per-step grammar (§8.3) and the JSON-schema → GBNF generator
 * (C11) both emit llama.cpp GBNF text through these helpers, so escaping and index enums are written
 * in exactly one place.
 *
 * Design: §8.3 (per-step grammar), §7.4 item 2 (schema ranges), C11 (unsupported keywords dropped),
 * ADR-0009 items 3-4 (short verbs, grammar from the catalogue).
 */

/** A GBNF grammar: ordered rules, rendered to the text `LlmPort.generate` takes as `gbnf`. */
class GbnfGrammar private constructor(private val rules: List<Pair<String, String>>) {

    fun render(): String = rules.joinToString("\n") { (name, body) -> "$name ::= $body" }

    fun ruleNames(): List<String> = rules.map { it.first }

    override fun toString(): String = render()

    companion object {
        fun of(vararg rules: Pair<String, String>): GbnfGrammar = GbnfGrammar(rules.toList())

        fun of(rules: List<Pair<String, String>>): GbnfGrammar = GbnfGrammar(rules)
    }
}

/** The GBNF text primitives §8.3 and C11 need. */
object Gbnf {

    /** A GBNF string terminal for [s], escaping `"` and `\` as GBNF requires. */
    fun lit(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** An alternation of already-rendered alternatives; a single alternative is returned bare. */
    fun alt(alts: List<String>): String = when (alts.size) {
        0 -> throw IllegalArgumentException("an alternation needs at least one alternative")
        1 -> alts[0]
        else -> alts.joinToString(" | ")
    }

    fun alt(vararg alts: String): String = alt(alts.toList())

    /** An alternation of string terminals for [values]. */
    fun enum(values: List<String>): String = alt(values.map { lit(it) })

    /**
     * The JSON string terminal shared by every verb whose argument is free text. §8.3: strings are
     * bounded as `{0,N}`; the bound is the caller's `max` (200 for `type`/`answer` text, 60 for `q`).
     */
    fun jsonString(max: Int): String = jsonString(0, max)

    /** A JSON string terminal with a `{min,max}` body bound, for schema `minLength`/`maxLength`. */
    fun jsonString(min: Int, max: Int): String =
        "\"\\\"\" ( [^\"\\\\\\x00-\\x1f] | \"\\\\\" ( [\"\\\\/bfnrt] | \"u\" [0-9a-fA-F]{4} ) ){$min,$max} \"\\\"\""

    /** A JSON number terminal; `[-]?[0-9]+` for integers, the full grammar for a JSON number. */
    fun integer(): String = "[-]?[0-9]+"

    fun number(): String = "[-]?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?"

    /** One terminal per concrete index, taken from the snapshot's sticky element numbers (§6.1 rule 10). */
    fun indexEnum(indices: List<Int>): String = enum(indices.map { it.toString() }.distinct().sortedBy { it.toInt() })
}
