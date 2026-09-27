# 02 — On-device inference: backends, memory, model switching, caching

Lane 02 of the operator design research (branch `design/foundation`, 2026-09-27). This is a design note only. No code was built and nothing was run on the phone.
Tags: **VERIFIED [n]** means checked against numbered source n. **UNVERIFIED** means not yet checked, and each one names what would check it. **INFERENCE** means reasoning or arithmetic from verified facts.
Reference llama.cpp tree: the submodule in op13's draft, `third_party/llama.cpp` at `9588757` (2026-09-26) [1]. The source-code claims below were read from that tree.

## Scope (questions covered)

1. Which llama.cpp backends run on SM8750 (CPU, OpenCL/Adreno, Vulkan/Adreno, Hexagon)? For each: Q1_0/Q2_0 kernels, maturity, measured numbers, and what a GitHub Actions build needs.
2. PrismML Bonsai 8B and 27B: base model, shapes, GGUF files, when Q1_0/Q2_0 landed upstream, and whether the models are text-only.
3. Memory on 16 GB under Android's lmkd: mmap versus anonymous memory, KV cache sizes, CLM co-residency, and three budget cases.
4. Loading and switching models: two models in one process, keeping models resident versus swapping them, threads and core pinning, thermals.
5. Prompt and KV caching across agent steps, and the cost of grammar-constrained decoding.
6. JNI integration: upstream `examples/llama.android`, CMake flags, ABI, APK size, and model delivery.

Out of scope, owned by other lanes: the decide() semantics and CLM heads (03), the screen serialisation and agent loop (04), the action schema (05), provisioning (06), and CI and evaluation gates (07).

## Findings

### F1. Quant formats: what Q1_0 and Q2_0 are upstream

- `GGML_TYPE_Q1_0 = 41`, `GGML_TYPE_Q2_0 = 42` [1: ggml/include/ggml.h]. The Q1_0 block holds 128 weights in 18 bytes: one fp16 scale plus 16 bytes of sign bits, so 1.125 bpw. The Q2_0 block holds **64** weights in 18 bytes, so 2.25 bpw [1: ggml/src/ggml-common.h L180-192]. VERIFIED [1].
- Upstream timeline, from merged PRs [18]. VERIFIED [18]:
  - Q1_0 on CPU: #21273, merge `2e1f0a8`, 2026-04-06. This PR has NEON and generic kernels only; the author wrote that "our main focus was Metal/CUDA" [20].
  - Q1_0 on Vulkan: #21539 (`7b69125`, 2026-04-10). Q1_0 on OpenCL: #25160 (`fd1a057`, 2026-07-01, "initial q1_0 support", plus Adreno GEMM/GEMV).
  - **Q1_0 ARM repack (dotprod 4x4, i8mm 4x8): #23492 (`8034c1d`, 2026-09-21).**
  - Q2_0 on CPU: #24448 (`bec4772`, 2026-07-07). Q2_0 on Vulkan: #25430 (2026-07-17).
- **Minimum commit for this project is `8034c1d`.** Earlier commits run Bonsai 8B on the CPU but without the ARM repack. On a Snapdragon 7 Gen 3 phone with Bonsai-1.7B and 4 cores, #23492 measured pp128 going from 27.17 to 121.87 t/s (i8mm 4x8) and tg32 going from 20.80 to 33.61 t/s (dotprod 4x4 gave tg 35.96) [19]. The pinned submodule `9588757` is later, so it includes the repack. VERIFIED [18][19][1].
- **Q2_0 hazard.** PrismML's own "Q2_0" is group-128 at 2.125 bpw, which is not the upstream layout (group 64). The files `Ternary-Bonsai-*-Q2_0.gguf` and `-PQ2_0.gguf` need the PrismML fork. PrismML's docs say a `Q2_0` file on a stock build "loads silently and outputs gibberish". Only `*-Q2_0_g64.gguf` (Ternary-Bonsai-8B: 2,310,125,920 B) runs upstream [24][25]. Ternary Bonsai 2 (`PTQ1_0`/`PQ2_0`) is fork-only [25]. VERIFIED [24][25].

### F2. Bonsai models

| | Bonsai 8B | Bonsai 27B |
|---|---|---|
| Base | Qwen3-8B, dense GQA [21] | Qwen3.6-27B, hybrid attention, "architecture unchanged" [22] |
| Layers | 36 [21][27] | 64 = 16 × (3 Gated-DeltaNet + 1 full attention) [22][28] |
| hidden / heads / KV heads / head_dim | 4096 / 32 / 8 / 128 [27] | 5120 / 24 / 4 / 256; linear: 16 K-heads, 48 V-heads, dim 128, conv 4 [28] |
| Vocab | 151,936 [27] | 248,320 [28] |
| Context | 65,536 per card [21] (the Qwen3-8B config says 40,960 [27]) | 262,144 [22][28] |
| GGUF | `Bonsai-8B-Q1_0.gguf` 1,158,654,496 B, sha256 `284a335a…bd54` [23] | `Bonsai-27B-Q1_0.gguf` 3,803,452,480 B, sha256 `17ef842e…9aa0` [23] |
| Modality | text-only [21] | text + vision; mmproj Q8_0 629 MB, separate file [22][23] |
| Licence | Apache-2.0 [21] | Apache-2.0 [22] |

