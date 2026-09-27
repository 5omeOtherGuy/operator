package dev.operator.llm

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * F0 stub of the `:llm` service (FOUNDATION §2.3, §4; ADR-0002, ADR-0005).
 *
 * It is declared in this library's manifest, in its own process `:llm`, and answers every AIDL call
 * with `onError("not implemented: <call>")` through the callback. [pid], [stats] and [bench] answer
 * directly because §2.3 has no callback for them. S1 replaces the body with the real inference
 * engine; the AIDL surface of `:llm-api` and the process declaration stay unchanged.
 */
class LlmService : Service() {

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder: ILlmService.Stub = object : ILlmService.Stub() {

        override fun load(spec: ModelSpec?, cb: ILlmCallback?) = notImplemented(cb, "load")

        override fun unload(role: String?, cb: ILlmCallback?) = notImplemented(cb, "unload")

        override fun tokenize(role: String?, text: String?, addSpecial: Boolean, parseSpecial: Boolean, cb: ILlmCallback?) =
            notImplemented(cb, "tokenize")

        override fun generate(
            req: GenerateRequest?,
            role: String?,
            promptParts: MutableList<String>?,
            gbnf: String?,
            maxTokens: Int,
            cb: ILlmCallback?,
        ) = notImplemented(cb, "generate")

        override fun labelLogits(
            req: GenerateRequest?,
            role: String?,
            promptParts: MutableList<String>?,
            labels: MutableList<LabelTokens>?,
            cb: ILlmCallback?,
        ) = notImplemented(cb, "labelLogits")

        override fun embedLast(req: GenerateRequest?, role: String?, tokens: IntArray?, cb: ILlmCallback?) =
            notImplemented(cb, "embedLast")

        override fun stateSave(role: String?, path: String?, cb: ILlmCallback?) = notImplemented(cb, "stateSave")

        override fun stateRestore(role: String?, path: String?, cb: ILlmCallback?) = notImplemented(cb, "stateRestore")

        override fun stateCheckpoint(role: String?, tag: String?, partialOnly: Boolean, cb: ILlmCallback?) =
            notImplemented(cb, "stateCheckpoint")

        override fun abort(reqId: Long, cb: ILlmCallback?) = notImplemented(cb, "abort")

        override fun pid(): Int = android.os.Process.myPid()

        override fun stats(): LlmStats = STUB_STATS

        override fun bench(pp: Int, tg: Int, threads: Int): LlmStats = STUB_STATS
    }

    private fun notImplemented(cb: ILlmCallback?, call: String) {
        Log.w(TAG, "$call: not implemented")
        cb?.onError("not implemented: $call")
    }

    private companion object {
        const val TAG = "operator.llm"

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
    }
}
