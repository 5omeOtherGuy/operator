# ADR-0005: Inference runtime: upstream llama.cpp CPU first, on the upstream `llama.android` lib (extended), no INTERNET permission

- **Status:** Proposed. Decision 4 (JNI base) is **pending the owner (OQ-21)**; the default below applies until the answer.
- **Date:** 2026-09-27 (revised after review findings R1, R11, R21, R24, R25)
- **Design reference:** FOUNDATION §4.1, §4.2, §4.8 (conflicts C2, C12)

## Context

**Owner decision** (quoted from the design brief): "Kotlin. Native inference = llama.cpp via JNI (upstream examples/llama.android `lib` module; Q1_0/Q2_0 are upstream)." op13's draft already includes that module in place from the submodule as `:llama` [V12].

**Upstream support for the formats:**
- Q1_0 and Q2_0 are upstream.
- The Q1_0 ARM repack (`8034c1d`, 2026-09-21) raised pp128 from 27.17 to 121.87 t/s on a Snapdragon 7 Gen 3, measured with Bonsai-1.7B [02§F1][REV 2].
- OpenCL has Q1_0 but not Q2_0; Vulkan is weak on Adreno; Hexagon has neither format [02§F4–F5].

**The upstream lib as shipped cannot serve M1** (VERIFIED [V12]; [02§F9][03§F2.4]):
- no grammar, logits, embeddings or state entry points;
- `set(GGML_OPENMP ON)` for arm64 in its `CMakeLists.txt` (L22-23), so threads cannot be pinned, and a Gradle `-D` argument cannot override it (INFERENCE from CMake scoping);
- process-global `g_model`, `g_context`, `g_sampler` in `ai_chat.cpp` (L36-40);
- backends are loaded from `nativeLibraryDir` (`ai_chat.cpp` L51), which the upstream app makes work with `android:extractNativeLibs="true"` (manifest L7).

**Owner constraint:** nothing leaves the phone.

## Decision

1. **Pin.** llama.cpp submodule at `9588757` (b11205), never older than `8034c1d`. Bump only in a PR with a bench-bin comparison and `07-E-10`.
2. **Backends.**
   - The CPU backend is the default.
   - OpenCL is built as a runtime-loaded module, with `<uses-native-library libOpenCL.so required=false>`. It is promoted to planner default only if pp512 ≥ 2× CPU, tg is no worse, greedy output is identical over 64 tokens, and 30 minutes pass with no crash.
   - Vulkan, Hexagon, QHexRT and the PrismML fork are excluded from M1.
3. **Build.** `GGML_BACKEND_DL=ON`, `GGML_CPU_ALL_VARIANTS=ON`, `GGML_NATIVE=OFF`, `GGML_OPENMP=OFF`, `GGML_CPU_KLEIDIAI=ON`, `GGML_LLAMAFILE=OFF`, `LLAMA_OPENSSL=OFF`, no curl. minSdk 33, targetSdk 36, AGP ≥ 8.5.1.
   - **NDK** `29.0.13113456`, as the upstream lib pins it. The ubuntu-24.04 runner ships 27.3.13750724 (default), 28.2.13676358 and 29.0.14206865, not the pinned build [V13], so it is installed and cached (R21 correction).
   - **Packaging (R11):** `packaging.jniLibs.useLegacyPackaging = true`, so native libraries are extracted at install and `ggml_backend_load_all_from_path(nativeLibraryDir)` finds the CPU variants, as upstream's app does with `extractNativeLibs="true"` [V12]. CI checks the packaging and runs a variant-load smoke test; stage 0 confirms it on the phone (U37).
   - **ABIs (R25):** the shipped variants are arm64-v8a only. CI also builds an x86_64 `emulatorStub` flavour whose `:llm` is a Kotlin stub with no native code, for the emulator job.
