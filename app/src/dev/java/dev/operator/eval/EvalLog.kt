package dev.operator.eval

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * The per-step evaluation log (FOUNDATION §12; ADR-0015; research 07 §R1).
 *
 * One JSON object per line under the app's files dir, `/data/data/dev.operator/files/eval/evallog.jsonl`.
 * The laptop driver reads it with `adb exec-out run-as dev.operator cat files/eval/evallog.jsonl` and
 * never commits it (OQ-2 default (b)); `tools/eval/scorer.py` turns it into `metrics.json`.
 *
 * Fields per §12: `t_read_ms`, `t_prefill_ms`, `t_decode_ms`, `t_decide_ms`, `t_act_ms`, `t_settle_ms`,
 * `n_prompt`, `n_gen`, tool-call parse ok, gate events, `vm_hwm_kb`, `headroom`,
 * `charge_counter_uah`, `current_now_ua`. The schema is mirrored in `tools/eval/evallog.py`.
 *
 * This class is deliberately dumb: append-only, one writer, no rotation (an eval run is short).
 */
class EvalLog(private val context: Context) {

    val dir: File get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    val file: File get() = File(dir, FILE_NAME)

    val replayDir: File get() = File(dir, "replay").apply { mkdirs() }

    /**
     * One step's timings and outcome. Milliseconds; `-1` means "not measured" and is written as null.
     */
    data class Step(
        val taskId: String,
        val run: Int,
        val step: Int,
        val tReadMs: Long = -1,
        val tPrefillMs: Long = -1,
        val tDecodeMs: Long = -1,
        val tDecideMs: Long = -1,
        val tActMs: Long = -1,
        val tSettleMs: Long = -1,
        val nPrompt: Int = -1,
        val nGen: Int = -1,
        val parseOk: Boolean = false,
        val raw: String = "",
        val action: JSONObject? = null,
        val valid: Boolean = false,
        val executable: Boolean = false,
        val gateEvents: List<String> = emptyList(),
        /** The snapshot acted on, written only while recording an op-replay-v0 set. */
        val snapshot: JSONObject? = null,
    )

    @Synchronized
    fun append(record: JSONObject) {
        dir.mkdirs()
        FileOutputStream(file, true).use { stream ->
            stream.write(record.toString().toByteArray(StandardCharsets.UTF_8))
            stream.write('\n'.code)
            stream.flush()
        }
    }

    fun appendTask(taskId: String, run: Int, seed: Long, goal: String, status: String,
                   answer: String? = null, reason: String? = null) {
        append(JSONObject()
            .put("type", "task")
            .put("taskId", taskId)
            .put("run", run)
            .put("seed", seed)
            .put("goal", goal)
            .put("status", status)
            .put("ts", System.currentTimeMillis())
            .putOpt("answer", answer)
            .putOpt("reason", reason))
    }

    fun appendStep(step: Step) {
        append(JSONObject()
            .put("type", "step")
            .put("taskId", step.taskId)
            .put("run", step.run)
            .put("step", step.step)
            .put("ts", System.currentTimeMillis())
            .putOpt("t_read_ms", ms(step.tReadMs))
            .putOpt("t_prefill_ms", ms(step.tPrefillMs))
            .putOpt("t_decode_ms", ms(step.tDecodeMs))
            .putOpt("t_decide_ms", ms(step.tDecideMs))
            .putOpt("t_act_ms", ms(step.tActMs))
            .putOpt("t_settle_ms", ms(step.tSettleMs))
            .putOpt("n_prompt", count(step.nPrompt))
            .putOpt("n_gen", count(step.nGen))
            .put("parse_ok", step.parseOk)
            .put("raw", step.raw)
            .putOpt("action", step.action)
            .put("valid", step.valid)
            .put("executable", step.executable)
            .put("gate", JSONObject().put("events", JSONArray(step.gateEvents)))
            .putOpt("vm_hwm_kb", Sample.vmHwmKb())
            .putOpt("headroom", Sample.thermalHeadroom(context))
            .putOpt("charge_counter_uah", Sample.chargeCounterUah(context))
            .putOpt("current_now_ua", Sample.currentNowUa(context))
            .putOpt("snapshot", step.snapshot))
    }

    fun appendGate(taskId: String, gateId: String, decision: String, method: String? = null) {
        append(JSONObject()
            .put("type", "gate")
            .put("taskId", taskId)
            .put("gateId", gateId)
            .put("decision", decision)
            .put("ts", System.currentTimeMillis())
            .putOpt("method", method))
    }

    fun appendEvent(kind: String, fields: JSONObject = JSONObject()) {
        append(JSONObject(fields.toString())
            .put("type", "event")
            .put("kind", kind)
            .put("ts", System.currentTimeMillis()))
    }

    fun readAll(): List<JSONObject> {
        if (!file.isFile) return emptyList()
        return file.readLines(StandardCharsets.UTF_8)
            .mapNotNull { line -> line.trim().takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() } }
    }

    /** Every recorded step that carried a `snapshot` (the recorder's input; `tools/eval/record.py`). */
    fun recordedSteps(): List<JSONObject> = readAll().filter { it.optString("type") == "step" && it.has("snapshot") }

    fun clear() {
        file.delete()
    }

    private fun ms(value: Long): Any = if (value < 0) JSONObject.NULL else value

    private fun count(value: Int): Any = if (value < 0) JSONObject.NULL else value

    companion object {
        const val DIR_NAME = "eval"
        const val FILE_NAME = "evallog.jsonl"
    }
}

/**
 * The §12 resource fields. All reads are best-effort: an OEM without the property yields null rather
 * than failing the run (research 07 §F4: VmHWM and the thermal/battery properties are UNVERIFIED).
 */
object Sample {

    /** Peak RSS of this process, `/proc/self/status` `VmHWM`. Null when unreadable (§F4 E-05). */
    fun vmHwmKb(): Int? = runCatching {
        File("/proc/self/status").readLines(StandardCharsets.UTF_8)
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toInt()
    }.getOrNull()

    /** `PowerManager.getThermalHeadroom`, 1.0 = severe throttling (research 07 §F4). */
    fun thermalHeadroom(context: Context, forecastSeconds: Int = 10): Float? = runCatching {
        context.getSystemService(PowerManager::class.java).getThermalHeadroom(forecastSeconds)
    }.getOrNull()

    /** `BatteryManager.CHARGE_COUNTER` in µAh (research 07 §F4). */
    fun chargeCounterUah(context: Context): Int? = battery(context, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

    /** `BatteryManager.CURRENT_NOW` in µA; sign is charge or discharge (research 07 §F4). */
    fun currentNowUa(context: Context): Int? = battery(context, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

    private fun battery(context: Context, property: Int): Int? = runCatching {
        val value = context.getSystemService(BatteryManager::class.java).getIntProperty(property)
        if (value == Int.MIN_VALUE) null else value
    }.getOrNull()

    /** Monotonic helper for the loop's `t_*` measurements. */
    fun nowMs(): Long = SystemClock.elapsedRealtime()
}
