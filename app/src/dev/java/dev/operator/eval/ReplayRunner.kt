package dev.operator.eval

import dev.operator.core.api.Bounds
import dev.operator.core.api.ElementKey
import dev.operator.core.api.GlobalAction
import dev.operator.core.api.HandsPort
import dev.operator.core.api.NodeAction
import dev.operator.core.api.NodeState
import dev.operator.core.api.NotificationRecord
import dev.operator.core.api.Point
import dev.operator.core.api.Role
import dev.operator.core.api.ScreenSignature
import dev.operator.core.api.Snapshot
import dev.operator.core.api.UiNode
import dev.operator.core.api.WindowInfo
import dev.operator.core.api.WindowType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * `ReplayRunner` (FOUNDATION §12; ADR-0015; research 07 §R1/F3).
 *
 * It reads an `op-replay-v0` JSONL file of recorded `UiNode` trees, rebuilds each [Snapshot] and feeds
 * it to the loop **through [HandsPort] fakes**, one recorded step at a time. It performs **no actions**:
 * the fake hands count every effect call as a violation and never touch the device. A well-behaved
 * replay ends with `violations == 0`.
 *
 * The format is documented in `tools/eval/README.md` and produced by `tools/eval/record.py` (from an
 * EvalLog recording) or `tools/eval/convert_ac.py` (`ac-sub-v0`). This class mirrors the reader in
 * `tools/eval/replay_v0.py`; the two must stay in step.
 */
class ReplayRunner(private val log: EvalLog) {

    /** One recorded step: its snapshot plus the gold reference action (`gold` is opaque here). */
    data class ReplayStep(
        val index: Int,
        val taskId: String,
        val goal: String,
        val instruction: String,
        val snapshot: Snapshot,
        val gold: JSONObject,
    )

    data class Summary(val file: String, val steps: Int, val violations: Int, val error: String? = null)

    suspend fun run(file: File, consumer: ReplayStepConsumer? = EvalHooks.replayConsumer): Summary {
        val steps = try {
            parse(file)
        } catch (failure: Exception) {
            log.appendEvent("replay", JSONObject().put("file", file.name).put("error", failure.message ?: "parse failed"))
            return Summary(file.name, 0, 0, failure.message ?: "parse failed")
        }
        val hands = RecordedHands(steps.map { it.snapshot })
        val consumerOrNoop = consumer ?: ReplayStepConsumer { _, _ -> }
        steps.forEachIndexed { index, step ->
            hands.cursor = index
            consumerOrNoop.accept(step, hands)
        }
        val summary = Summary(file.name, steps.size, hands.violations)
        log.appendEvent("replay", JSONObject()
            .put("file", file.name)
            .put("steps", summary.steps)
            .put("violations", summary.violations))
        return summary
    }

