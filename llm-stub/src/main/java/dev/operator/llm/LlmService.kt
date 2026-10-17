package dev.operator.llm

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Kotlin-only `:llm` implementation used by the emulatorStub channel.
 *
 * Generate and label-logit results are read from [FIXTURE] and consumed in order, independently.
 * Keeping the script in an asset makes emulator runs deterministic without loading a model or a
 * native library. All other methods provide the small deterministic behavior needed by an AIDL
 * client exercising the same lifecycle as the real service.
 */
class LlmService : Service() {
    private lateinit var script: Script
    private val generateIndex = AtomicInteger()
    private val labelLogitsIndex = AtomicInteger()
    private val aborted = ConcurrentHashMap.newKeySet<Long>()
    private val active = ConcurrentHashMap.newKeySet<Long>()
    private val loadedRoles = ConcurrentHashMap.newKeySet<ModelRole>()
    private val lastStats = AtomicReference(STUB_STATS)

    override fun onCreate() {
        super.onCreate()
        script = try {
            assets.open(FIXTURE).bufferedReader().use { Script.parse(it.readText()) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not load $FIXTURE", e)
            Script(error = "invalid stub fixture")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder: ILlmService.Stub = object : ILlmService.Stub() {
        override fun load(spec: ModelSpec?, cb: ILlmCallback?) {
            if (spec == null) return error(cb, "invalid load request")
            loadedRoles += spec.role
            done(cb)
        }

        override fun unload(role: String?, cb: ILlmCallback?) {
            val parsedRole = role.toRole() ?: return error(cb, "invalid model role")
            loadedRoles -= parsedRole
            done(cb)
        }

        override fun tokenize(
            role: String?,
            text: String?,
            addSpecial: Boolean,
            parseSpecial: Boolean,
            cb: ILlmCallback?,
        ) {
            if (role.toRole() == null || text == null) return error(cb, "invalid tokenize request")
            val codePoints = text.codePoints().toArray()
            val ids = if (addSpecial) intArrayOf(BOS_TOKEN_ID, *codePoints) else codePoints
            callback(cb) {
                it.onTokenIds(ids)
                it.onDone()
            }
        }

        override fun generate(
            req: GenerateRequest?,
            role: String?,
            promptParts: MutableList<String>?,
            gbnf: String?,
            maxTokens: Int,
            cb: ILlmCallback?,
        ) {
            if (req == null || role.toRole() == null || promptParts == null || maxTokens < 0) {
                return error(cb, "invalid generate request")
            }
            val output = nextGenerate() ?: return error(cb, script.error ?: "generate script exhausted")
            output.error?.let { return error(cb, it) }

            active += req.id
            try {
                var emitted = 0
                for (token in output.tokens) {
                    if (aborted.remove(req.id)) break
                    if (emitted == maxTokens) break
                    if (!callback(cb) { it.onToken(token) }) return
                    emitted++
                }
                val stats = output.stats.copy(nGen = emitted)
                lastStats.set(stats)
                callback(cb) {
                    it.onStats(stats)
                    it.onDone()
                }
            } finally {
                active -= req.id
                aborted -= req.id
            }
        }

        override fun labelLogits(
            req: GenerateRequest?,
            role: String?,
            promptParts: MutableList<String>?,
            labels: MutableList<LabelTokens>?,
            cb: ILlmCallback?,
        ) {
            if (req == null || role.toRole() == null || promptParts == null || labels == null) {
                return error(cb, "invalid labelLogits request")
            }
            val output = nextLabelLogits()
                ?: return error(cb, script.error ?: "labelLogits script exhausted")
            output.error?.let { return error(cb, it) }
            if (output.rows.size != labels.size) {
                return error(cb, "labelLogits fixture row count")
            }

            active += req.id
            try {
                for (row in output.rows) {
                    if (aborted.remove(req.id)) break
                    if (!callback(cb) { it.onLogits(row) }) return
                }
                done(cb)
            } finally {
                active -= req.id
                aborted -= req.id
            }
        }

        override fun embedLast(
            req: GenerateRequest?,
            role: String?,
            tokens: IntArray?,
            cb: ILlmCallback?,
        ) {
            if (req == null || role.toRole() == null || tokens == null) {
                return error(cb, "invalid embedLast request")
            }
            val scale = tokens.maxOfOrNull { kotlin.math.abs(it.toLong()) }?.coerceAtLeast(1L) ?: 1L
            val embedding = FloatArray(tokens.size) { tokens[it].toFloat() / scale.toFloat() }
            callback(cb) {
                it.onEmbedding(embedding)
                it.onDone()
            }
        }

        override fun stateSave(role: String?, path: String?, cb: ILlmCallback?) =
            finishStateCall(role, path, cb)

        override fun stateRestore(role: String?, path: String?, cb: ILlmCallback?) =
            finishStateCall(role, path, cb)

        override fun stateCheckpoint(
            role: String?,
            tag: String?,
            partialOnly: Boolean,
            cb: ILlmCallback?,
        ) = finishStateCall(role, tag, cb)

        override fun abort(reqId: Long, cb: ILlmCallback?) {
            if (active.contains(reqId)) aborted += reqId
            done(cb)
        }

        override fun pid(): Int = android.os.Process.myPid()

        override fun stats(): LlmStats = lastStats.get()

        override fun bench(pp: Int, tg: Int, threads: Int): LlmStats {
            if (pp < 0 || tg < 0 || threads <= 0) return STUB_STATS
            return STUB_STATS.copy(
                nPrompt = pp,
                nGen = tg,
                tPrefillMs = pp.toLong(),
                tDecodeMs = tg.toLong(),
                ppTps = if (pp == 0) 0.0 else 1_000.0,
                tgTps = if (tg == 0) 0.0 else 1_000.0,
            )
        }
    }

    private fun nextGenerate(): GenerateOutput? {
        val index = generateIndex.getAndIncrement()
        return script.generate.getOrNull(index)
    }

    private fun nextLabelLogits(): LabelLogitsOutput? {
        val index = labelLogitsIndex.getAndIncrement()
        return script.labelLogits.getOrNull(index)
    }

    private fun finishStateCall(role: String?, value: String?, cb: ILlmCallback?) {
        if (role.toRole() == null || value.isNullOrBlank()) return error(cb, "invalid state request")
        done(cb)
    }

    private fun done(cb: ILlmCallback?) {
        callback(cb) { it.onDone() }
    }

    private fun error(cb: ILlmCallback?, reason: String) {
        callback(cb) { it.onError(reason) }
    }

    private fun callback(cb: ILlmCallback?, block: (ILlmCallback) -> Unit): Boolean {
        if (cb == null) {
            Log.w(TAG, "Missing callback")
            return false
        }
        return try {
            block(cb)
            true
        } catch (e: RemoteException) {
            Log.w(TAG, "Callback binder died", e)
            false
        }
    }

    private fun String?.toRole(): ModelRole? =
        try {
            this?.let(ModelRole::valueOf)
        } catch (_: IllegalArgumentException) {
            null
        }

    private data class Script(
        val generate: List<GenerateOutput> = emptyList(),
        val labelLogits: List<LabelLogitsOutput> = emptyList(),
        val error: String? = null,
    ) {
        companion object {
            fun parse(json: String): Script {
                val root = JSONObject(json)
                return Script(
                    generate = root.getJSONArray("generate").mapObjects { value ->
                        GenerateOutput(
                            tokens = value.optJSONArray("tokens")?.mapStrings() ?: emptyList(),
                            stats = value.optJSONObject("stats")?.toStats() ?: STUB_STATS,
                            error = value.optionalString("error"),
                        )
                    },
                    labelLogits = root.getJSONArray("labelLogits").mapObjects { value ->
                        LabelLogitsOutput(
                            rows = value.optJSONArray("rows")?.mapFloatArrays() ?: emptyList(),
                            error = value.optionalString("error"),
                        )
                    },
                )
            }
        }
    }

    private data class GenerateOutput(
        val tokens: List<String>,
        val stats: LlmStats,
        val error: String?,
    )

    private data class LabelLogitsOutput(
        val rows: List<FloatArray>,
        val error: String?,
    )

    private companion object {
        const val TAG = "operator.llm.stub"
        const val FIXTURE = "llm-script.json"
        const val BOS_TOKEN_ID = 1

        val STUB_STATS = LlmStats(
            nPrompt = 0,
            nGen = 0,
            tPrefillMs = 0,
            tDecodeMs = 0,
            loadMs = 0,
            ppTps = 0.0,
            tgTps = 0.0,
            backend = LlmBackend.CPU,
            cpuVariant = "stub",
        )

        fun JSONArray.mapStrings(): List<String> =
            List(length()) { index -> getString(index) }

        fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
            List(length()) { index -> transform(getJSONObject(index)) }

        fun JSONArray.mapFloatArrays(): List<FloatArray> =
            List(length()) { rowIndex ->
                getJSONArray(rowIndex).let { row ->
                    FloatArray(row.length()) { columnIndex -> row.getDouble(columnIndex).toFloat() }
                }
            }

        fun JSONObject.optionalString(name: String): String? =
            if (has(name) && !isNull(name)) getString(name) else null

        fun JSONObject.toStats(): LlmStats = LlmStats(
            nPrompt = optInt("nPrompt"),
            nGen = optInt("nGen"),
            tPrefillMs = optLong("tPrefillMs"),
            tDecodeMs = optLong("tDecodeMs"),
            loadMs = optLong("loadMs"),
            ppTps = optDouble("ppTps"),
            tgTps = optDouble("tgTps"),
            backend = optionalString("backend")?.let(LlmBackend::valueOf) ?: LlmBackend.CPU,
            cpuVariant = optString("cpuVariant", "stub"),
        )
    }
}
