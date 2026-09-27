package dev.operator.llm

/**
 * AIDL payloads of the `:llm` surface (FOUNDATION §2.3), mirroring `dev.operator.core.api` field by
 * field; the composition root (I1) maps one to the other. Parcelables are written and read by hand:
 * the field order of `writeToParcel` and `createFromParcel` is identical per class.
 */

/** §4.4: the planner (Bonsai) and the decide encoder (CLM-8B, M2). */
enum class ModelRole { PLANNER, ENCODER }

/** §4.1: the CPU backend is the M1 default; OpenCL is the benchmark arm loaded as a runtime module. */
enum class LlmBackend { CPU, OPENCL }

/** KV cache element type (`llama_context_params.type_k`). */
enum class KvType { F16, Q8_0, Q4_0 }
