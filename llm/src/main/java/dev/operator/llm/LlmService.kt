package dev.operator.llm

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max

/** Real, process-isolated implementation of the frozen [ILlmService] surface. */
class LlmService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob)
    private val engines = ConcurrentHashMap<ModelRole, NativeModelEngine>()
    private val requests = ConcurrentHashMap<Long, NativeModelEngine>()
    private lateinit var pins: Map<String, ModelPin>

    override fun onCreate() {
        super.onCreate()
        pins = assets.open(MODELS_ASSET).bufferedReader().use { reader ->
            parsePins(reader.readText())
        }
        OperatorNative.initialize(applicationInfo.nativeLibraryDir)
        Log.i(TAG, "Native engine initialized: ${OperatorNative.systemInfo()}")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceJob.cancel()
        engines.values.forEach(NativeModelEngine::destroy)
        engines.clear()
        requests.clear()
        super.onDestroy()
    }

    private val binder = object : ILlmService.Stub() {
        override fun load(spec: ModelSpec?, cb: ILlmCallback?) {
            if (spec == null) return fail(cb, "invalid_load")
            launch(cb) {
                validateSpec(spec)
                val replacement = NativeModelEngine(spec, OperatorNative.systemInfo().take(CPU_INFO_LIMIT))
                try {
                    replacement.loadModel(spec.path)
                    engines.put(spec.role, replacement)?.destroy()
                } catch (e: Exception) {
                    replacement.destroy()
                    throw e
                }
            }
        }

        override fun unload(role: String?, cb: ILlmCallback?) {
            val parsed = parseRole(role) ?: return fail(cb, "invalid_role")
            launch(cb) { engines.remove(parsed)?.destroy() }
        }

        override fun tokenize(
            role: String?,
            text: String?,
            addSpecial: Boolean,
            parseSpecial: Boolean,
            cb: ILlmCallback?,
        ) {
            val engine = engine(role, cb) ?: return
            if (text == null) return fail(cb, "invalid_tokenize")
            launch(cb) {
                PayloadGuard.check(text)
                val tokens = engine.tokenize(text, addSpecial, parseSpecial)
                PayloadGuard.checkArray(tokens.size, Int.SIZE_BYTES)
                callback(cb) { it.onTokenIds(tokens) }
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
            val engine = engine(role, cb) ?: return
            if (req == null || promptParts == null) return fail(cb, "invalid_generate")
            launchRequest(req.id, engine, cb) {
                require(maxTokens in 0..MAX_GENERATED_TOKENS)
                PayloadGuard.check(*(promptParts + listOfNotNull(gbnf)).toTypedArray())
                val prompt = engine.tokenize(promptParts.joinToString(""), false, true)
                val generated = engine.generate(req, prompt, gbnf, maxTokens)
                PayloadGuard.checkArray(generated.size, Int.SIZE_BYTES)
                callback(cb) { it.onTokenIds(generated) }
                generated.forEach { token ->
                    val text = engine.detokenize(intArrayOf(token))
                    PayloadGuard.check(text)
                    callback(cb) { it.onToken(text) }
                }
                callback(cb) { it.onStats(engine.operatorStats()) }
            }
        }

        override fun labelLogits(
            req: GenerateRequest?,
            role: String?,
            promptParts: MutableList<String>?,
            labels: MutableList<LabelTokens>?,
            cb: ILlmCallback?,
        ) {
            val engine = engine(role, cb) ?: return
            if (req == null || promptParts == null || labels == null) {
                return fail(cb, "invalid_label_logits")
            }
            launchRequest(req.id, engine, cb) {
                PayloadGuard.check(*promptParts.toTypedArray())
                val labelArrays = labels.map { it.tokenIds }
                PayloadGuard.checkArray(labelArrays.sumOf { it.size }, Int.SIZE_BYTES)
                val prompt = engine.tokenize(promptParts.joinToString(""), false, true)
                engine.labelLogits(req, prompt, labelArrays).forEach { row ->
                    PayloadGuard.checkArray(row.size, Float.SIZE_BYTES)
                    callback(cb) { it.onLogits(row) }
                }
            }
        }

        override fun embedLast(
            req: GenerateRequest?,
            role: String?,
            tokens: IntArray?,
            cb: ILlmCallback?,
        ) {
            fail(cb, "unsupported in M1")
        }

        override fun stateSave(role: String?, path: String?, cb: ILlmCallback?) {
            val engine = engine(role, cb) ?: return
            if (path == null) return fail(cb, "invalid_state_path")
            launch(cb) {
                PayloadGuard.check(path)
                engine.stateSave(path)
            }
        }

        override fun stateRestore(role: String?, path: String?, cb: ILlmCallback?) {
            val engine = engine(role, cb) ?: return
            if (path == null) return fail(cb, "invalid_state_path")
            launch(cb) {
                PayloadGuard.check(path)
                engine.stateRestore(path)
            }
        }

        override fun stateCheckpoint(
            role: String?,
            tag: String?,
            partialOnly: Boolean,
            cb: ILlmCallback?,
        ) {
            val engine = engine(role, cb) ?: return
            if (tag == null || !SAFE_TAG.matches(tag)) return fail(cb, "invalid_checkpoint_tag")
            launch(cb) {
                PayloadGuard.check(tag)
                val directory = File(filesDir, "checkpoints").also { require(it.mkdirs() || it.isDirectory) }
                engine.stateCheckpoint(File(directory, "${engine.modelId}-$tag.state").path, partialOnly)
            }
        }

        override fun abort(reqId: Long, cb: ILlmCallback?) {
            // Do not queue this on a model dispatcher: it must interrupt a native call occupying it.
            requests[reqId]?.abort(reqId)
            complete(cb)
        }

        override fun pid(): Int = Process.myPid()

        override fun stats(): LlmStats =
            engines[ModelRole.PLANNER]?.operatorStats()
                ?: engines.values.firstOrNull()?.operatorStats()
                ?: EMPTY_LLM_STATS

        override fun bench(pp: Int, tg: Int, threads: Int): LlmStats {
            val engine = engines[ModelRole.PLANNER] ?: engines.values.firstOrNull()
                ?: return EMPTY_LLM_STATS
            return runBlocking { engine.benchmark(pp, tg, threads) }
        }
    }

    private fun validateSpec(spec: ModelSpec) {
        PayloadGuard.check(spec.path, spec.sha256)
        require(spec.nCtx in 1..MAX_CONTEXT)
        require(spec.threads in 1..MAX_THREADS)
        val file = File(spec.path)
        require(file.isFile && file.canRead())
        val pin = pins[spec.sha256.lowercase()] ?: error("model_not_pinned")
        require(pin.file == file.name && pin.size == file.length())
        require(pin.role == if (spec.role == ModelRole.PLANNER) "generate" else "decide-encoder")
        require(ModelGuards.sha256Matches(file, pin.sha256)) { "model_hash_mismatch" }
        val types = ModelGuards.tensorTypes(file)
        require(ModelGuards.allowed(types, spec.role == ModelRole.ENCODER)) {
            "model_tensor_type_not_allowed"
        }
    }

    private fun engine(role: String?, cb: ILlmCallback?): NativeModelEngine? {
        val parsed = parseRole(role) ?: run {
            fail(cb, "invalid_role")
            return null
        }
        return engines[parsed] ?: run {
            fail(cb, "model_not_loaded")
            null
        }
    }

    private fun parseRole(role: String?): ModelRole? =
        try {
            role?.let(ModelRole::valueOf)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun launch(cb: ILlmCallback?, block: suspend () -> Unit) {
        serviceScope.launch {
            try {
                block()
                complete(cb)
            } catch (e: Exception) {
                Log.w(TAG, "Inference call failed", e)
                fail(cb, e.message?.take(160) ?: "inference_error")
            }
        }
    }

    private fun launchRequest(
        id: Long,
        engine: NativeModelEngine,
        cb: ILlmCallback?,
        block: suspend () -> Unit,
    ) {
        if (requests.putIfAbsent(id, engine) != null) return fail(cb, "duplicate_request_id")
        launch(cb) {
            try {
                block()
            } finally {
                requests.remove(id, engine)
            }
        }
    }

    private fun complete(cb: ILlmCallback?) {
        callback(cb) { it.onDone() }
    }

    private fun fail(cb: ILlmCallback?, reason: String) {
        callback(cb) { it.onError(reason) }
    }

    private fun callback(cb: ILlmCallback?, call: (ILlmCallback) -> Unit): Boolean {
        if (cb == null) return false
        return try {
            call(cb)
            true
        } catch (e: RemoteException) {
            Log.w(TAG, "Callback process died", e)
            false
        }
    }

    private data class ModelPin(
        val role: String,
        val file: String,
        val size: Long,
        val sha256: String,
    )

    private companion object {
        const val TAG = "operator.llm"
        const val MODELS_ASSET = "models.json"
        const val MAX_CONTEXT = 1_048_576
        const val MAX_THREADS = 256
        const val MAX_GENERATED_TOKENS = 65_536
        const val CPU_INFO_LIMIT = 2_048
        val SAFE_TAG = Regex("[A-Za-z0-9._-]{1,80}")

        fun parsePins(json: String): Map<String, ModelPin> {
            val models = JSONObject(json).getJSONArray("models")
            return buildMap {
                for (index in 0 until models.length()) {
                    val value = models.getJSONObject(index)
                    val pin = ModelPin(
                        role = value.getString("role"),
                        file = value.getString("file"),
                        size = value.getLong("size"),
                        sha256 = value.getString("sha256").lowercase(),
                    )
                    require(pin.sha256.matches(Regex("[0-9a-f]{64}")))
                    require(put(pin.sha256, pin) == null) { "duplicate model pin" }
                }
            }
        }
    }
}

private class NativeModelEngine(
    private val spec: ModelSpec,
    private val cpuVariant: String,
) : OperatorInferenceEngine {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "llm-${spec.role.name.lowercase()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val mutableState = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Initialized)
    override val state: StateFlow<InferenceEngine.State> = mutableState.asStateFlow()
    private val sequenceTokens = mutableMapOf<Int, IntArray>()
    private val activeRequests = ConcurrentHashMap.newKeySet<Long>()
    private val abortedRequests = ConcurrentHashMap.newKeySet<Long>()
    private var modelHandle = 0L
    @Volatile private var contextHandle = 0L
    @Volatile private var closed = false
    private var systemPrompt = ""

    val modelId: String get() = spec.sha256.lowercase()

    override suspend fun loadModel(pathToModel: String) = withContext(dispatcher) {
        check(!closed && modelHandle == 0L)
        mutableState.value = InferenceEngine.State.LoadingModel
        try {
            val allowed = if (spec.role == ModelRole.ENCODER) ENCODER_TYPES else PLANNER_TYPES
            modelHandle = OperatorNative.loadModel(
                pathToModel, allowed, spec.useExtraBufts, spec.backend.ordinal,
            )
            contextHandle = OperatorNative.newContext(
                modelHandle = modelHandle,
                nCtx = spec.nCtx,
                nBatch = minOf(spec.nCtx, DEFAULT_BATCH),
                nSeqMax = MAX_SEQUENCES,
                nThreads = spec.threads,
                typeK = spec.kvType.ggmlType,
                typeV = spec.kvType.ggmlType,
                embeddings = spec.role == ModelRole.ENCODER,
            )
            OperatorNative.attachThreadpool(contextHandle, spec.threads, spec.threads, null)
            mutableState.value = InferenceEngine.State.ModelReady
        } catch (e: Exception) {
            mutableState.value = InferenceEngine.State.Error(e)
            releaseHandles()
            throw e
        }
    }

    override suspend fun setSystemPrompt(systemPrompt: String) = withContext(dispatcher) {
        require(systemPrompt.isNotBlank())
        this@NativeModelEngine.systemPrompt = systemPrompt
    }

    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
        val prompt = tokenize(systemPrompt + message, false, true)
        val request = GenerateRequest(
            id = System.nanoTime(),
            sampling = Sampling(),
            sequenceId = 0,
            reusePrefix = true,
            grammarFirst = false,
        )
        generate(request, prompt, null, predictLength).forEach { token ->
            emit(detokenize(intArrayOf(token)))
        }
    }

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String {
        val result = benchmark(pp, tg, pl)
        return "pp ${result.ppTps} t/s, tg ${result.tgTps} t/s"
    }

    override fun cleanUp() = destroy()

    @Synchronized
    override fun destroy() {
        if (closed) return
        closed = true
        runBlocking(dispatcher) { releaseHandles() }
        dispatcher.close()
        mutableState.value = InferenceEngine.State.Uninitialized
    }

    override suspend fun tokenize(
        text: String,
        addSpecial: Boolean,
        parseSpecial: Boolean,
    ): IntArray = withContext(dispatcher) {
        requireReady()
        OperatorNative.tokenize(modelHandle, text, addSpecial, parseSpecial)
    }

    suspend fun detokenize(tokens: IntArray): String = withContext(dispatcher) {
        requireReady()
        OperatorNative.detokenize(modelHandle, tokens, false, false)
    }

    override suspend fun generate(
        request: GenerateRequest,
        prompt: IntArray,
        gbnf: String?,
        maxTokens: Int,
    ): IntArray = withContext(dispatcher) {
        requireReady()
        validateRequest(request)
        require(prompt.isNotEmpty())
        val prefix = reusablePrefix(request, prompt)
        activeRequests += request.id
        try {
            check(!abortedRequests.remove(request.id)) { "request_aborted" }
            val sampling = request.sampling
            val generated = OperatorNative.generate(
                contextHandle, request.sequenceId, prompt, prefix, gbnf, maxTokens,
                if (sampling.greedy || gbnf != null) 0f else sampling.temperature,
                sampling.topK, sampling.topP, sampling.minP, sampling.seed, request.grammarFirst,
            )
            sequenceTokens[request.sequenceId] = prompt + generated
            generated
        } finally {
            activeRequests -= request.id
            abortedRequests -= request.id
        }
    }

    override suspend fun labelLogits(
        request: GenerateRequest,
        prompt: IntArray,
        labels: List<IntArray>,
    ): List<FloatArray> = withContext(dispatcher) {
        requireReady()
        validateRequest(request)
        require(request.sequenceId != SOURCE_SEQUENCE) { "label_branch_must_be_nonzero" }
        require(prompt.isNotEmpty() && labels.isNotEmpty() && labels.all { it.isNotEmpty() })
        val source = sequenceTokens[SOURCE_SEQUENCE] ?: error("source_sequence_not_prefilled")
        var sourceLength = ModelGuards.commonPrefix(source.asList(), prompt.asList())
        if (sourceLength == prompt.size) sourceLength--
        require(sourceLength >= 0)
        val suffix = prompt.copyOfRange(sourceLength, prompt.size)
        val branch = if (request.sequenceId == SOURCE_SEQUENCE) 1 else request.sequenceId
        activeRequests += request.id
        try {
            check(!abortedRequests.remove(request.id)) { "request_aborted" }
            labels.map { label ->
                OperatorNative.labelLogits(
                    contextHandle, SOURCE_SEQUENCE, branch, sourceLength, suffix, label,
                )
            }
        } finally {
            activeRequests -= request.id
            abortedRequests -= request.id
        }
    }

    override suspend fun embedLast(
        request: GenerateRequest,
        tokens: IntArray,
    ): FloatArray = withContext(dispatcher) {
        requireReady()
        validateRequest(request)
        require(tokens.isNotEmpty())
        val prefix = reusablePrefix(request, tokens)
        activeRequests += request.id
        try {
            check(!abortedRequests.remove(request.id)) { "request_aborted" }
            OperatorNative.embedLast(contextHandle, request.sequenceId, tokens, prefix).also {
                sequenceTokens[request.sequenceId] = tokens
            }
        } finally {
            activeRequests -= request.id
            abortedRequests -= request.id
        }
    }

    override suspend fun stateSave(path: String) = withContext(dispatcher) {
        requireReady()
        val tokens = sequenceTokens[SOURCE_SEQUENCE] ?: IntArray(0)
        require(OperatorNative.saveStateFile(contextHandle, SOURCE_SEQUENCE, path, tokens) > 0)
    }

    override suspend fun stateRestore(path: String) = withContext(dispatcher) {
        requireReady()
        val tokens = OperatorNative.restoreStateFile(
            contextHandle, SOURCE_SEQUENCE, path, spec.nCtx,
        )
        sequenceTokens[SOURCE_SEQUENCE] = tokens
    }

    override suspend fun stateCheckpoint(path: String, partialOnly: Boolean) =
        withContext(dispatcher) {
            requireReady()
            val state = OperatorNative.saveState(
                contextHandle, SOURCE_SEQUENCE, if (partialOnly) PARTIAL_ONLY else STATE_NONE,
            )
            File(path).outputStream().buffered().use { it.write(state) }
        }

    override fun abort(requestId: Long) {
        abortedRequests += requestId
        if (requestId in activeRequests) OperatorNative.abort(contextHandle)
    }

    @Synchronized
    override fun operatorStats(): LlmStats {
        val handle = contextHandle
        if (handle == 0L) return EMPTY_LLM_STATS
        val values = OperatorNative.stats(handle)
        if (values.size < 5) return EMPTY_LLM_STATS
        val prompt = values[0].toInt()
        val generated = values[1].toInt()
        val prefillMs = values[2].toLong()
        val decodeMs = values[3].toLong()
        return LlmStats(
            nPrompt = prompt,
            nGen = generated,
            tPrefillMs = prefillMs,
            tDecodeMs = decodeMs,
            loadMs = values[4].toLong(),
            ppTps = rate(prompt, prefillMs),
            tgTps = rate(generated, decodeMs),
            backend = spec.backend,
            cpuVariant = cpuVariant,
        )
    }

    suspend fun benchmark(pp: Int, tg: Int, threads: Int): LlmStats = withContext(dispatcher) {
        requireReady()
        require(pp > 0 && tg >= 0 && threads in 1..256)
        OperatorNative.attachThreadpool(contextHandle, threads, threads, null)
        try {
            val values = OperatorNative.bench(contextHandle, pp, tg)
            require(values.size >= 4)
            LlmStats(
                pp, tg, values[2].toLong(), values[3].toLong(),
                operatorStats().loadMs, values[0], values[1], spec.backend, cpuVariant,
            )
        } finally {
            OperatorNative.attachThreadpool(contextHandle, spec.threads, spec.threads, null)
        }
    }

    private fun reusablePrefix(request: GenerateRequest, tokens: IntArray): Int {
        if (!request.reusePrefix) return 0
        val previous = sequenceTokens[request.sequenceId] ?: return 0
        return ModelGuards.commonPrefix(previous.asList(), tokens.asList())
            .coerceAtMost(max(0, tokens.size - 1))
    }

    private fun validateRequest(request: GenerateRequest) {
        require(request.sequenceId in 0 until MAX_SEQUENCES)
        require(request.sampling.temperature.isFinite() && request.sampling.temperature >= 0)
        require(request.sampling.topK > 0)
        require(request.sampling.topP.isFinite() && request.sampling.topP in 0f..1f)
        require(request.sampling.minP.isFinite() && request.sampling.minP in 0f..1f)
    }

    private fun requireReady() {
        check(!closed && modelHandle != 0L && contextHandle != 0L) { "model_not_ready" }
    }

    private fun releaseHandles() {
        val context = contextHandle
        if (context != 0L) {
            OperatorNative.freeContext(context)
            contextHandle = 0
        }
        if (modelHandle != 0L) {
            OperatorNative.freeModel(modelHandle)
            modelHandle = 0
        }
        sequenceTokens.clear()
    }

    private val KvType.ggmlType: Int
        get() = when (this) {
            KvType.F16 -> 1
            KvType.Q8_0 -> 8
            KvType.Q4_0 -> 2
        }

    private companion object {
        const val DEFAULT_BATCH = 512
        const val MAX_SEQUENCES = 16
        const val SOURCE_SEQUENCE = 0
        const val STATE_NONE = 0
        const val PARTIAL_ONLY = 1
        val PLANNER_TYPES = intArrayOf(0, 41)
        val ENCODER_TYPES = intArrayOf(0, 1, 8, 10, 12, 14)

        fun rate(tokens: Int, milliseconds: Long): Double =
            if (tokens == 0 || milliseconds <= 0) 0.0 else tokens * 1_000.0 / milliseconds
    }
}

private val EMPTY_LLM_STATS =
    LlmStats(0, 0, 0, 0, 0, 0.0, 0.0, LlmBackend.CPU, "")
