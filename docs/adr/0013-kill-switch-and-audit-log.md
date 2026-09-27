# ADR-0013: Kill switch, rate limits and audit log

- **Status:** Proposed
- **Date:** 2026-09-27 (revised after review findings R6, R18, R19)
- **Design reference:** FOUNDATION §9.5–§9.6 (conflict C6)

## Context

**How stops behave:**
- A real touch cancels any in-flight injected gesture [05§F1].
- Disabling the service tears down the gesture injector [05§F1].
- llama.cpp's abort callback works only on the CPU [05§F5].
- The upstream engine cannot cancel prefill [01§F1.8].

**Conflict:** lanes 01 and 05 both proposed the both-volume-keys hold, one for an in-app Disarm and one for the system accessibility shortcut.

## Decision

1. **Stop task (soft).**
   - Triggers: the Stop chip on the status pill, the notification Stop action, a QS tile, and volume-down ×3 within 1 s through the a11y key filter.
   - Effect, in order:
     1. `halted` is set and pending approvals are voided;
     2. the task `SupervisorJob` is cancelled;
     3. `abort(req)` runs (per-token flag + CPU abort callback during prefill);
     4. if `:llm` is not idle within 2 s, the **main process kills it by PID** (`Process.killProcess`, same UID; the PID comes from the AIDL `pid()` call at bind time) and unbinds. A wedged `:llm` cannot block this, unlike the earlier `killSelf()` over AIDL (R18);
     5. an audit line is written.
   - While a gate card is up, volume-down ×3 voids the card (ADR-0011) before stopping the task.
2. **Disarm (hard).**
   - Triggers: the system accessibility shortcut (both volume keys) bound to operator's service through WSS (OQ-10), Disarm in the app, or the a11y switch in system Settings.
   - Effect: the OS switches the service off. `ScreenService.onUnbind`/`onDestroy` sets `armed=false` and voids pending approvals **in the main process at once**, so an already-approved R2 API call cannot race the Keeper's `ContentObserver` (R18). The Keeper records Disarm and does not re-enable it. The executor refuses everything. A Disarm chord while a card is up voids the card (ADR-0011).
   - **Shortcut pressed while disarmed (R18):** the shortcut is an OS toggle and switches the service back on. `onServiceConnected` finds `armed=false`, stays inert, notifies "operator is still disarmed; re-arm in the app", and the Keeper switches it off again through WSS.
   - **Re-arm** is in the app only, and audited.
3. **Executor checks** the `armed`/`halted` flags right before every gesture, node action and API call. Gestures are capped at 1000 ms. **Safety does not depend on how fast inference stops.**
4. **Rate limits** (defaults, OQ-9): UI ≤ 3/s; ≤ 60 steps per task; SMS ≤ 5/h and ≤ 20/day; calls ≤ 5/h; install/uninstall ≤ 3/day; permission/hide/suspend ≤ 10/day; the same R2 call at most once per 60 s. Exceeding a limit returns `Refused("rate")` and asks the owner.
5. **Audit log:**
   - append-only JSON lines, hash-chained, in credential-encrypted storage of the main process;
   - an intent line before each act and a result line after, so unfinished R2/R3 steps are reported on restart and **never auto-retried**;
   - fields: ts, taskId, step, tool, canonical args (full for R2/R3 per OQ-3), class, decision (including `voided` with its cause), ToolResult, sha256 of observation and model output, process exit reasons;
   - **scope beyond agent actions (R19):** owner Settings actions (Disarm, Re-arm, edits to limits, the denylist and the decide backend, audit export, Release device owner); every Keeper WSS write and automatic re-enable; every DO policy (re-)application; model imports with their sha256;
   - **anchor (R19):** the chain head hash goes into the 180-day R2/R3 file at each rotation and is shown to the owner as an 8-character fingerprint in the audit viewer;
   - retention: 5 × 10 MB rotation, R2/R3 lines kept 180 days;
   - excluded from backup; no tool can read it; the owner views and exports it in Settings.
   - **Limitation (R19):** the `dev` build is debuggable, so `adb shell run-as dev.operator` can rewrite the log and its chain. Until the M4 release build the log is tamper-evident against the agent only, not against someone with adb.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Both-volume hold as an in-app Disarm (lane 01) | The same gesture as the system shortcut. The OS-level path works even when our code misbehaves, so it wins. |
| Relying on inference abort to stop actions | GPU backends ignore the abort; stopping actions must not depend on inference [05§F5] |
| Full screen text in the audit log | Private messages; OQ-3 offers a separate encrypted ring buffer instead |

## Consequences

- Binding the a11y shortcut replaces any existing shortcut target, such as TalkBack (OQ-10).
- A volume-down triple press changes the volume three steps when no card is up. The key is consumed only while a card is up (INFERENCE; `05-M1-K1` checks OxygenOS chord conflicts).
- The owner's escapes without adb (Stop, Disarm, safe mode, Release device owner, factory reset) are listed in FOUNDATION §9.4 and shown in onboarding.

## Evidence

- [05§F1], [05§F5], [05§R6], [05§Kill switch options].
- [01§R3].
- [07§R2] S-06; FOUNDATION §12 S-12.

## Open questions

- OQ-3 (log content), OQ-9 (limits), OQ-10 (shortcut binding).
- Measurements: `05-M1-K1`, `05-M1-K2`, `05-M1-K3`, `07-E-08`.