All cells are VERIFIED at the cited sources.
- The model cards on Hugging Face still say to use "the PrismML fork" for CUDA/Metal kernels [21][22]. PrismML's docs say 1-bit runs on upstream [25]. The upstream tree has Q1_0 in the CPU, OpenCL and Vulkan backends and also has `LLM_ARCH_QWEN35` [1], so Bonsai 27B should load upstream. VERIFIED for the source; INFERENCE that 27B loads cleanly. **UNVERIFIED** until someone loads `Bonsai-27B-Q1_0.gguf` with the pinned build and gets coherent output (M-12).
- Bonsai 27B peak memory per the vendor, measured with llama.cpp Q1_0 and an f16 KV cache: weights 3.79 GB; 5.2 GB at 4K context; 5.6 GB at 10K; 11.6 GB at 100K. About 1.3 GB of that is "activations and runtime buffers" [22]. VERIFIED [22]; these were measured on a laptop.
- Measured phone numbers:
  - Bonsai 8B on a **Samsung S25 Ultra** (Snapdragon 8 Elite for Galaxy), llama.cpp OpenCL: **tg128 19.6 t/s, pp512 30.4 t/s** [21]. The card doesn't say which build; the number predates the upstream OpenCL Q1_0 GEMM and the CPU repack.
  - Bonsai 27B: iPhone 17 Pro Max (MLX), about 11 t/s decode [22].
  - No Snapdragon number exists for 27B on llama.cpp. VERIFIED [21][22] as quoted.
- An 8B-on-Hexagon runtime does exist, but only as a third party's: "QHexRT" (RunAnywhere) uses a custom QNN op package and their pinned PrismML forks [34]. It is not upstream and not ggml-hexagon. VERIFIED [34].

### F3. CPU backend (arm64, Oryon)

- **Features.** Oryon implements ARMv8.7-A. The 2nd-generation cores (in the Snapdragon 8 Elite) are also ARMv8.7-A [36]. Oryon has **no SVE/SVE2**, only 128-bit NEON [35]. VERIFIED [35][36]; [35] describes gen-1 Oryon.
  - INFERENCE: v8.7-A has no SME, and FEAT_DotProd and FEAT_I8MM are mandatory from v8.4 and v8.6. So ggml should select the `android_armv8.6_1` variant (DOTPROD + FP16 + MATMUL_INT8) out of the Android `GGML_CPU_ALL_VARIANTS` set [1: ggml/src/CMakeLists.txt L534-540].
  - **UNVERIFIED** until the `/proc/cpuinfo` Features line (i8mm, asimddp, sve, sme) and ggml's backend log are read on the device (M-1).
- **Q1_0 path.** It uses the NEON `vec_dot_q1_0_q8_0`. Weights are repacked at load to `q1_0_4x8` when i8mm is present, or to `4x4` with dotprod only [1: ggml-cpu/repack.cpp L5127-5137]. VERIFIED [1].
- **Q2_0 path.** It has a NEON `vec_dot` only, **no repack** [1: arch/arm/quants.c L222ff; repack.cpp has no Q2_0]. VERIFIED [1]. INFERENCE: ternary files run noticeably slower per byte than Q1_0 on this CPU.
- **KleidiAI** (`GGML_CPU_KLEIDIAI`) accelerates only Q4_0, Q8_0, F16 and F32 [1: kleidiai.cpp L487-489, L665]. It does nothing for Bonsai. It does help the CLM encoder at Q8_0/Q4_0. CMake downloads KleidiAI v1.24.0 from GitHub at configure time [1: ggml-cpu/CMakeLists.txt L611-623], which CI allows. VERIFIED [1].
- **Repack and KleidiAI buffers are anonymous memory.** When mmap is on, a weight that stays in the plain CPU buffer points into the file mapping. A weight placed in a repack or KleidiAI "extra" buffer is copied with `ggml_backend_tensor_set` [1: src/llama-model-loader.cpp L1269-1277, L1655-1670]. `use_extra_bufts=false` turns the extra buffers off [1: include/llama.h L352]. VERIFIED [1]. Consequence (INFERENCE): a fast CPU load makes the weights unreclaimable anonymous RSS; the no-repack path keeps them as evictable page cache. See F6.
- **Core pinning.** ggml applies `cpumask` affinity and priority only in its own threadpool, which is the non-OpenMP path [1: ggml-cpu.c L3170-3306]. The upstream Android CI builds use `GGML_OPENMP=OFF` [10]. The upstream `llama.android` lib sets `GGML_OPENMP ON` [11], so it cannot pin cores. VERIFIED [1][10][11].
- **Other Snapdragon 8 Elite measurements.** Qwen2.5-Coder-7B Q4_K_M on the CPU with 6 threads: pp512 24.17, tg128 7.95. Qwen2.5-1.5B Q8_0 on OpenCL: pp512 ~560, tg128 ~31 [31]. The reporter found 4 threads on cores 0-3 best and saw "cross-cluster latency variance" at 6 or more threads [31]. VERIFIED [31]; these are Termux numbers, and the CPU pp figure looks as if repack was absent.

### F4. OpenCL backend (Adreno 830)

- It is Qualcomm-maintained, and its docs list Snapdragon 8 Elite / **Adreno 830 as verified**. Adreno 830 maps to `ADRENO_GPU_GEN::A8X` [6][1: ggml-opencl.cpp L298-303]. VERIFIED.
- **Q1_0 is supported** for MUL_MAT with f32 activations, with Q1_0 GEMV/GEMM kernels (`gemv/gemm_noshuffle_q1_0_f32.cl`, `mul_mv_q1_0_f32*.cl`) [1][6]. **Q2_0 is not supported.** It falls back to the CPU and splits the graph [1: supports_op L9049-9100; no q2_0 kernels]. VERIFIED [1].
- GATED_DELTA_NET (the Bonsai 27B linear layers) is supported for f32 with S_v ∈ {16, 32, 64, 128} [1: L9028-9035]. Qwen3.6's value head dim is 128 [28]. INFERENCE: the 27B linear layers can stay on the GPU.
- Weights are converted into GPU buffers at load (`set_tensor` conversion for Q1_0 [1: L9891, L11561]). INFERENCE: on Adreno this is unified memory held by the kgsl driver. It is not file-backed and not reclaimable, so it costs the full weight size in RAM.
- The Termux report on Adreno 830 says "q8_0 KV crashes" with GPU offload and that flash attention was disabled. An f16 KV was needed [31]. VERIFIED [31] as a report. **UNVERIFIED** on our build (M-5).
- **Runtime linking from an app:** apps targeting API 31+ can load a vendor library such as `libOpenCL.so` only if they declare `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` **and** the vendor lists the library as public [38]. VERIFIED [38]. Whether OxygenOS 16 lists `libOpenCL.so` in `/vendor/etc/public.libraries.txt` is **UNVERIFIED** (M-2).
- **Build:** `find_package(OpenCL REQUIRED)` needs Khronos headers plus a `libOpenCL.so` to link against. The docs build the Khronos ICD loader with the NDK [6][1: ggml-opencl/CMakeLists.txt]. Alternatively, the `ghcr.io/snapdragon-toolchain/arm64-android:v0.7` container ships an NDK, an OpenCL SDK and the Hexagon SDK, and upstream CI runs it on `ubuntu-24.04` [9][10]. The kernels are embedded in the library by default (`GGML_OPENCL_EMBED_KERNELS=ON`) and compiled on first run with an on-disk binary cache (`GGML_OPENCL_KERNEL_CACHE_DIR`) [6]. VERIFIED [6][9][10].

