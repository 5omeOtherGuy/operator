package dev.operator.core.decide

import dev.operator.core.api.AbstainReason
import dev.operator.core.api.BackendId
import java.security.MessageDigest

/*
 * S4 decide(): the decision log.
 *
 * Design: FOUNDATION §5.1 ("It then writes a DecisionLogEntry (kind, hashes, backend, raw and
 * calibrated scores, latency, **no screen text**)"), §9.5 (the audit log is hash-chained by S10;
 * decide's own log is separate), ADR-0007 decision 3 (router "writes a decision log with hashes and
 * scores, never screen text"), research/03-decide.md risk table ("Decision logs hold sensitive
 * context | designed out | Log hashes and scores only").
 *
 * The entry carries the option labels (letters or yes/no) and the scores, never the model or screen
 * text: `contextSha256` is a digest of the context and `optionLabels` are the fixed label strings the
 * backend sent, not the option descriptions.
 */

/** §5.1: how the call ended. */
enum class DecisionOutcome { DECIDED, ABSTAINED, FAILED }

/**
 * §5.1, §5.5: one decision log line. Everything here is a hash, an id, a label, a number or an enum;
 * no `DecisionState.context` and no option description ever reaches it.
 */
data class DecisionLogEntry(
    /** `Clock.wallMs()` at the write. */
    val ts: Long,
    val kind: String,
    val backend: BackendId,
    /** sha256 of the model (§4.8), null for RULES. */
    val modelId: String?,
    val headId: String?,
    val calibrationVersion: String?,
    /** The prompt/rendering recipe version; part of the calibration key (§F6.5). */
    val recipeVersion: String?,
    /** §5.1 `DecisionState.screenHash`, already host-side. */
    val screenHash: Long?,
    /** sha256 hex of `DecisionState.context`; the text itself is not stored. */
    val contextSha256: String,
    /** The model-visible option labels (A–Z, or yes/no), in score order. */
    val optionLabels: List<String>,
    /** One raw logit per option, before calibration (§5.1). */
    val rawScores: List<Double>,
    /** The calibrated probabilities, in the same order. */
    val calibrated: List<Double>,
    /** §5.1 `p_top − mean(p_rest)`, after calibration. */
    val confidence: Double,
    /** τ_kind used for this call (§5.5). */
    val threshold: Double,
    val outcome: DecisionOutcome,
    val abstainReason: AbstainReason?,
    /** `Decision.Failed.reason`, when [outcome] is FAILED. */
    val failureReason: String?,
    val latencyMs: Long,
    val deadlineMs: Long,
) {
    /** A JSONL line with no screen or model text; hashes and numbers only. */
    fun toJsonLine(): String = buildString {
        append('{')
        appendField("ts", ts)
        appendField("kind", kind)
        appendField("backend", backend.name)
        appendField("modelId", modelId)
        appendField("headId", headId)
        appendField("calibrationVersion", calibrationVersion)
        appendField("recipeVersion", recipeVersion)
        appendField("screenHash", screenHash)
        appendField("contextSha256", contextSha256)
        appendField("optionLabels", optionLabels)
        appendField("rawScores", rawScores)
        appendField("calibrated", calibrated)
        appendField("confidence", confidence)
        appendField("threshold", threshold)
        appendField("outcome", outcome.name)
        appendField("abstainReason", abstainReason?.name)
        appendField("failureReason", failureReason)
        appendField("latencyMs", latencyMs)
        appendField("deadlineMs", deadlineMs)
        append('}')
    }

    private fun StringBuilder.appendField(name: String, value: Any?) {
        if (length > 1) append(',')
        append('"').append(name).append("\":")
        when (value) {
            null -> append("null")
            is String -> append('"').append(escapeJson(value)).append('"')
            is Boolean -> append(value)
            is Int, is Long -> append(value)
            is Double -> append(if (value.isFinite()) value.toString() else "null")
            is List<*> -> {
                append('[')
                value.forEachIndexed { i, element ->
                    if (i > 0) append(',')
                    when (element) {
                        is String -> append('"').append(escapeJson(element)).append('"')
                        else -> append(element)
                    }
                }
                append(']')
            }
            else -> append('"').append(escapeJson(value.toString())).append('"')
        }
    }
}

private fun escapeJson(s: String): String = buildString(s.length) {
    s.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}

/** §5.1: the sink the router writes each decision to. S10 may back it with app-private storage. */
fun interface DecisionLog {
    suspend fun append(entry: DecisionLogEntry)
}

/** A [DecisionLog] that keeps entries in memory; unit tests and a no-storage fallback use it. */
class InMemoryDecisionLog : DecisionLog {
    private val entries = mutableListOf<DecisionLogEntry>()

    override suspend fun append(entry: DecisionLogEntry) {
        entries += entry
    }

    fun entries(): List<DecisionLogEntry> = entries.toList()

    fun last(): DecisionLogEntry? = entries.lastOrNull()

    fun clear() = entries.clear()
}

/** §5.1: the context is logged as a digest, so the log searches and compares without the text. */
object Hashes {
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
