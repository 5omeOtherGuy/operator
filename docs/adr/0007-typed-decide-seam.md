# ADR-0007: Typed `decide()` seam, backends by milestone, CLM fidelity gates

- **Status:** Proposed
- **Date:** 2026-09-27 (revised after review findings R2, R10, R13, R24)
- **Design reference:** FOUNDATION §5

## Context

The owner set two roles, switchable at runtime, behind a typed seam modelled on brain ADR-0012 [V8]: generate/plan = Bonsai, and decide = "CLM-8B (Contrastive-LM/CLM-v0.1-8B, Apache-2.0: frozen Qwen3-8B last-token embedding + 2 MLP heads 4096->512, scaled cosine)". The checkpoint's `base_model` is `Qwen/Qwen3-8B` (VERIFIED [REV 8]), so the Qwen3-8B encoder is part of the owner's choice.

What lane 03 found in the CLM-v0.1-8B checkpoint and reference code [03§F1][REV 8]:
- Each head is a 3-layer fp32 MLP, 4096→1536→1536→512, with LayerNorm and exact GELU.
- The input is the L2-normalised last-token Qwen3-8B state of the plain text `context + "\n\n" + instructions`, tokenised without special tokens or chat template.
- The score scale is effectively 100.

Open facts:
- Whether a quantised `*-outq2` Qwen3-8B passes the fidelity gates, and its per-state prefill time on the phone (U41).
- That the reference vLLM pooler reads the post-final-RMSNorm state, like llama.cpp's `t_embd`, is UNVERIFIED (lane 03 infers it; G1 settles it) (R10).
- Whether 1-bit Bonsai states can drive the heads (the optional E4) is unknown [03§F3.2].
- The release is one week old [03§F1.1].

## Decision