### F5. Vulkan and Hexagon

- **Vulkan** has kernels for both Q1_0 and Q2_0 (dequant, mul_mat_vec, mmq) and an Adreno-specific architecture path [1: ggml-vulkan.cpp L130-145, L2178, L2633, L2929-2959]. VERIFIED [1].
  - Maturity on Adreno is poor. The latest comment in the long-running Android Vulkan thread, from February 2026, reports native Adreno 750 drivers crashing and about 7 t/s on Qwen3-8B Q4_K_M even with Turnip [33]. VERIFIED [33].
  - The build needs `glslc` and SPIRV-Headers [1: ggml-vulkan/CMakeLists.txt L9-14]. Whether the NDK's headers and glslc are enough is **UNVERIFIED** (a CI spike would tell).
- **Hexagon (ggml-hexagon)** is upstream, labelled "experimental" in its own log line, and built for HTP v73/v75/v79/v81 [9]. Supported weight types are Q4_0, Q4_1, Q8_0, IQ4_NL, MXFP4, Q4_K, Q5_K and Q6_K, with **no Q1_0 or Q2_0** [1: ggml-hexagon.cpp L267-270]. VERIFIED [1][9].
  - It needs the Hexagon SDK (`HEXAGON_SDK_ROOT`, SDK 6.6.0.0 in the container) and runs in an unsigned PD [1: ggml-hexagon/CMakeLists.txt; ggml-hexagon.cpp L4093][9]. VERIFIED.
  - The SDK is repackaged as "Community Edition … for GitHub Actions" [45]. The licence text was not found. **UNVERIFIED**: someone has to read the Qualcomm Software Center licence.
  - Measured on Snapdragon 8 Elite: Qwen3-4B Q4_0 at 12 t/s decode on Hexagon versus 26 t/s with Qualcomm's own HTP engine [32]. The upstream README's Llama-3.2-1B Q4_0 run gives pp128 169, tg64 51.5 (device unstated) [9]. VERIFIED [32][9].
  - From an app it would also need `<uses-native-library libcdsprpc.so>` [38] (INFERENCE by analogy), and the DSP skel must be loadable from the APK (UNVERIFIED).

### F6. Memory on 16 GB under lmkd

- The kernel sees 15.47 GB [43]. lmkd is PSI-driven. It watches file-backed refaults (`ro.lmk.thrashing_limit`, 100 % on high-performance devices) and swap exhaustion (`ro.lmk.swap_free_low_percentage`, 20 %), and treats swappable and non-swappable anonymous memory separately [37]. VERIFIED [37]. INFERENCE from that: clean mmap'd GGUF pages are reclaimable page cache and cost only refaults. Repacked or GPU weights are anonymous or driver memory, so they count toward kill pressure.
- **KV cache per token.** Arithmetic from the configs [27][28]; q8_0 = 34 B per 32 elements.
  - Qwen3-8B, which covers both Bonsai 8B and the CLM encoder: 36 × 8 × 128 × 2 = 73,728 elements, so **144 KiB f16** or **76.5 KiB q8_0**.
  - Bonsai 27B: only 16 full-attention layers × 4 × 256 × 2 = 32,768 elements, so **64 KiB f16** or **34 KiB q8_0**. On top of that comes a fixed recurrent state per sequence of 48 × (128 × 6144) f32 = **144 MiB**, plus about 6 MiB of conv state. The formula is at [1: src/llama-hparams.cpp n_embd_s = ssm_d_state × ssm_d_inner] and the state is f32 per [1: src/llama-model.cpp L2670-2671]. INFERENCE; this is consistent with the vendor's 100K-context peak [22].

| KV cache (MiB) | 4k f16 | 4k q8_0 | 8k f16 | 8k q8_0 | 16k f16 | 16k q8_0 |
|---|---|---|---|---|---|---|
| Bonsai 8B / Qwen3-8B | 576 | 306 | 1152 | 612 | 2304 | 1224 |
| Bonsai 27B (+150 recurrent) | 256+150 | 136+150 | 512+150 | 272+150 | 1024+150 | 544+150 |

INFERENCE (arithmetic). CLM runs at most 2048 tokens under its vLLM config [30], so its KV is **288 MiB f16** at n_ctx 2048.

- **CLM encoder files** (czl, third party, Apache-2.0) [29]:

| File | Size (B) | Accuracy note from [29] |
|---|---|---|
| `Qwen3-8B-Q8_0-outq2.gguf` | 8,252,495,488 | Q8_0 is "recommended", top-1 97.86 % against bf16 |
| `Qwen3-8B-Q6_K-outq2.gguf` | 6,570,317,440 | Q5_K_M and Q6_K lose 2-3 points |
| `Qwen3-8B-Q4_K_M-outq2.gguf` | 5,032,646,272 | Q4_K_M is "not recommended for ranking", planner −6.63 points |

  `outq2` means `output.weight` is quantised to q2_K. The LM head is unused under last-token pooling, so INFERENCE: its pages are never touched through mmap. VERIFIED [29].
