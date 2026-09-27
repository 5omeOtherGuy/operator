# 03 decide(): CLM-8B on device, type system, backends, calibration

Lane 03. Design only. Access date for every source: 2026-09-27.
Tags: **VERIFIED [n]** (numbered source below), **UNVERIFIED** (plus what would verify it), **INFERENCE** (my reasoning, not a source).

## Scope (questions covered)

1. What exactly is Contrastive-LM/CLM-v0.1-8B: encoder, how the embedding is taken, token recipe, head shapes, scale/temperature, training data, licence, reference code.
2. Running it on the phone: llama.cpp embedding extraction from a Qwen3-8B GGUF, whether that matches the hidden state CLM was trained on, quantisation choice, and a fidelity test (where it can run given ~11 GB free laptop disk).
3. The heads in Kotlin: export format, cost per call, SIMD, the candidate-embedding cache.
4. Avoiding a second 8B model: Bonsai as the encoder, adapting or retraining the heads, Bonsai logprob scoring as a backend.
5. The `decide()` type system (Kotlin sealed types), backends, calibration, abstain/escalation, and how the agent loop uses it.

Out of scope: loading/threading of llama.cpp (lane 02), screen serialisation (lane 04), the confirmation gate itself (lane 05), CI plumbing (lane 07).

## Findings

### F1. What CLM-v0.1-8B is

- F1.1 Two projection heads (a state head and an action head) on a frozen Qwen3-8B encoder, trained with bidirectional InfoNCE. Licence Apache-2.0. **VERIFIED [1][5]**. Hub tags: `base_model:Qwen/Qwen3-8B`, `pipeline_tag: text-ranking`. Repo created 2026-09-21, last modified 2026-09-24 **VERIFIED [2b]** (a week-old release).
- F1.2 `config.json`: `encoder_pooling: "last-token"`, `embedding_dim: 4096`, one checkpoint `CLM_v0.1-8B.pt`, library `contrastive-lm`, code `github.com/Contrastive-LM/CLM`. **VERIFIED [2]**
- F1.3 Checkpoint is 75,557,149 bytes, LFS sha256 `b2b4a8c9c2d39263eff78a351eb909a342ce9b3bf21a3f07c1d1bf15f1c4eda5`. **VERIFIED [3]**
- F1.4 Exact head architecture, read from the checkpoint's own pickle (`qwen3_8b_posttrained_head/data.pkl`, fetched by HTTP range request, no torch needed) **VERIFIED [4]**:
  - `cfg = {model: "Qwen/Qwen3-8B", hidden_size: 4096, projection_dim: 512, width: 1536, depth: 3, activation: "gelu", layernorm: True, residual: False}`.
  - Each head: `inp` Linear 4096→1536, GELU; `hidden.0` Linear 1536→1536, `norms.0` LayerNorm(1536), GELU; `out` Linear 1536→512. All tensors `torch.FloatStorage` (fp32).
  - Parameters per head 9,443,840; both heads 18,887,680 (= 75.55 MB fp32, matching the file size). The README's "20M-parameter" head is this pair. **VERIFIED [4][5]** (arithmetic from the tensor shapes).
  - So the owner's "2 MLP heads 4096->512" is right at the interface; inside, each is a 3-layer MLP with one LayerNorm. **VERIFIED [4]**
- F1.5 Forward pass (reference code): `x = gelu(inp(x)); x = gelu(norm(hidden(x))); z = out(x)`, then `normalize(z)` (L2) per head. **VERIFIED [6]**. `gelu` is `torch.nn.GELU()` with default `approximate='none'` (exact erf) **VERIFIED [6][34]**; LayerNorm default eps 1e-5 **VERIFIED [6][35]**.
- F1.6 Score = `exp(logit_scale) * cos(state_head(s), action_head(c))`, with `exp(logit_scale)` clamped to max 100. **VERIFIED [6]**. The stored `logit_scale` is 4.61325 (fp32), `exp` = 100.81, so the effective scale is **exactly 100.0** after the clamp. **VERIFIED [4][6]**. Training initialises it at `log(1/0.07)` **VERIFIED [14]** (summary of finetune.py via fetch; the code itself was not quoted).
- F1.7 Per-question distribution = softmax over that question's candidates of `scale*cos/temperature`; request `temperature` in (0, 100], default 1.0. **VERIFIED [11]**
- F1.8 Encoder input: the 4096-d embedding is **L2-normalised before the heads** (`l2()` in both the server embedder and the training precompute). **VERIFIED [7][9]**
- F1.9 Which hidden state: the reference encoder is `vllm serve Qwen/Qwen3-8B --runner pooling` with last-token pooling and prefix caching, "same as the precompute, so the embeddings match what the head was trained on". **VERIFIED [8][9]**. vLLM's default for a converted generative model on the embed task is `LAST` pooling with normalisation. **VERIFIED [20]**. That the pooled vector is the output of the decoder stack **after the final RMSNorm** (not the logits, not an intermediate layer) is **INFERENCE** from how vLLM wraps `*ForCausalLM` models; verify by the G1 test below (HF `last_hidden_state` vs published CLM embeddings, F5.6) or by reading vLLM's `as_embedding_model` adapter.
- F1.10 Encoder weights are `Qwen/Qwen3-8B` (the post-trained hybrid-thinking model, not `Qwen3-8B-Base`). **VERIFIED [4][8]**. The checkpoint directory is named `qwen3_8b_posttrained_head`. **VERIFIED [4]**
- F1.11 Token recipe of the precompute **VERIFIED [9]**:
  - string state: tokenised as plain text **without special tokens**, keep the **tail** (last `max_len-1` tokens; precompute `max_len` 8192);
  - message-list state: `apply_chat_template(..., add_generation_prompt=False)`, keep the tail;
  - candidate text: plain text without special tokens, keep the **head**.
  - The demo server truncates at 2048 tokens (`--max-model-len 2048`, `truncate_prompt_tokens`). **VERIFIED [7][8]**
  - Qwen3-8B tokenizer: `add_bos_token: false`, `bos_token: null`. **VERIFIED [29]**. So no BOS/EOS is added in the reference path (**INFERENCE** for the vLLM text path; the token-parity gate G0 checks it).