1. **Interface in `:agent-core`** [03§R1]:
   - `DecisionKind`s: `task.intent` (CAPS; ADR-0012's `decide(TaskIntent)`), `route.api_or_ui`, `ui.target`, `task.done`, `effect.achieved`, `action.irreversible` (R24);
   - `Question<A>`: YesNo, Choice, Score or Rank, each with a `DecisionKind` and code-supplied instructions;
   - `DecisionState(context)`: the context is data;
   - `Decision<A>`: Decided, Abstained or Failed, with `DecisionMeta` (backend, modelId, headId, calibrationVersion, rawLogits, latencyMs);
   - `DecideBackend.rawScores` returns only raw per-option logits.
2. **Semantics** mirror CLM/TypeSafe `schema.py`:
   - `state_text = context + "\n\n" + instructions`;
   - noul candidates `"true: Yes. This is true: {q}"` and `"false: No. This is false: {q}"`;
   - `confidence = p_top − mean(p_rest)`.
   - Ported to Kotlin with golden tests against Python.
3. **Router** runs rules → configured backend → per-kind calibration → threshold τ_kind → Decided or Abstained. It writes a decision log with hashes and scores, **never screen text**.
4. **Safety asymmetry:** `decide()` may add a confirmation, an escalation or a narrowing, and never remove one. The gate never consults it to skip itself.
5. **Backends (revised per R2; the owner's CLM-8B is the decide model, the milestones only order when it can run):**
   - M1: RULES + BONSAI_LOGPROB is active (label tokens on a branch of the agent's KV; two-ordering averaging for high-stakes kinds). The CLM-8B port and heads loader ship as tested code, and G0–G3 run on Actions for the `*-outq2` quants.
   - **M2: RULES + CLM-8B** (Qwen3-8B `*-outq2`, file-backed, the smallest quant that passes G3/G4) is selectable at run time and becomes the default once G4 passes on the phone and it fits beside Bonsai. BONSAI_LOGPROB stays as the per-call fallback (encoder not loaded, memory pressure, missing calibration) and is logged beside CLM. If no quant passes, logprob stays the default and the owner gets the numbers (OQ-22).
   - M3: per-kind thresholds refitted on device labels; the per-kind comparison with logprob is reported to the owner, whose Settings switch decides.
   - **E4/E5** (Bonsai's own state, or a bridge) are optional encoder savings, used only if the owner approves them in OQ-22 and they pass the same gates.
   - 27B mode: logprob on 27B.
   - Because `decide()` can only add confirmations (Decision 4), no backend switch can weaken the gate.
6. **Fidelity gates G0–G5** before any CLM number counts [03§F5]:
   - G0 token parity 100 %; G1 tensor-position cos ≥ 0.999;
   - G2–G5: raw cos, projected cos and |Δlogit|, decision agreement, calibration drift.
   - Default bars: top-1 agreement ≥ 97 %; median |Δlogit| ≤ 0.5; Δaccuracy ≥ −1 pt; ECE ≤ reference + 0.02.
   - The bf16 reference E0 is layer-streamed on a **GitHub Actions matrix**, each slice under the 6 h per-job limit [V3], not on the shared laptop.
   - Mandatory on-device re-run against shipped reference projections.
   - **The gates run on exactly the extraction path the phone uses**, fed by the same `DecisionState` serializer (R10).
7. **Extraction (revised per R10):** a separate sequence (the encoder's own context; for the optional E4, a third sequence on the Bonsai context) whose tokens follow the recipe exactly: plain `context + "\n\n" + instructions`, no BOS, no special tokens, no chat template, tail kept, at most 2,048 tokens. `embeddings=true`, `pooling=NONE`, last row via `llama_get_embeddings_ith`, L2-normalised by us [V6]. Never the agent's chat-templated sequence 0. In llama.cpp the Qwen3 `t_embd` is the post-final-RMSNorm output (VERIFIED [REV 1]); the reference side is UNVERIFIED until G1.
   - **Cost:** one prefill of the state `context` per state (capped at 512 tokens by default; placeholder 5–17 s at 30–100 t/s, INFERENCE) plus ≈ 40 tokens per question with the context KV reused; one encode per Rank candidate, cached. `02-M-6` and `03-M-3` measure it.
   - **Deadline:** `DecidePolicy.deadlineMs` per kind from the measured rates (FOUNDATION §4.5), not a fixed 1,500 ms (R13).
8. **Heads:** exported to safetensors (fp32, sha256-checked); Kotlin fp32 reference first; native (`ggml_gelu_erf` or NEON) only if `03-M-4` exceeds about 5 ms.
9. **Calibration:** per-kind temperature; isotonic only with ≥ 1000 labels; files versioned by (backend, encoderId, headId, recipeVersion); abstain-heavy defaults when a file is missing.
10. **Cache key:** `sha256(encoderId‖headId‖recipeVersion‖role‖text)`; the value is a 512-d fp16 projection; texts are not stored.

## Alternatives considered

| Alternative | Why not (now) |
|---|---|
| CLM-8B on the phone in M1 | Needs an encoder that has passed the gates and RAM numbers from stage 0; M1 runs the gates in CI instead, and M2 ships it |
| CLM only in shadow from M3, promoted by fleet gates (the earlier draft) | Changed the owner's decide choice without asking (R2) |
| E4 (Bonsai state) before the Qwen3-8B encoder (the earlier draft) | A different model under the heads; the owner decides (OQ-22) |
| Retrain CLM on Bonsai states | Training sets unpublished; GPU-scale re-embedding [03§F3.4] |
| Bonsai free-form generation as decide | Uncalibrated; parse failures. It remains the escalation target for Abstained answers. |
| bf16 reference on the laptop (lane 03 default) | The laptop is shared and has about 11 GB free; Actions on the public repo is free and splittable [V3][07§F5] |

## Consequences

- M1 needs `labelLogits` and `tokenize` in `:llm`; M2 needs `embedLast` and the encoder import.
- With CLM-8B selected, the phone carries 5.03–8.25 GB of file-backed encoder pages and about 0.7 GB more anonymous memory (FOUNDATION §4.3).
- A decision-set format with gold labels per kind is owed by lane 07.
- Calibration data never leaves the phone (see OQ-2 for evaluation exports).

## Evidence

- [03§F1–F7], [03§R1–R7].
- [04§F7] V-Droid verifier precedent.
- [V3] Actions limits.
- [V6] embeddings API.
- [V8] ADR-0012.

## Open questions

- OQ-22 (CLM-8B cost and timing; E4/E5), OQ-17 (27B mode drops CLM).
- Measurements: `03-M-1…M-9`, `02-M-6`, `02-M-11`; U40, U41.
