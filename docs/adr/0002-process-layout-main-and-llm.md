# ADR-0002: Process layout: Kotlin main process + one native `:llm` process

- **Status:** Proposed
- **Date:** 2026-09-27
- **Design reference:** `docs/design/FOUNDATION.md` §2 (conflict C1, C2, C13)

## Context

- **Native code must stay out of the hands' process.** When the process hosting an accessibility service dies, AOSP marks the service crashed. Corrected per review R9: the system's `BIND_AUTO_CREATE` binding survives, AMS schedules a restart of the process after a backoff, and `initializeService()` clears the crashed mark on reconnect; the service stays down only after `BOUND_SERVICE_MAX_CRASH_RETRY` crashes or a kill that allows no restart [V10]. So a native crash in this process costs a restart gap, a crash count toward the limit, and a lost task; a multi-GB native heap also makes the process an LMK target, and prefill cannot be interrupted [01§F1.8]. A force-stop removes the service from the enabled setting [01§F1.3].
- **Lanes disagreed on the layout:**
  - 01: one process per model role, with the upstream JNI lib unchanged;
  - 02: one `:llm` process;
  - 05: `:agent` (llama + loop), executor, `:gate`;
  - 06: the model hosted in the DeviceAdminService.
- **The models share a runtime.** The logprob decide backend reuses Bonsai's weights and KV cache; the CLM-8B encoder (M2) shares the llama.cpp backend, the threadpool and one memory budget; the optional E4 would reuse Bonsai's weights on a separate sequence [03§R4–R5].

## Decision

- **Main process `dev.operator`, Kotlin only:**
  - UI, AgentService (FGS), agent loop, DecideRouter;
  - Executor, ScreenService (a11y), Gate;
  - direct-API adapters, NotificationListener;
  - admin receiver, DeviceAdminService (M2);
  - Keeper, audit log.
- **One `:llm` process** holds llama.cpp and every model. It is a bound service reached through `:llm-api` AIDL: load, tokenize, generate with grammar, labelLogits on a branch sequence, embedLast (M2, CLM-8B), state save/restore, abort, pid, stats, bench.
  - It is bound with `BIND_AUTO_CREATE` from the FGS, which ranks it at least as high as the client [V5].
  - Each payload is under 256 KB [01§F2.1].
- **Hard stop** = the **main process kills `:llm` by PID** (`Process.killProcess`, same UID) and unbinds; no `killSelf()` over AIDL, which a wedged `:llm` could not serve (review R18). After an unplanned death the task goes to `Paused(inference_died)` and gets one retry, then the owner is notified.
- **Module boundary instead of a process boundary for the loop:**
  - `:agent-core` is pure JVM;
  - the only path to an effect is `Executor.execute(ToolCall, ApprovalToken?)`;
  - a CI grep forbids gesture, DPM and SMS calls outside the executor and adapter packages.
- **Fixtures:** a separate `dev.operator.fixture` APK hosts the test fixtures.

## Alternatives considered

| Alternative | Why not |
|---|---|
| P1: one process | A native crash or LMK kill takes the hands down for a restart backoff and counts toward AMS's crash limit, after which they stay down until toggled [V10]; the prefill cannot be interrupted [01§F1.8] |
| P3: `:gen` + `:decide` (lane 01) | Built around the upstream lib's process-global singletons, which the extended lib (ADR-0005, P2) no longer relies on. It would split Bonsai weights and KV across processes, although logprob decide needs the same context, and double the native baseline. |
| `:agent` process with loop + llama (lane 05) | The loop is our code, and the model's output is data. A process boundary adds IPC for every screen and gives no extra guarantee over the executor's validation. |
| LLM inside the DeviceAdminService process (lane 06) | That puts native code in the a11y host process; the DAS is in main |
| `isolatedProcess` for `:llm` | Deferred: models are opened by path, and fd passing is unproven [01§Options P5] |

## Consequences

- A `:llm` crash loses both roles at once. That is acceptable because they share weights; the reload time is measured by `01-M5` and `02-M-7`.
- There is an extra ART baseline, measured by `01-M4`.
- The screen serializer and the loop run in main on the `hands` and task dispatchers.

## Evidence

- [01§F1] crashed-service semantics (AOSP android16-release), corrected by [V10] (AOSP r4 `AccessibilityServiceConnection`, `ActiveServices`).
- [01§F2.1] binder buffer of 1 MB.
- [02§F7] several models and contexts per process in llama.cpp.
- [V6] `llama_init_from_model` and `llama_memory_seq_cp` at `9588757`.
- [V5] developer.android.com process lifecycle (bound-process importance).

## Open questions

None for the owner (the JNI base is OQ-21 under ADR-0005). Measurements: `01-M1` (oom_adj of `:llm`), `01-M3` (a11y survives a SIGKILL of `:llm`; time to reconnect after a SIGKILL of the main process), `01-M4` (PSS).