- F1.12 Prompt template (what the state head sees) **VERIFIED [10]**: `state_text = to_text(state) + "\n\n" + to_text(instructions)` ("context first, question last — the layout the heads were trained on"). `to_text` renders dicts as `key: value` blocks and lists as `- item` lines, "the heads are trained on prose, not on JSON".
  - Candidates **VERIFIED [10]**: `choice` → the option's description (or its key) with no prefix; `score` → each rubric level's text; `noul` (bool) → two candidates `"true: Yes. This is true: {instructions}"` and `"false: No. This is false: {instructions}"` unless custom descriptions are given.
  - Answer shapes **VERIFIED [10][12]**: `noul` → P(true); `choice` → argmax key, `confidence = p_top − mean(p_rest)`, full distribution; `score` → expected level index Σ i·p_i plus distribution and legend. This is the TypeSafe `/v1/systemone` wire shape (the same shape ADR-0012 designed for Jev). **VERIFIED [10][39]**
- F1.13 Candidate/state caching in the reference server: an LRU "VectorArena" of raw 4096-d embeddings and 512-d projections keyed by `"{head_name@generation}\x00{text}"`. **VERIFIED [13]**
- F1.14 Training data: pre-train ~60M Nemotron Q&A pairs, mid-train ~30M synthetic hard negatives, post-train ~1M agentic trajectories with 40 % Nemotron replay. **VERIFIED [1][5]** (vendor statement).
  - Published: `Contrastive-LM/CLM-v0.1-Pretrain-Nemotron` (62,451,070 rows, 1.02 TB; visible columns `doc_id`, `pair_idx`, no card, licence unstated) **VERIFIED [17]**; that it holds precomputed embeddings rather than text is **INFERENCE** (1.02 TB / 62.45M rows ≈ 16.3 KB/row ≈ 2 × 4096 × fp16). `deepswe-clm-train-embeddings-8k` (405,919 rows, fp16 Qwen3-8B last-token embeddings, **no raw text**, MIT) **VERIFIED [16]**. No mid-train or post-train set is listed under the org **VERIFIED [18]**.
  - `train/finetune.py` exists (tasks `clm` and `choice`; defaults width 1536, depth 3, proj 512, GELU, LayerNorm; `choice` trains on `LocalLLaMA/typed-decisions`) **VERIFIED [14]** (fetch summary). That dataset is Apache-2.0, 1K–10K rows, 4 configs (agent traces, customer service, invoices, security incidents) with train/test splits **VERIFIED [19]**.
- F1.15 Claims: "on par with Jev" zero-shot with "up to 9× lower latency", Terminal-Bench 2.1 87.6 % and DeepSWE 81.6 % **fine-tuned**; "encoder-locked" to Qwen3-8B; scores only, no generation. **VERIFIED [1][5]** as vendor claims; **UNVERIFIED** independently. Nothing in them measures phone-UI decisions; zero-shot quality on our decisions must be measured (lane 07 decision set).

### F2. Running the encoder on the phone with llama.cpp

- F2.1 llama.cpp's Qwen3 graph applies the final RMSNorm (`output_norm`), sets `res->t_embd = cur` **after** that norm, then applies the LM head. **VERIFIED [21]**. So llama.cpp's embedding for Qwen3 is the same tensor position as F1.9 (post-final-norm last layer). **INFERENCE** (hinges on F1.9).
- F2.2 API: `llama_context_params.embeddings` and `.pooling_type` (`NONE=0, MEAN, CLS, LAST=3, RANK`); `llama_get_embeddings_seq()` returns `float[n_embd]` for pooled types; `llama_get_embeddings_ith()` returns a token's `[n_embd]` row when pooling is NONE. **VERIFIED [22]**. The header says nothing about normalisation **VERIFIED [22]**; normalisation is a separate helper `common_embd_normalize(..., 2)` (L2) **VERIFIED [24]**. We must L2-normalise ourselves before the heads (F1.8).
- F2.3 Pooled embeddings are extracted per ubatch ("cleared before processing each batch"); non-causal attention requires `n_ubatch >= n_tokens`. **VERIFIED [23]** (fetch summary of llama-context.cpp). Whether `LAST` pooling of a causal model is correct when a long state spans several ubatches, or when the state's prefix is reused from KV cache, is **UNVERIFIED**; verify in the G1 test by comparing `pooling=LAST` against `pooling=NONE` + `llama_get_embeddings_ith(last)`. Design default: `pooling=NONE`, `embeddings=true`, request output only for the last token, read `get_embeddings_ith(-1)`. That path works with incremental prefill and KV prefix reuse (**INFERENCE**).
- F2.4 The upstream `examples/llama.android` JNI (`ai_chat.cpp`) exposes init/load/prepare/systemInfo/bench/processSystemPrompt/processUserPrompt/generateNextToken/unload/shutdown and **never enables embeddings or reads logits**. **VERIFIED [25]**. Lane 02 must add `embed` and `labelLogits` entry points (see Interfaces).
- F2.5 Official Qwen3-8B GGUFs: Q4_K_M 5,027,783,488 B; Q5_0 5.72 GB; Q5_K_M 5.85 GB; Q6_K 6,725,899,040 B; Q8_0 8,709,518,112 B. There is no BF16/F16 GGUF in that repo. **VERIFIED [27]**. The bf16 safetensors are five shards totalling ~16.38 GB, the largest 3,996,250,744 B. **VERIFIED [28]**
- F2.6 How much Q8_0 / Q6_K / Q4_K_M move the last-token hidden state, and how much that moves CLM decisions: **UNVERIFIED**, no published number for this head. Sensitivity is high: with scale 100 (F1.6), a projected-cosine error of 0.01 is a 1.0 logit error. **INFERENCE** from F1.6. The fidelity test (F5) exists for this.

