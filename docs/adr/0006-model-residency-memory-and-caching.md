# ADR-0006: Model residency, memory budget and prompt/KV caching

- **Status:** Proposed
- **Date:** 2026-09-27 (revised after review findings R2, R10, R13, R21, R22, R26)
- **Design reference:** FOUNDATION §4.3–§4.7 (conflict C9)

## Context

**Memory** (INFERENCE from [02§F6]):
- The kernel sees 15.47 GB; usable RAM with typical apps is a placeholder of about 8 GB until `02-M-3`.
- Bonsai 8B Q1_0 weights are 1.16 GB. Repacked weights are anonymous memory; clean mmap pages are reclaimable.
- The KV cache for Qwen3-8B-shaped models is 144 KiB/token at f16 and 76.5 KiB/token at q8_0.
- The CLM encoder `*-outq2` files are 5.03 / 6.57 / 8.25 GB.
- Bonsai 27B (3.80 GB) plus CLM does not fit.

**Speed:** prefill dominates step latency. Re-prefilling 2,000 tokens at 30 t/s takes about 67 s [02§F8]. Decide calls add a branch prefill each (≈ 1.3 s per question at pp 30), and a CLM-8B state needs its own encoder prefill (FOUNDATION §4.5, review R13).

## Decision

1. **Planner.**
   - Bonsai 8B, repacked, 8k context, q8_0 KV on the CPU (f16 on OpenCL until q8_0 is proven), n_batch/n_ubatch 512.
   - Loaded on the first command; resident during a task and for 10 minutes after; a "keep warm" setting pins it. No `mlock`.
2. **Decide on Bonsai (logprob, M1 and fallback):** a branch sequence copied with `llama_memory_seq_cp` from the agent's sequence 0 [V6], then question + labels → label logits → drop. `n_seq_max ≥ 2`. `DecidePolicy.deadlineMs` is set per kind from `02-M-5` (placeholder 5,000 ms), not lane 03's 1,500 ms, which would abstain routinely at pp ≈ 30 (R13).
3. **CLM-8B encoder (M2, the owner's decide model; revised per R2):** the smallest Qwen3-8B `*-outq2` quant that passes fidelity gates G3/G4 and fits the measured RAM, in the order Q4_K_M (5.03 GB) → Q6_K (6.57 GB) → Q8_0 (8.25 GB).
   - Loaded with mmap and `use_extra_bufts=false`, so its weights stay reclaimable page cache.
   - Its own context: n_ctx 2048, f16 KV (0.29 GB), created lazily and freed when idle. The state `context` is capped at 512 tokens by default; its KV is reused across questions on the same state.
   - **Optional, only with the owner's approval (OQ-22):** E4 (Bonsai's own hidden state) and E5 (Bonsai + linear bridge). E4 runs on **its own sequence with recipe-exact tokens** (plain `context + "\n\n" + instructions`, no special tokens, no chat template), never on the chat-templated sequence 0 (R10): `n_seq_max = 3`, about 0.15 GB of q8_0 KV for 2,048 tokens, and a prefill of the state per decision.
4. **Bonsai 27B** is an M3 experiment in 27B-only mode (OQ-17): at most 4 `PARTIAL_ONLY` state checkpoints of about 150 MiB each, because its recurrent state cannot be truncated [02§F8]. The logprob branch (`n_seq_max = 2`) allocates a second recurrent state of about 0.15 GB (INFERENCE, R21), so the 27B total is about 5.7 GB.
5. **Prompt order:** static (rules, schema, one few-shot) → task, capability set and plan → append-only history → SCREEN + CHANGES → cue.
   - Truncate to the longest common prefix with `llama_memory_seq_rm` and decode the tail.
   - Save the static prefix state to a file keyed by (model sha256, prompt-version hash).
6. **Pressure:** on `onTrimMemory(RUNNING_CRITICAL)`, drop the decide context, then the checkpoints. An **operated-app survival check** (the app being operated and the owner's music or navigation app keep running while models load and a task runs; lmkd kills read from logcat) is part of `02-M-3`, the M1 lifecycle probe, the M2 soak, `02-M-11` and the 27B experiment (R26, K20).
7. **Threads:** 6 pinned to CPU0–5 via `llama_attach_threadpool` (4 when OpenCL runs the planner), with separate prefill and decode pools. APerformanceHint reporting; pace the loop when thermal headroom exceeds 0.9. Final values from the `02-M-5` sweep.
8. **Sampling (R22):** greedy for grammar-constrained calls; the vendor defaults (temperature 0.5, top_k 20, top_p 0.85, also in the GGUF's `general.sampling.*` [REV 4][REV 6]) for free text. Greedy free text on the 1-bit model is compared against them in `04-M1`/`07-E-11`.

## Alternatives considered

| Alternative | Why not |
|---|---|
| CLM Q8_0-outq2 as default (lane 02) | 8.25 GB touched; the smaller quants are tried first and Q8_0 is used only if they fail the gates and it fits |
| E4 (Bonsai state) first, as the earlier draft had it | E4 feeds the heads a different model's state; that changes the owner's decide model, so it needs the owner's approval (OQ-22) and its own recipe-exact prefill (R2, R10) |
| Always-resident Bonsai (lane 01 Q5 default) | About 2.4 GB of anonymous RAM taken from the owner's apps all day; load time is expected to be seconds (INFERENCE; `01-M5`) |
| mlock | Per-app limit unknown; it would count fully against lmkd [02§F7] |
| 27B + CLM together | Does not fit on an ~8 GB-usable phone [02§F6] |
| Re-prefill every step | 10–70 s per step at the measured rates [02§F8] |

## Consequences

- Needs the `:llm` JNI calls `seq_rm`, `seq_cp`, `state_seq_*` and `attach_threadpool` (ADR-0005).
- Latency depends on `02-M-5` (pp) and `02-M-9` (reuse). The OSF budget (ADR-0008) is tuned to them.

## Evidence

- [02§F6–F8], [02§Memory plan], [02§Caching plan].
- [03§F3], [03§R5].
- [V6] `llama.h` API lines.

## Open questions

- OQ-17 (27B mode without CLM); OQ-22 (CLM-8B cost; E4/E5).
- Measurements: `02-M-3`, `02-M-5`, `02-M-6`, `02-M-7`, `02-M-9`, `02-M-11`, `02-M-12`, `01-M5`, `03-M-3`.
