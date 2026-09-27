# Architecture decision records: operator

Numbered from 0001 without gaps. ADR-0001 is the owner's decision. All others are **Proposed** by the foundation design (`docs/design/FOUNDATION.md`, 2026-09-27) and need the owner's review. They were revised after the adversarial review (`docs/design/REVIEW.md`, disposition table at its end); ADR-0016 was added then.

**Citation keys**, as in FOUNDATION.md:
- `[NN§X]` = `docs/design/research/NN-*.md`, section X;
- `[Vn]` = the sources listed at the end of FOUNDATION.md;
- `[REV n]` = source n in `docs/design/REVIEW.md` §4; `Rn` = review finding n;
- `OQ-n` = `docs/design/OPEN-QUESTIONS.md`.

**Changes:** once an ADR is accepted, later changes are append-only decision lines, not rewrites.

| ADR | Title | Status | Date | FOUNDATION § |
|---|---|---|---|---|
| [0001](0001-control-stack-baseline.md) | Control stack baseline: accessibility service + WRITE_SECURE_SETTINGS + device owner | Accepted (owner) | 2026-09-27 | 1, 3 |
| [0002](0002-process-layout-main-and-llm.md) | Process layout: Kotlin main process + one native `:llm` process | Proposed | 2026-09-27 | 2 |
| [0003](0003-lifecycle-keep-alive-and-self-heal.md) | Lifecycle: specialUse FGS, Keeper self-heal, device-owner anchors | Proposed | 2026-09-27 | 2.5 |
| [0004](0004-access-beyond-baseline.md) | Access beyond the baseline (answer to op13 addendum 2) | Proposed | 2026-09-27 | 3 |
| [0005](0005-inference-runtime-and-model-delivery.md) | Inference runtime: upstream llama.cpp CPU first, on the upstream `llama.android` lib (extended), no INTERNET permission | Proposed; JNI base pending OQ-21 | 2026-09-27 | 4.1, 4.2, 4.8 |
| [0006](0006-model-residency-memory-and-caching.md) | Model residency, memory budget and prompt/KV caching | Proposed | 2026-09-27 | 4.3–4.7 |
| [0007](0007-typed-decide-seam.md) | Typed `decide()` seam, backends by milestone, CLM fidelity gates | Proposed | 2026-09-27 | 5 |
| [0008](0008-screen-format-osf-v0.md) | Screen representation "OSF v0" | Proposed | 2026-09-27 | 6 |
| [0009](0009-agent-loop-route-first.md) | Route-first agent loop with per-step grammar | Proposed | 2026-09-27 | 8 |
| [0010](0010-typed-executor-and-risk-classes.md) | Typed executor, tool catalogue, risk classes and effect verification | Proposed | 2026-09-27 | 7 |
| [0011](0011-confirmation-gate-physical-approval.md) | Confirmation gate with physical approval | Proposed | 2026-09-27 | 9.2 |
| [0012](0012-prompt-injection-capability-scoping.md) | Prompt injection: owner channel, capability scoping, taint and the sensitive denylist | Proposed | 2026-09-27 | 9.3 |
| [0013](0013-kill-switch-and-audit-log.md) | Kill switch, rate limits and audit log | Proposed | 2026-09-27 | 9.5–9.6 |
| [0014](0014-provisioning-signing-and-variants.md) | Provisioning, signing key and build variants | Proposed | 2026-09-27 | 10, 12 |
| [0015](0015-evaluation-ci-and-milestones.md) | Evaluation harness, CI job graph and milestones | Proposed | 2026-09-27 | 12, 14 |
| [0016](0016-owner-ux.md) | Owner UX: command entry, progress, questions, confirmation surfaces | Proposed | 2026-09-27 | 11 |

The brain's ADR-0012 (typed decide seam, `/home/phaseonebig/brain/wiki/decisions/adr-0012-typed-decide-seam-jev-backend.md`) is an external reference. It is not part of this series.
