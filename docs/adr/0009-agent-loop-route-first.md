# ADR-0009: Route-first agent loop with per-step grammar

- **Status:** Proposed
- **Date:** 2026-09-27
- **Design reference:** FOUNDATION §8 (conflicts C7, C10)

## Context

- Zero-shot 7–9B text models score 0–7 % on AndroidLab-style UI tasks [04§F29].
- API/CLI agents beat GUI agents: 71.8 % against 57.8–69.3 % on AndroidWorld, in 10.7 against 18.6 steps [04§F9].
- The owner decided "direct APIs first".
- V-Droid shows that an 8B verifier ranking enumerated candidates works, reaching 59.5 % [04§F7].

## Decision

1. **State machine:**
   - INTAKE → CAPS (capability set from the owner's words, before any screen text) → ROUTE → API path (API_ARGS → GATE → EXEC_API → VERIFY_API) or UI path (PLAN → OBSERVE → PROPOSE → GATE → ACT → SETTLE → VERIFY → PROGRESS);
   - exits: DONE, RECOVER, REPLAN, ASK_OWNER, ABORT, and PAUSED on inference death.
2. **Model split:**
   - Bonsai generates plans, API arguments, the action (**L1**: verb + index under the grammar) and answers; greedy under grammar, vendor sampling for free text (ADR-0006).
   - decide handles intent, route, effect and goal checks: RULES + BONSAI_LOGPROB in M1; RULES + CLM-8B selectable from M2, with logprob as the fallback (ADR-0007, review R2).
   - M3: **L3** hybrid. Bonsai proposes verb + target text, CLM-8B ranks elements, and Bonsai picks from the top 5 when the margin is low. Adopted only if `04-M2-2` beats L1.
3. **Output syntax (C10):**
   - The model emits short JSON verbs (`{"a":"tap","i":11}`).
   - The host stamps the snapshot id and maps the verb to a typed `ToolCall` (ADR-0010).
   - Raw coordinates and swipes are host-only.
4. **Per-step GBNF grammar:**
   - verb-specific index enums (tap only on clickable elements, type only on `edit`, scroll only on scroll containers);
   - an app enum limited to the task's app set;
   - bounded strings;
   - (state, action) pairs tried twice are removed from the grammar;
   - thinking off.
   - Keywords llama.cpp cannot express (such as `uniqueItems`) are dropped from the grammar and enforced by the executor (C11).
5. **Budgets (C7):**
   - 8 UI steps per subgoal;
   - 25 UI steps per task, then ASK_OWNER "10 more?";
   - a hard 60-step ceiling enforced by the executor.
6. **Loop detection:**
   - a full-state hash seen 3 times in 8 steps → RECOVER;
   - 4 steps with no achieved verify → REPLAN.
7. **Recovery ladder:** re-observe → scroll (at most 3) → back (not over unsaved input) → relaunch → REPLAN (at most 2) → ASK_OWNER → ABORT.
8. **Working memory:** deterministic history lines, compacted every 10 steps into a Bonsai note of ≤ 60 tokens.

## Alternatives considered

| Alternative | Why not |
|---|---|
| UI-only agent | Fewer successes and more steps than the API route [04§F9] |
| L2 pure verifier (CLM ranks about 50 candidates, no generator) | Needs CLM-8B on the phone, which arrives in M2, and about 50 candidate encodes per step; V-Droid's verifier was fine-tuned [04§F7] |
| LLM-written working memory every step | Doubles step time on the phone [04§F31] |
| Model writes snapshot ids and coordinates (lane 05 schema) | More tokens and a forgery surface; the host knows both |

## Consequences

- Most of M1–M2's usefulness comes from ROUTE. UI-path success is expected to be low (INFERENCE [04§R6]) and is measured on T1.
- Every generate call needs a grammar built at run time, whose overhead is measured by `04-M1-6`. Because the grammar makes parse failures rare, M1 records the **valid-and-executable action rate** (no grammar fallback, not refused as stale or invalid) instead of a parse-rate bar (review R20).
- **Latency (review R13, INFERENCE):** a UI step is about 28 s of generate at pp 30 (9 s at pp 100) plus 1.3–5.2 s of decide at pp 30; a 10–25-step UI task takes about 2–14 min, a direct-API task about 10–60 s. Task wall time p50 per tier is an M1 baseline (FOUNDATION §4.5).

## Evidence

- [04§F1–F31], [04§R2–R4], [04§R6].
- [05§R2] ToolCall schema.
- [03§R3] loop use of decide.

## Open questions

- OQ-9: limits, including the 25/60 budgets.
- Measurements: `04-M2-1`, `04-M2-2`, `04-M2-4`, `04-M2-6`.