- **Compute buffers.** The vendor puts Bonsai 27B at about 1.3 GB [22]. For Bonsai 8B at ubatch 512 the size is **UNVERIFIED**; read it from `llama_memory_breakdown_print` (M-5). I use 0.5 GB as a placeholder.
- **What competes for RAM:** Android, SystemUI, GMS, OnePlus services, the app being operated (Chrome and similar can take 0.5-1.5 GB, UNVERIFIED) and zram. The device's MemAvailable figure at idle with typical apps is **UNVERIFIED** (M-3). I plan against an **~8 GB usable** placeholder.

**Budget table.** Steady state, GB. INFERENCE from the rows above; placeholders marked *.

| Component | (a) Bonsai 8B + CLM | (b) Bonsai 27B + CLM | (c1) Bonsai 8B only | (c2) Bonsai 27B only |
|---|---|---|---|---|
| Planner weights | 1.16 anon (repack) | 3.80 anon | 1.16 | 3.80 |
| Planner KV 8k q8_0 | 0.64 | 0.28 + 0.15 rec | 0.64 | 0.43 |
| Planner compute | 0.5* | 1.0* (vendor ~1.3 incl. KV) | 0.5* | 1.0* |
| Prefix checkpoints / state | 0.1 | 0.3 (2 × 150 MiB) | 0.1 | 0.3 |
| CLM Q8_0-outq2 weights | 8.25 **file-backed** (evictable) or anon if repacked | 8.25 file-backed | - | - |
| CLM KV 2k f16 + compute | 0.3 + 0.4* | 0.3 + 0.4* | - | - |
| **Anonymous total** | **~2.4 (+8.25 if CLM repacked)** | **~4.3 (+8.25)** | **~2.4** | **~5.5** |
| **Touched total incl. page cache** | **~11.4** | **~14.6** | ~2.4 | ~5.5 |

Reading the table (INFERENCE):
- (c1) is comfortable, and (c2) fits (the vendor measured 5.2 GB at 4K [22]).
- (a) fits only if the CLM weights stay file-backed and evictable, or if CLM drops to Q6_K (6.57) or Q4_K_M (5.03), which has an accuracy cost [29].
- (b) does not fit co-resident at Q8_0 on an ~8 GB-available phone; even at Q4_K_M it is about 9.3 GB touched.
- If CLM runs on OpenCL, its weights become non-reclaimable GPU memory (F4). Then only Q4_K_M or Q6_K are candidates, and only for case (a).

### F7. Loading, switching, threads, thermals

- **Two models in one process are supported.** The llama.cpp API is handle-based, and speculative decoding keeps a draft `llama_model` next to the target in one process [1: common/speculative.cpp L69-71, L465]. VERIFIED [1].
  - The upstream `llama.android` lib uses process-global singletons (`g_model`, `g_context`, `g_batch`, `g_sampler`) [11: ai_chat.cpp L36-40], so it can hold only one model. VERIFIED [11].
- **Load modes:** `LLAMA_LOAD_MODE_{NONE, MMAP, MLOCK, MMAP_MLOCK, DIRECT_IO}` [1: include/llama.h L206-212]. VERIFIED.
  - The per-app mlock limit on Android is **UNVERIFIED**; read `/proc/<pid>/limits` (M-4). Do not rely on mlock.
  - Load and repack times are **UNVERIFIED** (M-7). INFERENCE: a cold 1.16 GB read from UFS 4.0 plus the repack should take seconds, not minutes. Reading 8.25 GB for CLM from cold page cache is the expensive case.
- **Threads.** The upstream lib clamps to 2-4 threads [11: ai_chat.cpp L27-29]. The Termux report found 4 threads on cores 0-3 best [31].
  - On OnePlus 13 / OxygenOS 16, the `cpufreq_bouncing` module caps frequency after about 50 ms of sustained load: prime cores CPU6-7 to 2.44 GHz and cores CPU0-5 to 2.40 GHz. `oplus_bsp_task_overload` clamps `uclamp.max` for busy app threads, and prime-core residency was 0 % in benchmarks. The report says sustained multithreaded compute is "not affected", because a shared power budget limits both clusters anyway [41]. VERIFIED [41], OxygenOS 16.0.9.401; our device runs 16.0.10.501 [43].
  - INFERENCE: pin to CPU0-5, don't count on the prime cores, and sweep thread counts.
  - The cpuset a foreground service gets on this ROM is **UNVERIFIED** (M-4).
- **Thermals.** OnePlus 13 CPU throttling test 60 % and 3DMark stability 63 %, per a search snippet from GSMArena that I did not fetch: **UNVERIFIED** [42]. Sustained LLM throughput is **UNVERIFIED** (M-8).
  - The NDK `APerformanceHint` sessions (report actual versus target duration per cycle) are open to any app [40]. The thermal headroom API is documented separately [40]. VERIFIED [40] for the hint API.

### F8. Prompt/KV caching and constrained decoding