### F3. Bonsai and CLM

- F3.1 Bonsai 8B "is built from Qwen3-8B … The architecture is unchanged: the novelty lies entirely in the deployment stack"; 36 blocks, GQA 32/8, vocab 151,936, end-to-end 1-bit weights including embeddings and LM head; GGUF `Q1_0_g128`. **VERIFIED [30][31]** (whitepaper §4, model card). A secondary source says the 1-bit compression is post-training from stock Qwen3-8B; another says "trained natively at 1-bit from scratch". The whitepaper's "built from Qwen3-8B … architecture unchanged" supports derivation from Qwen3-8B; the method is proprietary and undisclosed. **VERIFIED [31]** for "built from", **UNVERIFIED** for the exact procedure (would need PrismML's statement).
- F3.2 Consequence: Bonsai 8B has the same tokenizer, the same 4096-d post-norm last-layer state and (probably) a hidden state close to Qwen3-8B's. So **Bonsai 8B itself is a candidate CLM encoder**: feed its last-token `t_embd` into the unchanged CLM heads. How close it is: **UNVERIFIED**, and it is the most valuable single measurement in this lane (F5, candidate E4). 1-bit weights across every matrix may move the final state much more than Q4_K_M does. **INFERENCE**
- F3.3 Bonsai 27B is derived from Qwen3.6-27B (search-result snippets) **UNVERIFIED** (read the Bonsai-27B card/whitepaper). A different base with a different hidden width means CLM heads cannot take 27B states. **INFERENCE**. The logprob backend (F4) works with either Bonsai.
- F3.4 Retraining the heads on Bonsai embeddings, full recipe: the ~30M hard-negative and ~1M trajectory sets are not published (F1.14), and the pretrain set appears to be Qwen3 embeddings keyed to Nemotron doc ids, not text (**INFERENCE**). Reproducing CLM on Bonsai would mean re-embedding tens of millions of texts, which needs GPU time we do not have. **INFERENCE**. Not viable for M1/M2.
- F3.5 Cheaper adaptations **INFERENCE**, all using paired embeddings produced by the fidelity pipeline:
  - (a) **linear bridge**: fit `W` (4096×4096, ridge or orthogonal Procrustes) mapping Bonsai states to Qwen3-8B bf16 states on 10k–50k operator-domain texts, then use the frozen CLM heads. Closed-form fit on CPU.
  - (b) **head distillation**: train new heads on Bonsai states to reproduce the CLM 512-d projections of the Qwen3 reference (MSE + InfoNCE on the teacher's logits). CPU-trainable at 18.9M params on ~10^5 pairs.
  - (c) **task fine-tune** of heads on `typed-decisions` plus our own labelled decision set using `finetune.py --task choice` (F1.14).
  Any of these produces a new head identity with its own calibration (F6).

### F4. Bonsai logprob scoring as a decide backend

- F4.1 Scoring principle: put the question and labelled options in the prompt, run one forward pass, read the next-token logits of the option labels (single tokens such as `A`–`Z`, `yes`/`no`, `0`–`4`), softmax over the label logits only. Larger LMs are "well-calibrated on diverse multiple choice and true/false questions when they are provided in the right format" **VERIFIED [38]**. Whether 1-bit Bonsai 8B keeps that calibration is **UNVERIFIED** (measure ECE on the decision set).
- F4.2 Cost: if the agent's context already holds the screen state in KV cache, a decision costs the question suffix prefill plus one decode step, with **no extra model in RAM**. **INFERENCE**. Needs a lane-02 `labelLogits` JNI call with KV prefix reuse (`llama_memory_seq_cp`-style branching; exact API **UNVERIFIED**, lane 02).
- F4.3 Known failure: option-position bias. Mitigation: for high-stakes kinds, score two permutations and average (2× cost). **INFERENCE**
- F4.4 Qwen3 hybrid thinking must be off for label scoring (template with `enable_thinking=false`, as PrismML does in its evaluation) **VERIFIED [31]** that PrismML disables thinking for Qwen3-family evals. Applying the same to our scoring prompt is **INFERENCE**.

### F5. The CLM fidelity test (design)

- F5.1 Purpose: decide which encoder can drive the CLM heads on the phone, with numbers, before any on-device integration. Candidates: **E0** reference Qwen3-8B bf16 weights, fp32 accumulation (HF transformers); **E1** Qwen3-8B Q8_0; **E2** Q6_K; **E3** Q4_K_M; **E4** Bonsai-8B Q1_0; **E5** Bonsai-8B + linear bridge (F3.5a); optional **E6** Ternary-Bonsai-8B.
- F5.2 Corpus (no private data): ~300 states × their questions → ~1,500 candidate texts, ≤ ~60k tokens in total:
  - `typed-decisions` test split (labelled, public) **VERIFIED [19]** for existence;
  - the operator synthetic decision set (screen-state renderings plus YesNo/Choice/Score/Rank questions with gold labels), authored by lanes 04/07;
  - the CLM README/client examples as smoke cases.
  All texts are built by the Kotlin port of `schema.py` (F1.12), so the test also checks the port.
- F5.3 Gates, in order:
  - **G0 token parity**: HF tokenizer ids == llama.cpp tokenizer ids (`add_special=false`, `parse_special=false`) for every text, and no BOS/EOS added (F1.11). Must be 100 %.
  - **G1 tensor position**: E0 `last_hidden_state[-1]` vs llama.cpp E1 `t_embd` (both `pooling=LAST` and `NONE`+ith). Expect cos ≥ 0.999 on L2-normalised vectors (**INFERENCE** threshold). Failure means we are reading the wrong tensor, not a quantisation effect.
  - **G2 raw embedding**: cos(e_q, e_ref) over the 4096-d L2-normalised vectors; report mean, p5, min per candidate.
  - **G3 projected/logit**: cos of 512-d projections per head, and |Δlogit| = 100·|cos_q − cos_ref| per (state, candidate) pair; report median and p95.
  - **G4 decision**: top-1 agreement, KL(p_ref‖p_q), |ΔP(true)| for YesNo, Spearman ρ for Rank, Δaccuracy on labelled items.
  - **G5 calibration drift**: fitted per-kind temperature T_q vs T_ref, ECE (15 bins), Brier, before and after refitting.
  Proposed pass bar (**INFERENCE**, a design default, not a source): G0 100 %; G1 cos ≥ 0.999; top-1 agreement ≥ 97 % overall and ≥ 99 % where the reference confidence ≥ 0.8; median |Δlogit| ≤ 0.5; Δaccuracy ≥ −1 pt; post-refit ECE ≤ ref ECE + 0.02.
- F5.4 Where it runs:
  - **E0 reference, layer-streamed**: load the embedding table, then one decoder layer at a time from the safetensors shards (largest shard 4.0 GB **VERIFIED [28]**), push all corpus activations through that layer, drop it, continue; final norm at the end. Peak disk is one shard plus activations: 60k tokens × 4096 × 2 B (bf16) ≈ 0.5 GB **INFERENCE**. Fits the laptop's ~11 GB free and a GitHub-hosted runner's 14 GB SSD; RAM fits a 16 GB public-repo runner (4 vCPU) or even an 8 GB private-repo runner (2 vCPU) **VERIFIED [33]** for runner specs. Compute ≈ 2 × 8.2e9 FLOP/token × 60k tokens ≈ 1e15 FLOP; at an assumed 50–200 GFLOP/s on 2–4 x86 cores this is ~1.4–5.5 h (**INFERENCE**, measure). Split the corpus across matrix jobs to stay under the Actions per-job time limit (limit value **UNVERIFIED**, lane 07 checks it).
  - **E1–E6 on x86**: llama.cpp CPU in GitHub Actions from the pinned submodule; Q8_0 (8.7 GB) is the tightest fit on a 14 GB runner disk **VERIFIED [27][33]**; stream one GGUF per job.
  - **On device (mandatory)**: ARM kernels (NEON/dotprod/i8mm repacks, any GPU backend) differ numerically from x86, so the chosen encoder is re-measured on the phone. Ship the reference 512-d projections (fp16, ~1,800 × 2 × 1 KB ≈ 4 MB) plus corpus texts in a debug/test build; compute G2–G5 on the device and report only the summary numbers. **INFERENCE**
  - The laptop cannot hold the bf16 model whole (16.4 GB > 11 GB free) **VERIFIED [28]** + README task facts; layer streaming is why E0 is feasible at all.
- F5.5 Optional anchor: the published `deepswe-clm-*-embeddings-8k` vectors are exactly what the head was trained/evaluated on (vLLM output) **VERIFIED [16]**. If the matching DeepSWE rollout texts can be obtained, recomputing a few hundred with E0 would validate E0 against the training-time encoder. **UNVERIFIED** (needs the raw rollouts and their state rendering).

### F6. Heads in Kotlin: format, cost, SIMD, cache

- F6.1 Export: a CI script reads the `.pt` once, checks the LFS sha256 (F1.3), and writes `clm-heads-v0.1.safetensors` (fp32, tensor names as in F1.4, `__metadata__` with `logit_scale`, `scale_clamp=100`, `width`, `depth`, `activation=gelu_erf`, `ln_eps=1e-5`, `source_sha256`, `recipe_version`). An fp16 variant (37.8 MB) is produced too and must pass G3/G4 against fp32 before use. safetensors is a JSON header plus raw little-endian arrays, trivial to mmap from Kotlin (`FileChannel.map`) or C++. **INFERENCE**
- F6.2 Cost per vector per head: 4096·1536 + 1536·1536 + 1536·512 = 9,437,184 MAC (18.9 MFLOP), streaming 37.8 MB of fp32 weights. **VERIFIED [4]** (shapes) / arithmetic. Scalar Kotlin on ART: order 10–40 ms per vector (**INFERENCE**, measure). Native NEON: memory-bound, low single-digit ms (**INFERENCE**, measure). Candidate batches turn GEMV into GEMM and amortise the weight stream.
- F6.3 SIMD: the JDK Vector API is still incubating (JEP 537, twelfth incubator) **VERIFIED [41]**; that ART does not ship `jdk.incubator.vector` is **UNVERIFIED** (check the Android SDK `android.jar`). So SIMD means native: either ggml ops in the existing llama.cpp JNI library (`ggml_mul_mat`, `ggml_norm(eps=1e-5)`, `ggml_gelu_erf`, which exists and "uses erf when possible; some backends may fall back to an Abramowitz–Stegun approximation" **VERIFIED [26]**) or ~150 lines of NEON C++. Do **not** use `ggml_gelu` (tanh approximation) since the head uses exact GELU (F1.5). **INFERENCE**
- F6.4 Plan: Kotlin fp32 reference implementation first (unit-testable on the JVM against Python golden vectors); native path only if the on-device measurement exceeds the budget (target ≤ 5 ms per state projection, **INFERENCE**).
- F6.5 Cache (**INFERENCE**, modelled on F1.13):
  - key = SHA-256(`encoderId` ‖ `headId` ‖ `recipeVersion` ‖ role{state|action} ‖ text), where `encoderId` = sha256 of the GGUF (computed once at model install), `headId` = sha256 of the heads file, `recipeVersion` = version of the `to_text`/candidate rendering;
  - value = 512-d fp16 projection (1 KB); optionally the raw 4096-d fp16 embedding (8 KB) only while a head hot-swap is being evaluated;
  - two tiers: in-memory LRU (a few thousand entries) and an app-private on-disk LRU (≈50k entries ≈ 50 MB); texts are never stored, only hashes, because they are screen content;
  - any change of encoder, head or recipe changes the key, so there is no explicit invalidation;
  - states are cached too: an agent revisits screens.
- F6.6 Prefix reuse: state_text puts the question last (F1.12), so several questions about one screen share the context tokens. Take the longest common token prefix between the cached sequence and the new token ids (BPE may re-merge at the `\n\n` boundary) and prefill only the rest. **INFERENCE**

### F7. Calibration

- F7.1 Temperature scaling (one parameter per decision kind) is "surprisingly effective" for neural classifiers **VERIFIED [36]**; it is a 1-D convex NLL fit, cheap enough to refit on the device. Platt/sigmoid suits small samples; isotonic "will perform as well as or better than 'sigmoid' when there is enough data (greater than ~1000 samples)" and is "more prone to overfitting, especially on small datasets" **VERIFIED [37b]**. Default: per-kind temperature; isotonic only for kinds with ≥ 1000 labelled outcomes.
- F7.2 Abstention: selective classification picks a rejection threshold that meets a target risk on accepted items **VERIFIED [37]**. Per kind k, choose τ_k as the lowest confidence threshold with held-out error ≤ the kind's risk budget; below τ_k return `Abstained`.
- F7.3 CLM's scale of 100 makes raw distributions very peaked (cos gap 0.05 → p ≈ 0.993 between two candidates), so raw CLM probabilities are likely over-confident on out-of-domain phone states. **INFERENCE**; measure ECE on the decision set before trusting any raw probability.
- F7.4 Label sources: (1) off-device: `typed-decisions` and the synthetic operator set; (2) on-device only: owner answers at the confirmation gate and lane-05 effect verification (weak labels). Calibration files are versioned by (backend, encoderId, headId, recipeVersion); a missing or mismatched file falls back to conservative defaults (τ high, abstain-heavy). **INFERENCE**

## Options

| Option | What it gives | Cost / risk | Evidence |
|---|---|---|---|
| O1 Rules backend (deterministic) | Exact, instant answers for structural questions (package allowlists, node flags, known dialogs, irreversible-action classes) | Coverage limited; hand-maintained | INFERENCE |
| O2 Bonsai logprob scoring (label tokens) | Typed answers with no extra RAM; reuses the agent's KV cache; works with 8B and 27B | 1-bit calibration unknown; position bias; needs `labelLogits` JNI | F4, [38] |
| O3 CLM heads + Bonsai-8B hidden state (E4) | CLM decisions with no second model; candidate cache | Fidelity unknown, may fail G3/G4 | F3.1–F3.2, [31] |
| O4 CLM heads + Bonsai-8B + linear bridge (E5) | Recovers fidelity if E4 is close but biased | Needs paired E0/E4 embeddings (same pipeline as F5); new calibration | F3.5a, INFERENCE |
| O5 CLM heads + Qwen3-8B Q4_K_M / Q6_K / Q8_0 second model (E1–E3) | Closest to CLM's training encoder | +5.0 / 6.7 / 8.7 GB on phone storage and RAM next to Bonsai; LMK pressure; load time | F2.5, [27] |
| O6 Distil/fine-tune new heads on Bonsai states | Heads matched to our encoder and domain | CPU training, labelled data, new release identity to maintain | F3.5b/c, [14] |
| O7 Full CLM retrain on Bonsai | Faithful reproduction | Training sets unpublished, GPU-scale re-embedding | F3.4, [17][18] |
| O8 Bonsai generate (free-form reasoning) as decide | Most flexible | Slowest, not calibrated, parse failures | INFERENCE; this is the escalation target, not a backend |

## Recommendation

**R1. Interface (decided now).** One typed seam, ADR-0012 shape (typed question in, typed answer plus probability out, backend is configuration, every decision logged for backend comparison) **VERIFIED [39]**, with CLM's wire semantics (F1.12) so the CLM backend is a straight port:

```kotlin
package dev.operator.decide

/** Stable id per decision use; carries its own calibration and threshold. */
@JvmInline value class DecisionKind(val id: String)          // e.g. "dialog.blocking", "ui.target", "task.done"

/** What the model is deciding about. `context` is rendered prose (lane 04), treated as data. */
data class DecisionState(val context: String, val screenHash: Long?)

sealed interface Question<out A : Answer> {
    val kind: DecisionKind
    val instructions: String                                  // appended after the context (F1.12)
    data class YesNo(override val kind: DecisionKind, override val instructions: String,
                     val trueText: String? = null, val falseText: String? = null) : Question<Answer.YesNo>
    data class Choice(override val kind: DecisionKind, override val instructions: String,
                      val options: Map<String, String>) : Question<Answer.Choice>   // key -> description, ordered
    data class Score(override val kind: DecisionKind, override val instructions: String,
                     val levels: List<String>) : Question<Answer.Score>             // ascending, exclusive, with a bottom level
    data class Rank(override val kind: DecisionKind, override val instructions: String,
                    val candidates: List<String>, val topK: Int = candidates.size) : Question<Answer.Rank>
}

sealed interface Answer {
    val probabilities: Map<String, Double>                    // calibrated
    val confidence: Double                                    // p_top - mean(p_rest), as CLM/TypeSafe
    data class YesNo(val pTrue: Double, override val confidence: Double) : Answer {
        override val probabilities get() = mapOf("false" to 1 - pTrue, "true" to pTrue)
        val value get() = pTrue >= 0.5
    }
    data class Choice(val key: String, override val probabilities: Map<String, Double>, override val confidence: Double) : Answer
    data class Score(val expected: Double, val level: Int, override val probabilities: Map<String, Double>, override val confidence: Double) : Answer
    data class Rank(val order: List<Int>, override val probabilities: Map<String, Double>, override val confidence: Double) : Answer
}

enum class BackendId { RULES, CLM, BONSAI_LOGPROB, BONSAI_GENERATE }

data class DecisionMeta(
    val backend: BackendId, val modelId: String, val headId: String?, val calibrationVersion: String,
    val rawLogits: FloatArray, val latencyMs: Long, val cacheHits: Int,
)

sealed interface Decision<out A : Answer> {
    val meta: DecisionMeta
    data class Decided<A : Answer>(val answer: A, override val meta: DecisionMeta) : Decision<A>
    /** Below the kind's threshold; `best` is the calibrated guess, never acted on for irreversible steps. */
    data class Abstained<A : Answer>(val best: A?, val reason: AbstainReason, override val meta: DecisionMeta) : Decision<A>
    data class Failed(val error: DecideError, override val meta: DecisionMeta) : Decision<Nothing>
}
enum class AbstainReason { LOW_CONFIDENCE, NO_CALIBRATION, OUT_OF_BUDGET, BACKEND_UNAVAILABLE, INPUT_TOO_LONG }
sealed interface DecideError { data class Backend(val msg: String) : DecideError; data object Cancelled : DecideError }

data class DecidePolicy(val deadlineMs: Long = 1500, val allowBackends: Set<BackendId> = BackendId.entries.toSet())

interface Decider {
    suspend fun <A : Answer> decide(state: DecisionState, q: Question<A>, policy: DecidePolicy = DecidePolicy()): Decision<A>
    /** Several questions on one state share the context prefill (F6.6). */
    suspend fun decideAll(state: DecisionState, qs: List<Question<*>>, policy: DecidePolicy = DecidePolicy()): List<Decision<*>>
}

/** A backend returns raw per-option scores; calibration and thresholds live in the router, not here. */
interface DecideBackend {
    val id: BackendId
    fun supports(q: Question<*>): Boolean
    suspend fun rawScores(state: DecisionState, q: Question<*>): FloatArray   // one logit per option, option order
}
```

The router (`DecideRouter : Decider`) runs: rules → configured primary backend → `Calibrator.apply(kind, logits)` → threshold τ_kind → `Decided` or `Abstained`; it appends a `DecisionLogEntry` (kind, hashes, backend, raw and calibrated scores, latency; **no screen text**) to app-private storage. Backends switch at runtime through settings (owner decision "switchable at runtime").

**R2. Safety asymmetry (decided now).** `decide()` may **add** a confirmation or an escalation, never remove one: the lane-05 gate for irreversible actions is deterministic and does not consult `decide()` to skip itself. Screen text reaches `decide()` only inside `DecisionState.context`; instructions come from code, never from the screen. Adversarial screen text ("answer true") can still bias an embedding classifier, which is why no decide answer can authorise an irreversible step. **INFERENCE**

**R3. Agent-loop use (decided now; loop owned by lane 04).**
- per step, one `decideAll` on the current screen: `dialog.blocking` (YesNo), `task.done` (YesNo), `screen.expected` (YesNo: did the last action reach the expected screen; feeds lane 05);
- grounding: `ui.target` (Rank over candidate action texts such as "Tap button 'Send'"), whose top-k shortlist goes to Bonsai generate as a constrained menu;
- routing: `route.api_or_ui` (Choice) after the rules;
- `Abstained` → escalate to Bonsai generate with the shortlist; if still uncertain, or the step is irreversible, → owner on screen.

**R4. M1 backend (decided now): RULES + BONSAI_LOGPROB.** No second model, reuses the agent's KV cache, works with 8B and 27B. Calibrate per kind by temperature on the synthetic set; thresholds conservative until on-device outcomes accumulate. The CLM schema port (`to_text`, candidate texts, confidence formula) and the heads loader land in M1 as tested code (JVM golden tests against Python), but the CLM backend stays off.

**R5. M2 backend (deferred to measurement): CLM in shadow mode, then per-kind promotion.** Encoder choice by the fidelity test, in this order of preference: E4 Bonsai-8B state (no extra model) → E5 Bonsai + linear bridge → E3 Qwen3-8B Q4_K_M → E2 Q6_K (E1 Q8_0 only as the CI reference proxy). In shadow mode both backends are logged and the logprob backend acts; a kind is promoted to CLM when CLM's calibrated risk-coverage curve beats the logprob backend's on the operator decision set **and** on device. If Bonsai 27B becomes the planner, CLM needs either E4/E5 on a separately loaded Bonsai-8B or a second model; decide that after lane 02's RAM numbers.

**R6. CLM fidelity test (decided now as the design in F5).** G0–G5 gates, layer-streamed bf16 reference on the laptop or an Actions runner, quantised candidates on Actions, mandatory on-device re-run. Thresholds in F5.3 are defaults that lane 07 may tighten.

**R7. Heads (decided now):** safetensors export with a sha256 check, Kotlin fp32 first, native (ggml with `ggml_gelu_erf`, or NEON) only if measurement demands; fp16 weights only after passing G3/G4 vs fp32. Cache per F6.5.

Deferred to on-device measurement: the encoder for CLM (E3/E4/E5); whether CLM beats logprob per kind; the head compute path; per-kind temperatures and τ; `pooling=LAST` vs `NONE`+ith correctness.

## Interfaces this lane assumes from other lanes

- **02 inference (JNI):**
  - `tokenize(text, addSpecial=false, parseSpecial=false): IntArray` using the loaded model's vocab;
  - `embedLast(ctx, tokens, reusePrefix=true): FloatArray(4096)`: `embeddings=true`, post-final-norm `t_embd` of the last token, raw (unnormalised); a second llama context sharing the loaded Bonsai-8B weights for decide, so the agent's generation context is not disturbed (llama.cpp support for several contexts per model: **UNVERIFIED** here, lane 02 confirms);
  - `labelLogits(ctx, promptTokens, labelTokenIds): FloatArray` with KV prefix reuse/branching;
  - optional load/unload of a second GGUF (Qwen3-8B) on demand; the model file's sha256 as `modelId`;
  - RAM and prefill-speed numbers for Bonsai 8B/27B and Qwen3-8B Q4_K_M/Q6_K.
- **04 screen representation / loop:** a prose rendering of the screen (`to_text`-like, stable order, ≤ ~1,500 tokens, context first) and short prose candidate texts; the loop calls `decideAll` once per step and handles `Abstained` by escalation.
- **05 actions / safety:** the deterministic confirmation gate (R2); effect verification may call `decide(YesNo)` and returns outcome labels to the calibration log.
- **01 architecture / owner UX:** a settings switch for the decide backend; an on-screen owner escalation for `Abstained` on consequential steps.
- **06 control access:** nothing; decide needs no privileges.
- **07 evaluation / CI:** the operator decision set with gold labels per `DecisionKind`; CI jobs for the export script and the fidelity test (matrix jobs, per-job time limit); artefact storage for reference projections; the on-device test build that reports G2–G5 summaries.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Bonsai-8B 1-bit hidden state is too far from Qwen3-8B for the CLM heads (E4 fails) | Medium-high (INFERENCE) | CLM needs a second 5–6.7 GB model or adaptation | E5 bridge, O6 distillation; logprob backend stays primary |
| Wrong tensor or token mismatch (BOS, special tokens, truncation side, `LAST` over ubatches) silently degrades CLM | Medium | Plausible-looking but wrong decisions | G0/G1 gates before any accuracy number; `NONE`+ith path |
| Scale 100 amplifies small quantisation errors into large logit shifts | High (arithmetic) | Unstable decisions near ties | G3 |Δlogit| gate; per-kind temperature; abstain near ties |
| CLM zero-shot quality on phone-UI decisions is poor (benchmarks were fine-tuned, not phone UI) | Medium | CLM never promoted | Shadow mode, per-kind promotion, O6 fine-tune on our set |
| Two 8B-class models exceed what Android lets one app keep resident | Medium | LMK kills, slow reloads | Prefer E4/E5; load Qwen3 on demand; lane-02 RAM measurement |
| Bonsai 1-bit logprobs poorly calibrated or position-biased | Medium | Wrong accepts at τ | Per-kind temperature, permutation averaging, conservative τ, owner escalation |
| Prompt injection via screen text biases decisions | Medium | Wrong non-destructive steps | R2 asymmetry; rules first; instructions only from code |
| CLM release is a week old; heads or recipe may change | Medium | Cache/calibration invalidation | Pin sha256 (F1.3); `headId` in cache keys and calibration files |
| Reference E0 on CPU is slower than estimated | Medium | Fidelity result late | Smaller corpus for E0, Q8_0 proxy validated on a subset, split jobs |
| Decision logs hold sensitive context | Low (designed out) | Privacy | Log hashes and scores only; logs never leave the phone |

## Owner-only questions

1. **Second model on the phone.** If Bonsai-8B cannot drive the CLM heads (E4/E5 fail) but Qwen3-8B Q4_K_M passes, may the operator download and keep a second ~5.0 GB model (Q6_K 6.7 GB) on the phone, loaded on demand? Options: (a) yes Q4_K_M; (b) yes Q6_K; (c) no, logprob backend only. **Default: (a)**, and only after the fidelity and RAM measurements.
2. **Where the multi-hour reference run may use compute.** Options: (a) laptop CPU in the background (free, slow, uses up to ~5 GB of the ~11 GB free disk transiently); (b) GitHub Actions matrix jobs (free minutes only if the repo is public; a private repo uses the account's minutes and gets 2 vCPU / 8 GB runners); (c) both, split by corpus slice. **Default: (a)** for E0, Actions for E1–E6.
3. **Owner-initiated export of decision logs for calibration.** "Nothing leaves the phone" covers runtime; this asks whether you ever want an explicit, manual export of labelled decisions (scores and hashes, optionally screen text) for off-device recalibration or head fine-tuning. Options: (a) never; (b) scores and hashes only; (c) with text, manual and per export. **Default: (a) never**; calibrate on-device and off-device on synthetic/public sets only.

## On-device measurements needed (for M1/M2)

- M-1 Token parity (G0) with the Android build's tokenizer vs the HF tokenizer on the corpus.
- M-2 Fidelity G1–G5 of the chosen encoder(s) on device (E4 first, then E3), against shipped reference projections.
- M-3 Prefill throughput and decide latency (p50/p95) for state lengths of 256/512/1,024/2,048 tokens on Bonsai-8B and Qwen3-8B Q4_K_M; with and without prefix reuse.
- M-4 Head projection latency: Kotlin fp32 vs native, one vector and a batch of 32; fp16 weights vs fp32.
- M-5 RAM (PSS) and LMK behaviour: Bonsai only; Bonsai plus a second decide context; Bonsai plus Qwen3-8B Q4_K_M; foreground and background.
- M-6 Logprob backend: latency with KV reuse; ECE/Brier per kind on the decision set; position-bias rate (answer flips under option permutation).
- M-7 Cache hit rate for action and state projections over real sessions (counters only).
- M-8 Sustained-load thermals: decide throughput after 10 minutes of agent-loop use.
- M-9 `pooling=LAST` vs `NONE`+ith agreement on multi-ubatch states and with KV prefix reuse.

## Sources

1. https://huggingface.co/Contrastive-LM/CLM-v0.1-8B (model card, and `/raw/main/README.md`), accessed 2026-09-27
2. https://huggingface.co/Contrastive-LM/CLM-v0.1-8B/raw/main/config.json, accessed 2026-09-27
   2b. https://huggingface.co/api/models/Contrastive-LM/CLM-v0.1-8B (tags, created/modified dates), accessed 2026-09-27
3. https://huggingface.co/api/models/Contrastive-LM/CLM-v0.1-8B/tree/main (file sizes, LFS sha256), accessed 2026-09-27
4. https://huggingface.co/Contrastive-LM/CLM-v0.1-8B/resolve/main/CLM_v0.1-8B.pt: zip central directory, `qwen3_8b_posttrained_head/data.pkl` (disassembled with Python `pickletools`) and `data/16` (logit_scale) read via HTTP range requests, accessed 2026-09-27
5. https://github.com/Contrastive-LM/CLM (README and tree), accessed 2026-09-27
6. https://raw.githubusercontent.com/Contrastive-LM/CLM/main/src/clm/heads.py, accessed 2026-09-27
7. https://raw.githubusercontent.com/Contrastive-LM/CLM/main/src/clm/embedder.py, accessed 2026-09-27
8. https://raw.githubusercontent.com/Contrastive-LM/CLM/main/serve_qwen3_8b.sh, accessed 2026-09-27
9. https://raw.githubusercontent.com/Contrastive-LM/CLM/main/train/embed_utils.py, accessed 2026-09-27
10. https://raw.githubusercontent.com/Contrastive-LM/CLM/main/src/clm/schema.py, accessed 2026-09-27
11. https://raw.githubusercontent.com/Contrastive-LM/CLM/main/src/clm/engine.py, accessed 2026-09-27
12. https://raw.githubusercontent.com/Contrastive-LM/CLM/main/src/clm/client.py, accessed 2026-09-27
13. https://raw.githubusercontent.com/Contrastive-LM/CLM/main/src/clm/cache.py, accessed 2026-09-27
14. https://raw.githubusercontent.com/Contrastive-LM/CLM/main/train/finetune.py (via fetch summary, defaults not quoted verbatim), accessed 2026-09-27
15. https://raw.githubusercontent.com/Contrastive-LM/CLM/main/preprocessing/hf_embeddings.py, accessed 2026-09-27
16. https://huggingface.co/datasets/Contrastive-LM/deepswe-clm-train-embeddings-8k, accessed 2026-09-27
17. https://huggingface.co/datasets/Contrastive-LM/CLM-v0.1-Pretrain-Nemotron, accessed 2026-09-27
18. https://huggingface.co/api/datasets?author=Contrastive-LM, accessed 2026-09-27
19. https://huggingface.co/api/datasets/LocalLLaMA/typed-decisions, accessed 2026-09-27
20. https://docs.vllm.ai/en/latest/models/pooling_models.html, accessed 2026-09-27
21. https://raw.githubusercontent.com/ggml-org/llama.cpp/master/src/models/qwen3.cpp, accessed 2026-09-27
22. https://raw.githubusercontent.com/ggml-org/llama.cpp/master/include/llama.h, accessed 2026-09-27
23. https://raw.githubusercontent.com/ggml-org/llama.cpp/master/src/llama-context.cpp (via fetch summary), accessed 2026-09-27
24. https://raw.githubusercontent.com/ggml-org/llama.cpp/master/common/common.cpp, accessed 2026-09-27
25. https://raw.githubusercontent.com/ggml-org/llama.cpp/master/examples/llama.android/lib/src/main/cpp/ai_chat.cpp, accessed 2026-09-27
26. https://raw.githubusercontent.com/ggml-org/llama.cpp/master/ggml/include/ggml.h, accessed 2026-09-27
27. https://huggingface.co/api/models/Qwen/Qwen3-8B-GGUF/tree/main, accessed 2026-09-27
28. https://huggingface.co/api/models/Qwen/Qwen3-8B/tree/main, accessed 2026-09-27
29. https://huggingface.co/Qwen/Qwen3-8B/raw/main/tokenizer_config.json, accessed 2026-09-27
30. https://huggingface.co/prism-ml/Bonsai-8B-gguf, accessed 2026-09-27
31. https://github.com/PrismML-Eng/Bonsai-demo/blob/main/1-bit-bonsai-8b-whitepaper.pdf (§3–4 and evaluation setup; text extracted with pdftotext), accessed 2026-09-27
32. https://prismml.com/news/bonsai-27b and https://huggingface.co/prism-ml/Bonsai-27B-gguf (search-result snippets only, not fetched; basis of the UNVERIFIED Qwen3.6-27B lineage), accessed 2026-09-27
33. https://docs.github.com/en/actions/reference/runners/github-hosted-runners, accessed 2026-09-27
34. https://docs.pytorch.org/docs/2.14/generated/torch.nn.GELU.html, accessed 2026-09-27
35. https://docs.pytorch.org/docs/2.14/generated/torch.nn.LayerNorm.html, accessed 2026-09-27
36. Guo, Pleiss, Sun, Weinberger, "On Calibration of Modern Neural Networks", https://arxiv.org/abs/1706.04599, accessed 2026-09-27
37. Geifman, El-Yaniv, "Selective Classification for Deep Neural Networks", https://arxiv.org/abs/1705.08500, accessed 2026-09-27
    37b. https://scikit-learn.org/stable/modules/calibration.html (sigmoid vs isotonic, ~1000-sample guidance), accessed 2026-09-27
38. Kadavath et al., "Language Models (Mostly) Know What They Know", https://arxiv.org/abs/2207.05221, accessed 2026-09-27
39. /home/phaseonebig/brain/wiki/decisions/adr-0012-typed-decide-seam-jev-backend.md, accessed 2026-09-27
40. /home/phaseonebig/projects/operator-design/README.md, accessed 2026-09-27
41. https://openjdk.org/jeps/537 (Vector API, twelfth incubator; search result), accessed 2026-09-27