    companion object {

        /** Parse an `op-replay-v0` file into its steps; `meta` and blank lines are skipped. */
        fun parse(file: File): List<ReplayStep> {
            val steps = mutableListOf<ReplayStep>()
            file.readLines(StandardCharsets.UTF_8).forEachIndexed { lineNumber, line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEachIndexed
                val record = JSONObject(trimmed)
                if (record.optString("type") != "step") return@forEachIndexed
                val snapshotJson = record.optJSONObject("snapshot")
                    ?: throw IllegalArgumentException("line ${lineNumber + 1}: step without a snapshot")
                steps += ReplayStep(
                    index = record.optInt("i", steps.size),
                    taskId = record.optString("taskId"),
                    goal = record.optString("goal"),
                    instruction = record.optString("instruction"),
                    snapshot = parseSnapshot(snapshotJson),
                    gold = record.optJSONObject("gold") ?: JSONObject(),
                )
            }
            return steps
        }

        /** Rebuild the frozen [Snapshot] from its `op-replay-v0` JSON. */
        fun parseSnapshot(json: JSONObject): Snapshot {
            val windowsJson = json.optJSONArray("windows") ?: JSONArray()
            val windows = (0 until windowsJson.length()).map { i -> parseWindow(windowsJson.getJSONObject(i)) }
            val nodesJson = json.optJSONArray("nodes") ?: JSONArray()
            val nodes = (0 until nodesJson.length()).map { i -> parseNode(nodesJson.getJSONObject(i)) }
            val signatureJson = json.optJSONObject("screenSignature")
            return Snapshot(
                id = json.optLong("id"),
                capturedAtMs = json.optLong("capturedAtMs"),
                foregroundPackage = json.optString("foregroundPackage"),
                windows = windows,
                nodes = nodes,
                structuralHash = json.optLong("structuralHash"),
                fullHash = json.optLong("fullHash"),
                keyboardUp = json.optBoolean("keyboardUp"),
                focusedIndex = if (json.isNull("focusedIndex")) null else json.optInt("focusedIndex"),
                screenSignature = ScreenSignature(
                    packageName = signatureJson?.optString("packageName") ?: json.optString("foregroundPackage"),
                    windowTitle = signatureJson?.optStringOrNull("windowTitle"),
                    structuralHash = signatureJson?.optLong("structuralHash") ?: json.optLong("structuralHash"),
                ),
            )
        }

        private fun parseWindow(json: JSONObject) = WindowInfo(
            id = json.optInt("id"),
            type = enumValue(WindowType.values(), json.optString("type"), WindowType.APPLICATION),
            layer = json.optInt("layer"),
            packageName = json.optString("packageName"),
            title = json.optStringOrNull("title"),
            active = json.optBoolean("active"),
            rootNodeIndex = if (json.isNull("rootNodeIndex")) null else json.optInt("rootNodeIndex"),
        )

        private fun parseNode(json: JSONObject): UiNode {
            val boundsJson = json.optJSONObject("bounds") ?: JSONObject()
            val childrenJson = json.optJSONArray("children") ?: JSONArray()
            return UiNode(
                index = json.optInt("index"),
                key = ElementKey(json.optString("key").toLongOrNull() ?: json.optString("key").hashCode().toLong()),
                windowId = json.optInt("windowId"),
                packageName = json.optString("packageName"),
                role = enumValue(Role.values(), json.optString("role"), Role.TXT),
                label = json.optString("label"),
                className = json.optStringOrNull("className"),
                viewId = json.optStringOrNull("viewId"),
                uniqueId = json.optStringOrNull("uniqueId"),
                bounds = Bounds(boundsJson.optInt("left"), boundsJson.optInt("top"),
                    boundsJson.optInt("right"), boundsJson.optInt("bottom")),
                depth = json.optInt("depth"),
                parentIndex = if (json.isNull("parentIndex")) null else json.optInt("parentIndex"),
                children = (0 until childrenJson.length()).map { i -> childrenJson.getInt(i) },
                actions = json.optJSONArray("actions")?.let { array ->
                    (0 until array.length()).mapNotNull { i -> enumValueOrNull(NodeAction.values(), array.getString(i)) }.toSet()
                } ?: emptySet(),
                state = json.optJSONArray("state")?.let { array ->
                    (0 until array.length()).mapNotNull { i -> enumValueOrNull(NodeState.values(), array.getString(i)) }.toSet()
                } ?: emptySet(),
                row = if (json.isNull("row")) null else json.optInt("row"),
                column = if (json.isNull("column")) null else json.optInt("column"),
                windowTitle = json.optStringOrNull("windowTitle"),
            )
        }

        private fun <T : Enum<T>> enumValue(values: Array<T>, token: String, fallback: T): T =
            enumValueOrNull(values, token) ?: fallback

        private fun <T : Enum<T>> enumValueOrNull(values: Array<T>, token: String): T? {
            if (token.isBlank()) return null
            val upper = token.uppercase(Locale.ROOT).replace('-', '_')
            return values.firstOrNull { it.name == upper }
        }

        private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
    }
}

/**
 * The loop's entry for one recorded step. The loop reads the screen through [hands] — the only way to
 * observe it — and must perform no effects; the replay's [RecordedHands] counts any that it does.
 */
fun interface ReplayStepConsumer {
    suspend fun accept(step: ReplayRunner.ReplayStep, hands: HandsPort)
}

/**
 * The replay's [HandsPort]: it answers reads from the recorded snapshot and counts every effect call
 * as a violation (it never performs one). `snapshot()` returns the step the runner is positioned on.
 */
class RecordedHands(private val snapshots: List<Snapshot>) : HandsPort {

    @Volatile
    var cursor: Int = 0

    @Volatile
    var violations: Int = 0
        private set

    override suspend fun snapshot(): Snapshot = snapshots[cursor]

    override suspend fun awaitIdle(quietMs: Long, maxMs: Long): Boolean = true

    override suspend fun isConnected(): Boolean = true

    override suspend fun performNodeAction(key: ElementKey, action: NodeAction, text: String?) {
        violations += 1
    }

    override suspend fun gestureTap(point: Point, durationMs: Long) {
        violations += 1
    }

    override suspend fun gestureSwipe(from: Point, to: Point, durationMs: Long) {
        violations += 1
    }

    override suspend fun globalAction(action: GlobalAction) {
        violations += 1
    }

    override suspend fun notifications(): List<NotificationRecord> = emptyList()

    override suspend fun takeScreenshot(): ByteArray? = null
}