- **Pure-attention models (Bonsai 8B, CLM).** `llama_memory_seq_rm(mem, seq, p0, -1)` truncates the KV cache at the common prefix, and decoding continues from there. Whole or partial state save and restore goes through `llama_state_seq_get/set_data[_ext]` and `llama_state_seq_save/load_file` [1: include/llama.h L761, L855-950]. VERIFIED [1].
- **Hybrid model (Bonsai 27B).** Recurrent state "can't be partially erased", so `seq_rm` returns false. Rollback works only within `n_rs_seq` per-token snapshots, an experimental option [1: src/llama-memory-recurrent.cpp L161-200; include/llama.h L370]. VERIFIED [1]. So prefix reuse for 27B needs **checkpoints**: snapshot with `LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY` at the end of the stable prefix and restore it each step. `llama-server` does exactly this, with 32 checkpoints per slot by default [1: common/common.h L630; tools/server/server-context.cpp L1365]. VERIFIED [1]. Each checkpoint costs about 150 MiB for 27B (INFERENCE from F6).
- **Grammar.** GBNF (or lazy grammar with trigger patterns, or llguidance) comes via `common_sampler`. JSON schema converts to GBNF with `common/json-schema-to-grammar.cpp` [1]. With `grammar_first=false`, the sampler draws unconstrained first, checks only the chosen token against the grammar, and runs the full grammar pass over the candidates only if that token is rejected [1: common/sampling.cpp L594-666]. VERIFIED [1]. The per-token overhead with a 152k vocab on Oryon is **UNVERIFIED** (M-10).
- Step-latency model (INFERENCE, placeholder rates): a step costs `(new prompt tokens)/pp + (output tokens)/tg`. With the S25 OpenCL numbers [21] (pp 30, tg 19.6), re-prefilling a 2,000-token prompt every step would take about 67 s, and 60 output tokens about 3 s. **Prefill dominates.** Prefix reuse plus a compact screen matter more than decode speed.

### F9. JNI integration and delivery

- **Upstream `examples/llama.android/lib`** [11]:
  - Kotlin namespace `com.arm.aichat`; `InferenceEngine` interface: `loadModel`, `setSystemPrompt`, `sendUserPrompt: Flow<String>`, `bench`.
  - C++ `ai_chat.cpp`; compileSdk 36, **minSdk 33**, NDK `29.0.13113456`, ABIs arm64-v8a and x86_64.
  - CMake flags: `BUILD_SHARED_LIBS=ON`, `GGML_BACKEND_DL=ON`, `GGML_CPU_ALL_VARIANTS=ON`, `GGML_NATIVE=OFF`, `GGML_LLAMAFILE=OFF`, `LLAMA_BUILD_COMMON=ON`, `LLAMA_OPENSSL=OFF`, and on arm64 `GGML_CPU_KLEIDIAI=ON` and `GGML_OPENMP=ON`.
  - Backends load at runtime via `ggml_backend_load_all_from_path(nativeLibraryDir)`.
  - n_ctx defaults to 8192 and n_batch to 512.
  - It has **no grammar, embeddings, state save or multi-model support**. VERIFIED [11].
- op13's draft wires this lib in as Gradle module `:llama` straight from the submodule, targets arm64-v8a with minSdk 33 and targetSdk 36, and builds in CI with Gradle 8.14.3 on `ubuntu-24.04` [44]. VERIFIED [44].
- **16 KB pages:** NDK r28+ aligns to 16 KB by default, and AGP ≥ 8.5.1 is needed for aligned uncompressed libs [39]. VERIFIED [39]. The pinned NDK r29 satisfies this.
- **APK size** with `GGML_CPU_ALL_VARIANTS` (7 Android CPU variant `.so` files) plus OpenCL is **UNVERIFIED** (M-13, from the CI artifact).
- **Checksums:** Hugging Face exposes each file's LFS sha256 through `/api/models/<repo>/tree/main` [23]. VERIFIED; the values are in the F2 table.

## Options

| Option | What it gives | Cost / risk | Evidence |
|---|---|---|---|
| CPU, ALL_VARIANTS + Q1_0 repack, OpenMP off | Works everywhere; Q1_0 i8mm repack; core pinning; CLM gets KleidiAI | Repacked weights are anonymous; CPU contends with the app being operated; OnePlus frequency caps | [1][10][19][41] |
| OpenCL/Adreno (DL module, optional) | Q1_0 GEMM/GEMV, probably much faster prefill; frees CPU | Weights in non-reclaimable GPU memory; needs `libOpenCL.so` public on this ROM; q8_0 KV/FA issues reported; **no Q2_0** | [1][6][21][31][38] |
| Vulkan/Adreno | Only GPU path with Q2_0 | Poor Adreno maturity, extra build dependencies | [1][33] |
| Hexagon (ggml-hexagon) | NPU offload for Q4_0/Q8_0 (possibly CLM) | No Q1_0/Q2_0; experimental; about half of Qualcomm's own speed; Hexagon SDK licence; FastRPC from an app unproven | [1][9][32][45] |
| QHexRT (RunAnywhere) | 1-bit Bonsai on the NPU | Third-party runtime and forks; not llama.cpp upstream | [34] |
| PrismML fork (`prism`) | Ternary Bonsai 2, PrismML Q2_0 g128 | Fork drift; its extra kernels target CUDA/Metal, not Android | [24][25] |
| CLM Q8_0-outq2, mmap, no extra bufts | Best CLM accuracy; weights evictable page cache | Slower CPU matmul; refault stalls when evicted | [1][29][37] |
| CLM Q6_K / Q4_K_M, repacked | Smaller and faster | −2-3 or −6.6 points ranking accuracy | [29] |
| Separate `:llm` process | Native crash or LMK kill spares the a11y service | IPC (binder) cost; two process lifecycles | INFERENCE |

## Recommendation

### Backend plan

- **M1 default: the CPU backend, upstream llama.cpp at `9588757` or newer (never older than `8034c1d`).**
  - Build as shared libraries with `GGML_BACKEND_DL=ON`, `GGML_CPU_ALL_VARIANTS=ON`, `GGML_NATIVE=OFF`, **`GGML_OPENMP=OFF`** (needed for cpumask pinning, and it matches upstream Android CI), `GGML_CPU_KLEIDIAI=ON` and `GGML_LLAMAFILE=OFF`.
  - The only ABI is arm64-v8a.
  - Bonsai 8B Q1_0 goes through the i8mm 4x8 repack.
  - Rationale: this is the only backend certain to work in an app without depending on the vendor-library namespace, and its Q1_0 kernels were tuned on phones [19].
