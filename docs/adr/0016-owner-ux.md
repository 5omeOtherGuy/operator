# ADR-0016: Owner UX: command entry, progress, questions, confirmation surfaces

- **Status:** Proposed
- **Date:** 2026-09-27 (new, from review findings R3 and R17)
- **Design reference:** FOUNDATION §11 (and §9.2–§9.4, §9.6)

## Context

- The owner talks to operator in four ways: giving a task, watching it, answering its questions, and approving or stopping its actions. The earlier draft recorded these only as decisions, without options (review R17).
- **The owner channel must be unreachable for the agent (review R3).** RemoteInput fields on notifications render in SystemUI's shade or heads-up window, which the agent can open, read and type into. Owner input arriving that way carries no provenance, so the agent could start its own tasks, answer its own questions, widen its capability set or extend its budget.
- What the platform gives (lane 01 §F7): `TYPE_ACCESSIBILITY_OVERLAY` windows need no extra permission [01§F7.1]; data-sensitive views are hidden from a non-tool service, and AOSP r4 drops touches it injects on them [V9]; a QS tile can open an activity [01§F7.9]; `ROLE_ASSISTANT` must be set by the owner [01§F7.7]; on-device speech recognition on OxygenOS is unverified [01§F7.8].
- Scheduled or unattended tasks were neither designed nor excluded (review R17, lane 01 §F5.1).

## Options

**Command entry** [01§Options]:

| Option | Gives | Cost / risk |
|---|---|---|
| In-app command screen | Reliable; history, plan, live step log | The owner leaves the current app |
| Command sheet activity (compact, opened from the QS tile, the notification's *Open* action, the a11y button or a share) | Two swipes from anywhere; lands over the app the task concerns | An activity on top of the target app; the executor pauses while it is in front |
| RemoteInput on the FGS notification | Type from anywhere without leaving the app | **Agent-reachable** (R3) |
| RemoteInput + BiometricPrompt for widening and extensions | Keeps notification replies for plain answers | Plain answers and new tasks stay agent-reachable; complexity |
| Floating overlay bubble with text input | One tap, in place | IME in an a11y overlay unverified (`01-M7`); a text field in an overlay the agent's service owns |
| Voice (`VoiceInteractionService`, power long-press) | Hands-free | Owner sets the role; OxygenOS routing and on-device STT unverified (`01-M8`, `01-M9`) |
| Share target | "Do X with this" | Narrow |

**Progress:** notification text only; notification + a non-touchable status pill with a Stop chip while a task runs; an always-on pill.

**Questions to the owner:** heads-up notification with RemoteInput; heads-up notification that opens the in-app thread; a full-screen activity.

**Confirmation surfaces:** the a11y overlay card with physical approval (ADR-0011); a `GateActivity` with BiometricPrompt; the in-app pending-approvals screen.

## Decision

1. **The owner channel is operator's own activities only.** Goals, ASK_OWNER answers, capability widening and budget extensions are accepted only in the command screen, the command sheet, the ASK_OWNER thread and the pending-approvals screen. Their views are data-sensitive; the OSF drops their windows; the executor refuses all actions on them and acts on nothing while one is in front (ADR-0012).
2. **No RemoteInput on any notification** (M1–M3; the S-08 lint enforces it). Notification actions are only *Open* and *Stop*. Operator's own notifications are hidden from the agent (ADR-0008) and refused by the executor (ADR-0010).
3. **Command entry:** M1 = the command screen, the command sheet (from the QS tile and the notification's *Open*). M2 = the a11y button and a share target, both opening the sheet. M4 = voice if OQ-15 = (a) and `01-M8`/`01-M9` pass. The overlay bubble is not adopted.
4. **Progress:** the notification shows "Step n · <verb> <target>" and elapsed time; a non-touchable status pill with a small Stop chip **only while a task runs** (a Settings switch allows always-on or off); the pill is hidden during `takeScreenshot`.
5. **Questions:** a heads-up notification whose only actions are *Open* and *Stop*; the answer is given in the in-app thread.
6. **Confirmation:** the overlay card of ADR-0011, the GateActivity for fingerprints, and the pending-approvals screen as the fallback. One real-gesture gate check by the owner per milestone (OQ-12).
7. **Stop and escapes:** Stop chip, notification Stop, QS tile, volume-down ×3; Disarm by the a11y shortcut or Settings; safe mode; "Release device owner" (M2); all shown in onboarding (FOUNDATION §9.4, ADR-0013).
8. **Scheduled or unattended tasks are a non-goal for M1–M3.** Every task starts from an owner command on an unlocked phone. OQ-28 asks whether to design them for M4 (exact alarms can start a task; UI actions need an unlocked phone, and approvals need the owner present).

## Alternatives considered

| Alternative | Why not |
|---|---|
| RemoteInput for commands and answers (the earlier draft) | The agent can type into it through the shade (R3) |
| RemoteInput + BiometricPrompt only for widening and extensions | Still lets the agent start tasks and answer its own questions; kept as a possible later option if S-10 and the owner's feedback ask for it |
| Always-on pill | Screen clutter all day for a feature used in bursts; available as a setting |
| Overlay bubble with text input | Unverified IME behaviour; puts an owner text field in a window our own service draws |

## Consequences

- Starting a task always opens an operator activity, which covers the target app until the owner closes it; the agent then starts from the app underneath (the sheet closes before the first step).
- Answering a question takes one tap more than an inline reply.
- S-10 tests that no forged owner input is accepted.

## Evidence

- [01§F7], [01§Options], [01§R3].
- [V9] AOSP data-sensitive touch filter.
- REVIEW.md R3, R17.

## Open questions

- OQ-12 (owner time), OQ-15 (voice), OQ-28 (scheduled tasks).
- Measurements: `01-M7`, `01-M8`, `01-M9`; S-10; U46.
