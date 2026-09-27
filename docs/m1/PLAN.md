# M1 build plan

Owner 2026-09-27 05:10: build the full M1 of `docs/design/FOUNDATION.md` §14; every OQ default accepted (`docs/design/OPEN-QUESTIONS.md`).
This file is the lead's slice table and the restart point. Later changes are append-only decision lines at the end.

## Rules

- One slice = one branch `m1/<id>-<slug>` = one worktree `~/projects/operator-<id>` = one writer = one PR against `main`.
- A slice touches only its owned paths. Shared files (table below) change only in F0, or by an append-only edit named in the slice brief.
- Android code builds only in GitHub Actions (no Android SDK on the laptop). `:agent-core` is pure Kotlin/JVM and runs locally: `./gradlew :agent-core:test` (JDK 17).
- Landing: CI green on the PR head, one independent review (GPT-6 Sol), the lead merges (squash).
- Design text is the specification. A slice cites the sections it implements; a conflict between design and code is reported, not improvised.

## Slices

| id | slice | owns | needs | design |
|---|---|---|---|---|
| F0 | Foundation: Gradle modules, CI, manifests with every M1 component stubbed, AIDL, frozen `:agent-core` types and ports | everything below as stubs | — | §2, §4.2, §5.1, §7.1–7.2, §8.1, ADR-0002/0005/0015 |
| S1 | `:llm` native: patch set P1–P3, `operator_jni.cpp`, `LlmService` (load+allowlist+sha256, tokenize, generate GBNF + prefix reuse, labelLogits, abort, pid, state, stats, bench); emulatorStub fake | `llm/**`, `llm-api/**` impl | F0 | §2.3, §4, ADR-0005/0006 |
| S2 | OSF v0 serializer over `UiNode` | `agent-core/.../osf/**` | F0 | §6, ADR-0008 |
| S3 | Policy + executor core: validation order, risk classes, irreversibility lexicon, taint, rate limits, budgets | `agent-core/.../policy/**`, `agent-core/.../executor/**` | F0 | §7, §9.3, ADR-0010/0012 |
| S4 | decide(): router, RULES, calibration, thresholds, decision log, BONSAI_LOGPROB over `LlmPort` | `agent-core/.../decide/**` | F0 | §5, ADR-0007 |
| S5 | CLM-8B port (schema, safetensors heads, fp32 reference, JVM goldens) + G0–G3 Actions workflow | `agent-core/.../clm/**`, `tools/clm/**`, `.github/workflows/clm-gates.yml` | F0 | §5.3–5.4, ADR-0007 |
| S6 | Agent loop: state machine, per-step grammar, budgets, loop bans, recovery, ASK_OWNER | `agent-core/.../loop/**`, `agent-core/.../grammar/**` | F0 | §8, ADR-0009 |
| S7 | Hands: `ScreenService` tree → `UiNode`, gestures, key filter, overlay host | `app/.../hands/**` | F0 | §2.4, §6, §7.5, ADR-0001/0004 |
| S8 | Gate + kill switch: overlay card, volume hold-release, BiometricPrompt, approval tokens, K-a…K-e | `app/.../gate/**`, `app/.../kill/**` | F0 (S7 interfaces are F0's) | §9.2, §9.6, ADR-0011/0013 |
| S9 | T0 direct-API adapters + NotificationListener | `app/.../adapters/**` | F0 | §7.2, ADR-0010 |
| S10 | Lifecycle + audit: AgentService FGS, exact-alarm watchdog, Keeper, boot receivers, hash-chained audit log | `app/.../lifecycle/**`, `app/.../audit/**`, `agent-core/.../audit/**` | F0 | §2.5, §9.5, ADR-0003/0013 |
| S11 | Owner UX: setup checklist + self-check, command screen/sheet, QS tile, status pill, settings | `app/.../ui/**` | F0 | §11, ADR-0016 |
| S12 | Eval: EvalReceiver, EvalLog, ReplayRunner (dev source set), `:fixture` APK, laptop driver + scorer, `op-replay-v0`, `ac-sub-v0` | `app/src/dev/**`, `fixture/**`, `tools/eval/**` | F0 | §12, ADR-0015 |
| S13 | Stage 0: `llama-bench` arm64 + upstream demo APK as CI artifacts; phone scripts that write `metrics.json` | `tools/stage0/**`, `.github/workflows/stage0.yml` | F0 | §14 Stage 0 |
| I1 | Integration: composition root in `:app`, wiring of S1–S12, emulator job (emulatorStub) running the CI safety suite | `app/.../OperatorApp.kt`, `app/.../di/**`, emulator workflow | S1–S12 | §12, §14 |
| M | Phone: runbook §10.1 install, measurements, exit criteria, `metrics.json` under the M1 tag | `metrics/**` | I1 | §10, §14 |

S2–S6 are pure JVM (local tests). S1, S7–S13 are Android (CI feedback). All of S1–S13 start when F0 lands.

## Shared files (F0 owns; slices append only where their brief says so)

`settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `*/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/**`, `.github/workflows/build.yml`, `agent-core/.../api/**` (frozen types and ports), `llm-api/**/*.aidl`.
A slice that needs a change to a frozen type or AIDL reports it to the lead; the lead decides and records a decision line here.

## Decision lines
