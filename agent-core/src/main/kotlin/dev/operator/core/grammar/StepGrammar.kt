package dev.operator.core.grammar

import dev.operator.core.api.MediaAction
import dev.operator.core.api.NodeAction
import dev.operator.core.api.NodeState
import dev.operator.core.api.Role
import dev.operator.core.api.Snapshot

/*
 * The per-step GBNF grammar (§8.3): verb-specific index enums over the current snapshot.
 *
 * `tap` is offered only on clickable elements, `type` only on edit elements, `scroll` only on scroll
 * containers, and `open` only on apps inside the task's app set. Element numbers come from the
 * snapshot's sticky numbering (§6.1 rule 10). Actions the loop banned for this screen state — the
 * (full hash, action) pairs seen twice (§8.4) — are removed from the enums, so the model cannot
 * repeat itself.
 *
 * Design: §8.3, §6.1 rule 10, §8.4, C10, ADR-0009 items 3-4.
 */

/** The GBNF for one screen state, plus the concrete alternatives the tests and traces can inspect. */
class StepGrammar(
    private val snapshot: Snapshot,
    private val appSet: Set<String> = emptySet(),
    private val appAliases: Map<String, String> = emptyMap(),
    private val bannedActionKeys: Set<String> = emptySet(),
) {

    /** Indices whose element supports [action] and is not disabled, minus the banned ones. */
    private fun indicesFor(action: NodeAction, keyPrefix: String): List<Int> = snapshot.nodes
        .filter { action in it.actions && NodeState.DISABLED !in it.state }
        .map { it.index }
        .filter { "$keyPrefix:${it}" !in bannedActionKeys }
        .distinct()
        .sorted()

    val tapIndices: List<Int> get() = indicesFor(NodeAction.CLICK, "tap")
    val longIndices: List<Int> get() = indicesFor(NodeAction.LONG_CLICK, "long")

    val typeIndices: List<Int> get() = snapshot.nodes
        .filter { it.role == Role.EDIT && NodeAction.SET_TEXT in it.actions && NodeState.DISABLED !in it.state && NodeState.PASSWORD !in it.state }
        .map { it.index }
        .filter { "type:$it" !in bannedActionKeys }
        .distinct()
        .sorted()

    val scrollIndices: List<Int> get() = snapshot.nodes
        .filter { NodeAction.SCROLL_FORWARD in it.actions || NodeAction.SCROLL_BACKWARD in it.actions || it.role == Role.LIST }
        .filter { NodeState.DISABLED !in it.state }
        .map { it.index }
        .filter { "scroll:$it" !in bannedActionKeys }
        .distinct()
        .sorted()

    /** The apps the `open` enum may name: the display name where known, else the package (§8.3). */
    val openApps: List<String> get() = appSet.map { pkg -> appAliases.entries.firstOrNull { it.value == pkg }?.key ?: pkg }.distinct().sorted()

    fun build(): GbnfGrammar {
        val rules = ArrayList<Pair<String, String>>()
        val verbAlternatives = ArrayList<String>()

        if (tapIndices.isNotEmpty()) {
            verbAlternatives.add("tap")
            rules.add("tap" to "${Gbnf.lit("""{"a":"tap","i":""")} tapidx ${Gbnf.lit("}")}")
            rules.add("tapidx" to Gbnf.indexEnum(tapIndices))
        }
        if (longIndices.isNotEmpty()) {
            verbAlternatives.add("long")
            rules.add("long" to "${Gbnf.lit("""{"a":"long","i":""")} longidx ${Gbnf.lit("}")}")
            rules.add("longidx" to Gbnf.indexEnum(longIndices))
        }
        if (typeIndices.isNotEmpty()) {
            verbAlternatives.add("type")
            rules.add("type" to "${Gbnf.lit("""{"a":"type","i":""")} typeidx ${Gbnf.lit(""","text":""")} str ${Gbnf.lit("}")}")
            rules.add("typeidx" to Gbnf.indexEnum(typeIndices))
        }
        if (scrollIndices.isNotEmpty()) {
            verbAlternatives.add("scroll")
            rules.add("scroll" to "${Gbnf.lit("""{"a":"scroll","i":""")} scrollidx ${Gbnf.lit(""","dir":""")} scrolldir ${Gbnf.lit("}")}")
            rules.add("scrollidx" to Gbnf.indexEnum(scrollIndices))
            rules.add(
                "scrolldir" to Gbnf.enum(
                    listOf("down", "up", "left", "right").map { d -> "\"$d\"" },
                ),
            )
        }
        if (openApps.isNotEmpty()) {
            verbAlternatives.add("open")
            rules.add("open" to "${Gbnf.lit("""{"a":"open","app":""")} openidx ${Gbnf.lit("}")}")
            rules.add("openidx" to Gbnf.enum(openApps.map { "\"$it\"" }))
        }

        // The global navigation verbs, media and the loop-control verbs need no element (§7.1).
        verbAlternatives.add("nav")
        rules.add("nav" to Gbnf.alt(NavVerb.wires.map { Gbnf.lit("""{"a":"$it"}""") }))
        verbAlternatives.add("media")
        rules.add("media" to Gbnf.alt(MediaAction.entries.map { Gbnf.lit("""{"a":"media","act":"${it.name.lowercase()}"}""") }))
        verbAlternatives.add("wait")
        rules.add("wait" to Gbnf.lit("""{"a":"wait"}"""))
        verbAlternatives.add("done")
        rules.add("done" to "${Gbnf.lit("""{"a":"done","answer":""")} str ${Gbnf.lit("}")}")
        verbAlternatives.add("ask")
        rules.add("ask" to "${Gbnf.lit("""{"a":"ask","q":""")} askstr ${Gbnf.lit("}")}")

        val allRules = ArrayList<Pair<String, String>>()
        allRules.add("root" to Gbnf.alt(verbAlternatives))
        allRules.addAll(rules)
        allRules.add("str" to Gbnf.jsonString(ShortForm.MAX_TEXT))
        allRules.add("askstr" to Gbnf.jsonString(ShortForm.MAX_QUESTION))
        return GbnfGrammar.of(allRules)
    }

    /** Convenience: the verbs this screen state offers, for the history line and the tests. */
    fun verbs(): Set<ActionVerb> {
        val out = linkedSetOf<ActionVerb>()
        if (tapIndices.isNotEmpty()) out.add(ActionVerb.TAP)
        if (longIndices.isNotEmpty()) out.add(ActionVerb.LONG)
        if (typeIndices.isNotEmpty()) out.add(ActionVerb.TYPE)
        if (scrollIndices.isNotEmpty()) out.add(ActionVerb.SCROLL)
        if (openApps.isNotEmpty()) out.add(ActionVerb.OPEN)
        out.add(ActionVerb.BACK)
        out.add(ActionVerb.HOME)
        out.add(ActionVerb.MEDIA)
        out.add(ActionVerb.WAIT)
        out.add(ActionVerb.DONE)
        out.add(ActionVerb.ASK)
        return out
    }
}