4. **JNI base (pending OQ-21).** Default **(a) "upstream lib, extended"**: `:llm` compiles the upstream `examples/llama.android/lib` sources in place from the pinned submodule and adds a listed patch set. The submodule is never edited; changed files are copied into `llm/` with a header naming upstream path and commit, and CI diffs them on each bump.
   - P1: the copied `CMakeLists.txt` sets `GGML_OPENMP OFF`.
   - P2: an added `operator_jni.cpp` with handle-based entry points (model, context, sequence handles) for grammar sampling, label logits on a branch sequence, `embedLast`, state save/restore, `llama_attach_threadpool` and the abort callback. Every API was checked in `llama.h` at `9588757` [V6]. The upstream globals stay for the upstream chat path, used by the stage 0 spike and a smoke test.
   - P3: the Kotlin `InferenceEngine` interface is extended, not replaced; `LlmService` exposes the AIDL surface of FOUNDATION §2.3.
   - Option (b), an own JNI written from scratch over `llama.h` + `common`, is used only if the owner chooses it in OQ-21. The AIDL surface is the same either way, so the choice does not affect other ADRs.
5. **Integrity.**
   - A tensor-type allowlist admits Q1_0, Q2_0_g64, **F32** (Bonsai 8B has 145 F32 norm tensors, VERIFIED [REV 6]) and the types of the pinned CLM encoder quants. It rejects PrismML Q2_0 g128 and PQ2_0, which load silently upstream and produce gibberish [02§F1].
   - sha256 is pinned per file.
   - The model's sha256 is its `modelId`.
6. **No `android.permission.INTERNET`** in any operator variant; CI asserts it on both merged manifests. This covers **operator's own UID only**: data leaving through other apps' UIs is handled by the taint and context rules of ADR-0010 and ADR-0012 (review R4).
   - Models arrive by `adb push` into the app inbox (development) or by SAF import from Downloads (owner).
   - They are verified, then copied into `noBackupFilesDir/models` so mmap runs from internal storage.
   - Localhost test pages come from the separate fixture APK `dev.operator.fixture` (C12).

## Alternatives considered

| Alternative | Why not |
|---|---|
| Upstream `llama.android` lib unchanged in production | Missing grammar, logits, embeddings and state; OpenMP forced on; singletons [V12][02§F9] |
| Own JNI from scratch (the earlier draft of this ADR) | Departs from the module the owner named; offered as OQ-21 option (b) instead of being decided here (review R1) |
| OpenCL as default now | `libOpenCL.so` visibility on OxygenOS is unverified; q8_0 KV crash reports; the weights become non-reclaimable GPU memory [02§F4] |
| Vulkan | Crashes and about 7 t/s reported on Adreno [02§F5] |
| Hexagon / QHexRT | No Q1_0; experimental; SDK licence; third-party forks [02§F5] |
| INTERNET permission for Hugging Face downloads (lane 02 Q1) | "Nothing leaves the phone" is then no longer checkable from the manifest; the owner can download with a browser and import |
| mmap from shared storage | FUSE mmap performance unverified; copying to internal storage sidesteps it (INFERENCE) |
| Uncompressed, unextracted native libraries (AGP default) | `nativeLibraryDir` would hold no `.so`, so `GGML_BACKEND_DL` would load no CPU variant (INFERENCE, R11); loading variants by soname from the APK would need a loader change upstream does not have |

## Consequences

- Extending the JNI is M1 work; the patch set is kept small so upstream bumps stay cheap.
- APK size and install-time disk grow with the 7 CPU variants plus OpenCL, stored extracted (`02-M-13`).
- Model updates need adb or the owner's SAF import.

## Evidence

- [02§F1–F5], [02§F9], [02§Recommendation].
- [03§F2.4].
- [V6] `llama.h` at `95887577ab5f`; [V12] the upstream lib and app files; [V13] runner NDK list.
- [REV 6] Bonsai GGUF tensor types.
- [04§F25] json-schema-to-grammar limits.

## Open questions

- **OQ-21: JNI base** (upstream lib extended, or own JNI).
- OQ-19: Hexagon SDK licence, only if Hexagon is revisited at M3.
- Measurements: `02-M-1`, `02-M-2`, `02-M-5`, `02-M-7`, `02-M-13`; U37.
