package dev.operator.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import dev.operator.core.api.LlmPort
import dev.operator.core.api.StopReason
import dev.operator.core.api.GenerationResult
import dev.operator.core.api.GenerateRequest as CoreRequest
import dev.operator.core.api.ModelSpec as CoreSpec
import dev.operator.core.api.ModelRole as CoreRole
import dev.operator.core.api.LlmStats as CoreStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A dead inference process is distinct from a model or decode failure. */
class InferenceDisconnectedException : IllegalStateException("inference_died")
class InferenceCallException(reason: String) : IllegalStateException(reason)

/** Bound client. Close when the owning task scope ends. */
class LlmClient(private val context: Context) : LlmPort, AutoCloseable {
    private val lock = Any()
    private var service: ILlmService? = null
    private var binding = false
    private val waiting = mutableListOf<CancellableContinuation<ILlmService>>()
    private val pending = mutableSetOf<CompletableDeferred<Unit>>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val listeners: List<CancellableContinuation<ILlmService>>
            val remote = ILlmService.Stub.asInterface(binder)
            synchronized(lock) {
                service = remote
                listeners = waiting.toList()
                waiting.clear()
            }
            listeners.forEach { if (it.isActive) it.resume(remote) }
        }

        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
    }

    private fun disconnected() {
        val listeners: List<CancellableContinuation<ILlmService>>
        val calls: List<CompletableDeferred<Unit>>
        synchronized(lock) {
            service = null
            binding = false
            listeners = waiting.toList()
            waiting.clear()
            calls = pending.toList()
            pending.clear()
        }
        listeners.forEach { if (it.isActive) it.resumeWith(Result.failure(InferenceDisconnectedException())) }
        calls.forEach { it.completeExceptionally(InferenceDisconnectedException()) }
        runCatching { context.unbindService(connection) }
    }

    private suspend fun remote(): ILlmService = suspendCancellableCoroutine { cont ->
        var start = false
        synchronized(lock) {
            val connected = service
            if (connected != null) {
                cont.resume(connected)
                return@suspendCancellableCoroutine
            }
            waiting.add(cont)
            cont.invokeOnCancellation { synchronized(lock) { waiting.remove(cont) } }
            if (!binding) {
                binding = true
                start = true
            }
        }
        if (start) {
            val intent = Intent().setClassName(context.packageName, "dev.operator.llm.LlmService")
            try {
                if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) disconnected()
            } catch (e: RuntimeException) {
                disconnected()
            }
        }
    }

    private suspend fun call(block: (ILlmService, ILlmCallback) -> Unit): Reply {
        val result = Reply()
        val done = CompletableDeferred<Unit>()
        synchronized(lock) { pending.add(done) }
        try {
            val api = remote()
            val cb = object : ILlmCallback.Stub() {
                override fun onToken(text: String?) = receive {
                    if (text != null) {
                        PayloadGuard.check(text)
                        synchronized(result) { result.text.append(text) }
                    }
                }
                override fun onTokenIds(ids: IntArray?) = receive {
                    PayloadGuard.checkArray(ids?.size ?: 0, 4)
                    synchronized(result) { result.ids = ids?.toList() ?: emptyList() }
                }
                override fun onLogits(logits: FloatArray?) = receive {
                    PayloadGuard.checkArray(logits?.size ?: 0, 4)
                    synchronized(result) { result.rows.add(logits ?: FloatArray(0)) }
                }
                override fun onEmbedding(values: FloatArray?) = receive {
                    PayloadGuard.checkArray(values?.size ?: 0, 4)
                    synchronized(result) { result.embedding = values ?: FloatArray(0) }
                }
                override fun onStats(stats: LlmStats?) { synchronized(result) { result.stats = stats } }
                override fun onDone() { done.complete(Unit) }
                override fun onError(reason: String?) {
                    done.completeExceptionally(InferenceCallException(reason ?: "inference_error"))
                }
                private fun receive(block: () -> Unit) {
                    try { block() } catch (e: Exception) { done.completeExceptionally(e) }
                }
            }
            block(api, cb)
            done.await()
            return result
        } catch (e: RemoteException) {
            throw InferenceDisconnectedException()
        } finally {
            synchronized(lock) { pending.remove(done) }
        }
    }

    private class Reply {
        val text = StringBuilder()
        var ids: List<Int> = emptyList()
        val rows = mutableListOf<FloatArray>()
        var embedding = FloatArray(0)
        var stats: LlmStats? = null
    }

    override suspend fun load(spec: CoreSpec) {
        PayloadGuard.check(spec.path)
        call { api, cb -> api.load(ModelSpec(spec.path, spec.sha256, ModelRole.valueOf(spec.role.name),
            spec.nCtx, KvType.valueOf(spec.kvType.name), LlmBackend.valueOf(spec.backend.name),
            spec.threads, spec.useExtraBufts), cb) }
    }
    override suspend fun unload(role: CoreRole) { call { api, cb -> api.unload(role.name, cb) } }
    override suspend fun tokenize(role: CoreRole, text: String, addSpecial: Boolean, parseSpecial: Boolean): List<Int> {
        PayloadGuard.check(text)
        return call { api, cb -> api.tokenize(role.name, text, addSpecial, parseSpecial, cb) }.ids
    }
    private fun CoreRequest.parcel() = GenerateRequest(id, Sampling(sampling.greedy, sampling.temperature,
        sampling.topK, sampling.topP, sampling.minP, sampling.seed), sequenceId, reusePrefix, grammarFirst)

    override suspend fun generate(req: CoreRequest, role: CoreRole, promptParts: List<String>,
        gbnf: String?, maxTokens: Int, onToken: (String) -> Unit): GenerationResult {
        PayloadGuard.check(*(promptParts + listOfNotNull(gbnf)).toTypedArray())
        // The callback may run on a binder thread; callers must not assume main-thread affinity.
        val reply = call { api, cb -> api.generate(req.parcel(), role.name, promptParts, gbnf, maxTokens,
            object : ILlmCallback.Stub() {
                override fun onToken(text: String?) {
                    cb.onToken(text)
                    if (text != null) {
                        try { onToken(text) } catch (e: Exception) { cb.onError("token callback failed") }
                    }
                }
                override fun onTokenIds(ids: IntArray?) = cb.onTokenIds(ids)
                override fun onLogits(logits: FloatArray?) = cb.onLogits(logits)
                override fun onEmbedding(values: FloatArray?) = cb.onEmbedding(values)
                override fun onStats(stats: LlmStats?) = cb.onStats(stats)
                override fun onDone() = cb.onDone()
                override fun onError(reason: String?) = cb.onError(reason)
            }) }
        return GenerationResult(reply.text.toString(), reply.ids,
            if (reply.ids.isNotEmpty() && reply.ids.size < maxTokens) StopReason.EOG else StopReason.MAX_TOKENS,
            reply.stats?.core() ?: emptyStats())
    }
    override suspend fun labelLogits(req: CoreRequest, role: CoreRole, promptParts: List<String>,
        labelTokenIds: List<List<Int>>): List<FloatArray> {
        PayloadGuard.check(*promptParts.toTypedArray())
        PayloadGuard.checkArray(labelTokenIds.sumOf { it.size }, 4)
        return call { api, cb -> api.labelLogits(req.parcel(), role.name, promptParts,
            labelTokenIds.map { LabelTokens(it.toIntArray()) }, cb) }.rows
    }
    override suspend fun embedLast(req: CoreRequest, role: CoreRole, tokens: List<Int>): FloatArray {
        PayloadGuard.checkArray(tokens.size, 4)
        return call { api, cb -> api.embedLast(req.parcel(), role.name, tokens.toIntArray(), cb) }.embedding
    }
    override suspend fun stateSave(role: CoreRole, path: String) {
        PayloadGuard.check(path)
        call { api, cb -> api.stateSave(role.name, path, cb) }
    }
    override suspend fun stateRestore(role: CoreRole, path: String) {
        PayloadGuard.check(path)
        call { api, cb -> api.stateRestore(role.name, path, cb) }
    }
    override suspend fun stateCheckpoint(role: CoreRole, tag: String, partialOnly: Boolean) {
        PayloadGuard.check(tag)
        call { api, cb -> api.stateCheckpoint(role.name, tag, partialOnly, cb) }
    }
    override suspend fun abort(reqId: Long) { call { api, cb -> api.abort(reqId, cb) } }
    override suspend fun pid(): Int = try { remote().pid() } catch (e: RemoteException) {
        throw InferenceDisconnectedException()
    }
    override suspend fun stats(): CoreStats = try { remote().stats().core() } catch (e: RemoteException) {
        throw InferenceDisconnectedException()
    }
    override suspend fun bench(pp: Int, tg: Int, threads: Int): CoreStats = try {
        remote().bench(pp, tg, threads).core()
    } catch (e: RemoteException) { throw InferenceDisconnectedException() }

    private fun LlmStats.core() = CoreStats(nPrompt, nGen, tPrefillMs, tDecodeMs, loadMs,
        ppTps, tgTps, dev.operator.core.api.LlmBackend.valueOf(backend.name), cpuVariant)
    private fun emptyStats() = CoreStats(0, 0, 0, 0, 0, 0.0, 0.0, dev.operator.core.api.LlmBackend.CPU, "")
    override fun close() {
        disconnected()
        synchronized(lock) {
            if (binding) context.unbindService(connection)
            binding = false
        }
    }
}