- **M1 benchmark arm: build `GGML_OPENCL=ON`** (Adreno kernels on, embedded kernels, a kernel cache directory under the app's `cacheDir`) as a DL module in the same APK. Declare `libOpenCL.so` with `required=false`. Select the backend at runtime, per model role.
  - Promote OpenCL to the planner default only if all of these hold: (i) pp512 is at least 2x the CPU's, (ii) tg is no worse, (iii) output matches (greedy decoding identical for 64 tokens), and (iv) no crash in a 30-minute loop.
- **Not in M1:**
  - Vulkan: revisit only if ternary Q2_0_g64 becomes the planner.
  - Hexagon: revisit in M3, and only for CLM (Q8_0/Q4_0) if CLM prefill on the CPU and GPU misses lane 03's latency budget. That also needs the owner to accept the Hexagon SDK licence.
  - The PrismML fork: not used.
- **Ternary:** only `*-Q2_0_g64.gguf` is allowed with upstream. Add a load-time guard that rejects any GGUF whose tensor types are not in an allow-list, and check its sha256 against the pinned list.

### Memory plan

- **Planner.** Bonsai 8B stays resident while an operator session runs: repacked, **8k context, q8_0 KV on the CPU** (f16 on OpenCL until q8_0 is proven), one sequence, n_batch/n_ubatch 512.
- **CLM (decide).** Default: `Qwen3-8B-Q8_0-outq2.gguf`, loaded with **mmap and `use_extra_bufts=false`** so its weights stay reclaimable page cache. Give it its own context with n_ctx 2048, f16 KV, `embeddings=true` and `pooling=LAST`, created on the first decide() of a session and freed after an idle timeout.
  - If M-6 shows unacceptable decide latency or refault thrash, switch to `Q6_K-outq2`, repacked. `Q4_K_M` only with lane 03's sign-off on the accuracy loss.
- **Bonsai 27B** is an M2 experiment in "27B-only" mode (case c2). CLM is not co-resident, and lane 03 needs a decide() fallback for that mode.
- **Process and priority:**
  - Run inference in a **foreground service in a separate process** (`android:process=":llm"`). An LMK kill or a native crash then loses only the model, never the accessibility service. The exact FGS type is lane 01/06's call.
  - The device owner (lane 06) should exempt the app from battery optimisation.
  - No mlock.
  - Watch `onTrimMemory` and PSI. At `TRIM_MEMORY_RUNNING_CRITICAL`, drop the CLM context first, then the KV checkpoints.
- **Decided now:** the component ordering and the file choices above. **Deferred to M-3 and M-6:** the usable-RAM number and the final CLM quant.

### Caching plan

- **Prompt layout, most stable first:**
  `[system + policy + action schema/tools]` → `[task goal]` → `[append-only step history]` → `[current screen]` → `[generation cue]`.
  - Lane 04 must keep history append-only and put the screen last.
- **Bonsai 8B.** Keep the KV cache of sequence 0 across steps. At each step, compute the longest common token prefix with the previous prompt, call `llama_memory_seq_rm(0, n_common, -1)`, and decode only the tail. Save the static prefix state to a file keyed by (model sha256, prompt-version hash) with `llama_state_seq_save_file`, so a restarted `:llm` process skips re-prefilling it.
- **Bonsai 27B.** Checkpoint with `PARTIAL_ONLY` at the end of the static prefix and after each history append, keeping at most 4 (about 150 MiB each). Restore the latest one and decode the delta.
- **CLM.** Cache the KV of the state prefix the same way. Cache action embeddings by exact text hash for the whole session; the model card says reuse gives its "13×" speed-up [30].
- **Decoding.**
  - GBNF generated from lane 05's JSON schema; `grammar_first=false` (the resampling path).
  - A small, enumerated grammar: action names as literals and element references as integers.
  - Greedy or low-temperature sampling.
  - The reasoning ("thinking") mode is off for per-step actions unless lane 04 measures a benefit.

### Threads

- The default is 6 threads pinned to CPU0-5 with `strict_cpu`, and 4 threads when OpenCL runs the planner. Set these with `llama_attach_threadpool`, with separate threadpools for batch (prefill) and single-token decode.
- The final numbers come from the M-5 sweep.
- Report each step's work to an `APerformanceHint` session. Throttle the loop (lower thread count, pause) when thermal headroom exceeds 0.9 (M-8 checks this).

### JNI

- **Do not ship the upstream `lib` module.** It has global singletons, only 2-4 threads, OpenMP on, and no grammar, embeddings or state.
- Write an own `:llm` Gradle module containing:
  - a small C++ JNI layer over `llama.h` and `common` (sampling, json-schema-to-grammar, chat templates);
  - handle-based calls: `loadModel(path, loadMode, extraBufts, devices)`, `newContext(model, nCtx, kvType, embeddings, pooling, threads)`, `prefill(ctx, tokens, reuse=true)`, `generate(ctx, grammar, maxTokens, stop)`, `embed(ctx, texts)`, `stateSave/Restore/Checkpoint`, `bench(pp, tg)`, `free*`;
  - a Kotlin API with one single-thread dispatcher per model.
- Reuse the upstream module's CMake pattern (`add_subdirectory(llama.cpp)`, `ggml_backend_load_all_from_path(nativeLibraryDir)`) with the flags above. Keep NDK r29 and AGP ≥ 8.5.1.
- op13's `:llama` wiring is acceptable for a first benchmark spike only.
- **Model delivery.**
  - Store models in app-specific external storage, `getExternalFilesDir("models")`. That location can be written with `adb push` during development and survives app updates. Whether adb can write `Android/data/<pkg>` on OxygenOS 16 is **UNVERIFIED** (M-2).
  - For re-provisioning without adb, import through the Storage Access Framework (from Downloads).
  - Always verify sha256 against a list pinned in the build ([23][29] values).
  - Default: **no INTERNET permission in the operator app** (owner question Q1).

## Interfaces this lane assumes from other lanes

- **01 architecture:**
  - Inference lives in a separate `:llm` process behind a binder or AIDL interface (`plan(prompt parts)`, `decide(state, candidates)`, `status()`), and that process runs a foreground service.
  - The model-role switch (8B, 27B, CLM on or off) is a runtime setting that 01 exposes to the owner.
  - One agent session at a time.
- **03 decide()/CLM:**
  - CLM is the Qwen3-8B encoder in GGUF, last-token pooled, at most 2048 tokens. This lane supplies `embed(texts) -> float[4096]` with prefix KV reuse and the action-embedding cache.
  - 03 owns the heads (4096→512 MLPs, cosine), the quant accuracy threshold, and a fallback decide() for when CLM is not resident (27B mode or memory pressure).
- **04 screen and agent loop:**
  - Screen serialisation target of ≤ 1,500 tokens per step (INFERENCE from F8).
  - Deterministic element ordering and ids, so unchanged regions tokenise identically.
  - Append-only history and the prompt layout above.
  - Thinking off by default.
- **05 actions:** a JSON schema for the action union, from which this lane builds GBNF. 05 re-validates the parsed output; this lane never executes anything.
- **06 access and provisioning:** the device owner exempts the app from battery optimisation and background restrictions; nothing else is needed from 06.
- **07 evaluation and CI:**
  - The CI job for NDK r29, the OpenCL headers and ICD stub (or the snapdragon-toolchain container), and the APK-size report.
  - An in-app benchmark screen or intent that runs `bench()` and writes JSON results to the app's external files directory, so another session can collect them.
  - Measurements M-1 to M-14 enter the M1 exit criteria.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| CPU prefill too slow for per-step prompts (seconds to tens of seconds) | High | Agent unusably slow | Prefix reuse, compact screen, OpenCL benchmark arm (M-5, M-9) |
| `libOpenCL.so` not public on OxygenOS 16 | Low-Med | No GPU path from the app | Declare `uses-native-library`; check M-2; CPU default |
| OpenCL q8_0 KV or FA crashes; Q1_0 GEMM numerically wrong | Med | Wrong actions | Greedy-equivalence test against CPU, f16 KV on GPU |
| CLM Q8_0 + Bonsai + operated app exceed RAM → lmkd kills `:llm` or the operated app | High | Lost session | File-backed CLM, separate process, trim handling, Q6_K fallback |
| Wrong GGUF variant (PrismML Q2_0 g128) → silent gibberish | Med | Nonsense plans | Type allow-list and sha256 pin at load |
| OnePlus frequency caps (CFB, task_overload) and thermals halve throughput in long sessions | High | Slow and variable latency | Measure (M-8), APerformanceHint, pacing, fewer threads |
| Bonsai 27B hybrid needs checkpoints; `n_rs_seq` is experimental | Med | 27B mode slow or buggy | Keep 27B at M2; use the server's checkpoint pattern |
| Upstream API churn (llama.cpp moves daily) | High | Build breaks on bump | Pin the submodule SHA; bump deliberately with the bench suite |
| Hexagon path pulled in prematurely | Low | Licence and effort sink, no Q1_0 | Deferred to M3, CLM-only, owner licence decision |

## Owner-only questions

1. **May the operator app have INTERNET permission?**
   - (a) No, and models arrive by adb push or SAF import. This is a verifiable "nothing leaves the phone".
   - (b) Yes, for one-time Hugging Face model downloads only.
   - **Default: (a).**
2. **CLM accuracy versus memory, if M-3/M-6 show Q8_0 doesn't coexist:**
   - (a) Accept Q6_K (−2-3 points ranking).
   - (b) Accept Q4_K_M (−6.6 planner points).
   - (c) Keep Q8_0 file-backed and accept slower first decisions after eviction.
   - **Default: (c), then (a).**
3. **Is 27B mode allowed to drop CLM** (decide() falls back to the planner), or must CLM always be available?
   - **Default: allowed, M2 experiment only.**
4. **Hexagon SDK licence** (Qualcomm terms) if we ever build ggml-hexagon:
   - (a) Never.
   - (b) Revisit at M3.
   - **Default: (b).** Nothing is needed now.

## On-device measurements needed (for M1/M2)

- **M-1:** `/proc/cpuinfo` Features (asimddp, i8mm, bf16, sve, sme) and the ggml CPU variant chosen (log line).
- **M-2:**
  - `/vendor/etc/public.libraries.txt` and `/system/etc/public.libraries.txt`: are `libOpenCL.so` and `libcdsprpc.so` listed?
  - Can the app `dlopen` them with `uses-native-library`?
  - Can adb write `/sdcard/Android/data/<pkg>/files`?
- **M-3:**
  - `/proc/meminfo` MemAvailable and `dumpsys meminfo`, (i) idle and (ii) with Chrome, Maps and Messages open.
  - zram and swap size.
  - `getprop | grep ro.lmk`.
- **M-4:**
  - `oom_score_adj` and `/proc/<pid>/limits` (memlock) of the `:llm` foreground service.
  - `/dev/cpuset/{top-app,foreground,background}/cpus`.
- **M-5:**
  - Bonsai 8B Q1_0 pp512, pp2048 and tg128. CPU with threads {2, 4, 6, 8} and masks {0-5, 0-3, 0-7}; OpenCL with f16 and q8_0 KV and FA on and off.
  - Memory breakdown (weights, KV, compute).
  - Greedy output equivalence between CPU and OpenCL.
- **M-6:**
  - CLM embed latency for 512, 1024 and 2048-token states and 20-token actions, at Q8_0-outq2 (mmap, no repack), Q8_0 repacked, Q6_K and Q4_K_M, on CPU and OpenCL.
  - PSS/RSS and page-cache residency.
- **M-7:** cold load time (after dropping caches via reboot) and warm load time for each model; repack time; OpenCL first-run kernel compile and cached-run time.
- **M-8:** a 15-minute sustained loop (pp1024 + tg64 per step) with t/s over time, `AThermal` headroom, CPU frequencies and skin temperature.
- **M-9:** step latency with and without prefix reuse; state-file size and save/restore time.
- **M-10:** grammar overhead per token (JSON-schema GBNF against unconstrained).
- **M-11:** co-residency of both models for 30 minutes of agent-like alternation, with lmkd kills in logcat and the operated app's survival.
- **M-12:** Bonsai 27B Q1_0 on the pinned build. Load, coherence check, pp and tg on CPU and OpenCL, peak RSS at 4k and 8k, checkpoint size and time.
- **M-13:** APK size and native-library list from the CI artifact.
- **M-14 (optional):** the Hexagon arch version reported by the device, for the record.

## Sources (all accessed 2026-09-27)

1. llama.cpp source at `9588757` (2026-09-26): `/home/phaseonebig/projects/operator/third_party/llama.cpp`. Files cited: `ggml/include/ggml.h`, `ggml/src/ggml-common.h`, `ggml/src/ggml-cpu/{arch/arm/quants.c, arch/arm/repack.cpp, repack.cpp, ggml-cpu.c, ggml-cpu.cpp, kleidiai/kleidiai.cpp, CMakeLists.txt}`, `ggml/src/CMakeLists.txt`, `ggml/src/ggml-opencl/{ggml-opencl.cpp, CMakeLists.txt}`, `ggml/src/ggml-vulkan/{ggml-vulkan.cpp, CMakeLists.txt}`, `ggml/src/ggml-hexagon/{ggml-hexagon.cpp, CMakeLists.txt}`, `include/llama.h`, `src/{llama-model-loader.cpp, llama-memory-recurrent.cpp, llama-hparams.cpp, llama-model.cpp, llama-arch.h}`, `common/{sampling.cpp, common.h, speculative.cpp, json-schema-to-grammar.cpp}`, `tools/server/server-context.cpp`.
   (Numbers 2-5, 7-8 and 12-17 are unused; those source files are cited as [1: path].)
6. `/home/phaseonebig/projects/operator/third_party/llama.cpp/docs/backend/OPENCL.md`; also https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md
9. `/home/phaseonebig/projects/operator/third_party/llama.cpp/docs/backend/snapdragon/README.md`
10. `/home/phaseonebig/projects/operator/third_party/llama.cpp/.github/workflows/{build-android.yml, build-and-test-snapdragon.yml}`
11. `/home/phaseonebig/projects/operator/third_party/llama.cpp/examples/llama.android/lib/` (`build.gradle.kts`, `src/main/cpp/CMakeLists.txt`, `src/main/cpp/ai_chat.cpp`, `src/main/java/com/arm/aichat/InferenceEngine.kt`)
18. https://api.github.com/search/issues?q=repo:ggml-org/llama.cpp+is:pr+is:merged+q1_0 ; https://api.github.com/repos/ggml-org/llama.cpp/pulls/{21273,24448,25160,23492,21539} ; Q2_0 PR search (#25419, #25430, #25707)
19. https://github.com/ggml-org/llama.cpp/pull/23492
20. https://github.com/ggml-org/llama.cpp/pull/21273
21. https://huggingface.co/prism-ml/Bonsai-8B-gguf (card and README.md)
22. https://huggingface.co/prism-ml/Bonsai-27B-gguf (card; raw README.md)
23. https://huggingface.co/api/models/prism-ml/Bonsai-8B-gguf/tree/main ; https://huggingface.co/api/models/prism-ml/Bonsai-27B-gguf/tree/main
24. https://huggingface.co/prism-ml/Ternary-Bonsai-8B-gguf (README; tree API); https://huggingface.co/api/models/prism-ml/Ternary-Bonsai-27B-gguf/tree/main
25. https://docs.prismml.com/run/llamacpp
26. https://docs.prismml.com/download/models
27. https://huggingface.co/Qwen/Qwen3-8B/raw/main/config.json
28. https://huggingface.co/Qwen/Qwen3.6-27B/raw/main/config.json
29. https://huggingface.co/czl/CLM-v0.1-8B-GGUF ; https://huggingface.co/api/models/czl/CLM-v0.1-8B-GGUF/tree/main
30. https://huggingface.co/Contrastive-LM/CLM-v0.1-8B
31. https://github.com/ggml-org/llama.cpp/discussions/23736
32. https://github.com/ggml-org/llama.cpp/discussions/21702
33. https://github.com/ggml-org/llama.cpp/discussions/9464
34. https://www.runanywhere.ai/blog/bonsai-27b-1-bit-models-on-phone
35. https://www.hwcooling.net/en/oryon-arm-core-in-snapdragon-x-cpus-architecture-analysis/
36. https://en.wikipedia.org/wiki/Oryon
37. https://source.android.com/docs/core/perf/lmkd
38. https://developer.android.com/guide/topics/manifest/uses-native-library-element
39. https://developer.android.com/guide/practices/page-sizes
40. https://developer.android.com/ndk/reference/group/a-performance-hint
41. https://github.com/wyl2607/oneplus13-performance-investigation
42. https://www.gsmarena.com/oneplus_13-review-2777p4.php (search-result snippet only, not fetched)
43. `/home/phaseonebig/op13/REPORT.md`
44. `/home/phaseonebig/projects/operator/{app/build.gradle.kts, settings.gradle.kts, .github/workflows/build.yml, .gitmodules}` (op13's uncommitted draft)
45. https://github.com/snapdragon-toolchain/hexagon-sdk ; https://github.com/snapdragon-toolchain
46. https://prismml.com/news/bonsai-27b
