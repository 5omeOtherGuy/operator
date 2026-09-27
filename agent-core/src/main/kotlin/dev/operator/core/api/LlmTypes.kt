package dev.operator.core.api

/*
 * Frozen M1 API (`dev.operator.core.api`) — the payloads of the `:llm` AIDL surface, pure Kotlin.
 *
 * Design: FOUNDATION §2.3 (`:llm` AIDL surface), §4.2 (roles and backend), §4.5 (prefix reuse and
 * branch sequences), §4.8 (model delivery: sha256 is the model id), ADR-0002 (process layout),
 * ADR-0005 (integrity allowlist).
 *
 * `:agent-core` is pure JVM (§2.2) and cannot see `:llm-api`, which is an Android library, so these
 * types mirror the AIDL Parcelables of `llm-api/` field by field. The composition root (I1) maps one
 * to the other; keeping the names and meanings identical is what the mirroring is checked against.
 */

/** §4.4: the planner (Bonsai 8B/27B) and the decide encoder (CLM-8B, M2). The logprob backend reuses the planner's weights. */
enum class ModelRole { PLANNER, ENCODER }

/** §4.1: the CPU backend is the M1 default; OpenCL is the benchmark arm loaded as a runtime module. */
enum class LlmBackend { CPU, OPENCL }

/** KV cache element type (`llama_context_params.type_k`). */
enum class KvType { F16, Q8_0, Q4_0 }

/** §2.3 `load(ModelSpec{path, sha256, role, nCtx, kvType, backend, threads, useExtraBufts})`. */
data class ModelSpec(
    val path: String,
    /** sha256 of the file; it is the `modelId` in logs and decisions (§4.8). */
    val sha256: String,
    val role: ModelRole,
    val nCtx: Int,
    val kvType: KvType,
    val backend: LlmBackend,
    /** §4.7: 6 threads is the default, pinned through `llama_attach_threadpool`. */
    val threads: Int,
    /** §2.3: the upstream `use_extra_bufts` switch. */
    val useExtraBufts: Boolean,
)

/** §4.6: sampling parameters. Greedy for grammar-constrained calls; the vendor defaults (0.5/20/0.85) for free text. */
data class Sampling(
    val greedy: Boolean = true,
    val temperature: Float = 0.5f,
    val topK: Int = 20,
    val topP: Float = 0.85f,
    val minP: Float = 0.0f,
    val seed: Int = 0,
)

/** §2.3: the `req` of `generate`, `labelLogits` and `embedLast`, and the correlator `abort(req)` uses. */
data class GenerateRequest(
    val id: Long,
    val sampling: Sampling = Sampling(),
    /** §4.5: 0 is the task sequence, >0 a decide branch copied with `llama_memory_seq_cp`. */
    val sequenceId: Int = 0,
    /** §4.5: truncate at the longest common token prefix instead of re-prefilling. */
    val reusePrefix: Boolean = true,
    /** §4.6 `grammar_first=false`: sample unconstrained, fall back to the grammar pass only on rejection. */
    val grammarFirst: Boolean = false,
)

/** Why generation stopped. */
enum class StopReason { EOG, MAX_TOKENS, ABORTED, ERROR }

/** §2.3 `stats()` / `bench(pp, tg, threads)` and the §12 metrics. */
data class LlmStats(
    val nPrompt: Int,
    val nGen: Int,
    val tPrefillMs: Long,
    val tDecodeMs: Long,
    val loadMs: Long,
    val ppTps: Double,
    val tgTps: Double,
    val backend: LlmBackend,
    /** §4.1: which ggml CPU variant loaded from `nativeLibraryDir` (`R11`, `02-M-1`). */
    val cpuVariant: String,
)

/** §2.3 `generate` result. */
data class GenerationResult(
    val text: String,
    val tokenIds: List<Int>,
    val stopReason: StopReason,
    val stats: LlmStats,
)
