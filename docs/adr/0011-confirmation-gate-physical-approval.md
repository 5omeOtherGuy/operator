# ADR-0011: Confirmation gate with physical approval

- **Status:** Proposed
- **Date:** 2026-09-27 (revised after review findings R6, R7, R12)
- **Design reference:** FOUNDATION §9.2 (conflicts C4, C5)

## Context

The owner requires on-screen confirmation for irreversible actions. The gate must not be pressable by operator's own accessibility service, and approval should show that the owner actually looked at the card.

**Lane 01** proposed an a11y overlay card approved by holding volume-down [01§R3].

**Lane 05** proposed a gate activity in a `:gate` process, approved by a tap, with these protections [05§R1][05§R5]:
- data-sensitive buttons, hidden from a service declared `isAccessibilityTool=false`;
- touches carrying flag 0x800 dropped;
- an HMAC approval.

What is verified:
- 0x800 marks accessibility-injected motion and key events [05§F2]; the Java constant is `@hide @TestApi` with value 0x800 [V1]. AOSP's gesture injector sets `FLAG_INJECTED_FROM_ACCESSIBILITY` on dispatched gestures [V9].
- **Corrected per review R7:** the lanes said data-sensitivity does not block coordinate taps. In AOSP `android-16.0.0_r4`, `View.onFilterTouchEventForSecurity` returns false for a touch that `isInjectedFromAccessibilityService()`, is not from an accessibility tool, and lands on a view where `isAccessibilityDataSensitive()` holds; views with `filterTouchesWhenObscured` and descendants of sensitive views count as sensitive (VERIFIED [V9]; the Android blog says the same: non-tool services are denied "performing interactions" on such views [V14]). So a data-sensitive tap button is protected by the framework, not only by an app-side check. Whether OxygenOS keeps this is unmeasured (`05-M1-G1`).
- An a11y service can consume hardware keys in `onKeyEvent` [05§F1]; keys the system does not pass to the user never reach it (`AccessibilityInputFilter` L416 [REV 16c]).
- An a11y service has no API to inject hardware keys (INFERENCE from the API surface [05§F1]). Its `InputConnection.sendKeyEvent` reaches only the focused app's view [V2].
- **Review R6:** a plain "hold volume-down ≥ 1 s" is how people lower the volume without looking, the agent can provoke it by starting loud media, and the same key carries the soft stop and, with volume-up, the Disarm chord.

## Decision

1. **Trigger:** the executor returns `NeedsConfirmation` for R2/R3, and the loop blocks.
2. **Card:**
   - a `TYPE_ACCESSIBILITY_OVERLAY` card drawn by operator;
   - it shows the executor-rendered typed arguments and never model prose, the app label from PackageManager, a red taint line when a value came from the screen, and a red warning for unknown numbers;
   - **UI-path taps (R12):** the card says only *Tap "<label>" in <app label>*; recipient, conversation title and field contents appear beneath, marked *from the screen*;
   - its views are `accessibilityDataSensitive=YES`, so they are hidden from our service and injected touches on them are dropped [V9];
   - it is excluded from the OSF;
   - the executor refuses gestures inside operator windows and locks all actions while a card is up.
3. **R2 approval = press, hold and release volume-down**, read in `onKeyEvent` (every volume key is consumed while a card is up). Approval fires **on key-up**, never on a timer, only if:
   - key-down came ≥ 1 s after the card appeared and the hold lasted **1.0–2.5 s**;
   - no other key went down during the hold;
   - the screen was interactive throughout and the proximity sensor read "far" (skipped and recorded if the phone exposes none to apps, U39);
   - no media was playing and operator issued no `media play` in this task in the last 60 s;
   - no key event carries the accessibility flag.
   If the media or proximity condition fails, the card asks for the fingerprint instead (Decision 4's `GateActivity`). The executor does not use `sendKeyEvent` in M1.
4. **R3 approval** = the hold, then a transparent `GateActivity` with `BiometricPrompt` `BIOMETRIC_STRONG` and no device-credential fallback. The GateActivity uses `setFilterTouchesWhenObscured` and `setHideOverlayWindows(true)`.
5. **Approval token (extended per R12):**
   - `HMAC(K_boot, taskId‖step‖sha256(canonicalArgs)‖screenSignature‖sha256(editedFieldContents)‖nonce)`, single-use, valid 30 s; `screenSignature` = (package, window title, structural hash);
   - `K_boot` is random per process start and kept in memory;
   - just before acting, the executor re-reads the screen and verifies the token, the argument hash, the screen signature and the edited-field contents, then re-resolves the target by key and label (TOCTOU). An approved Send cannot fire in a different chat with the same layout.
6. **Reject and void:** reject by a tap on Reject or a 60 s timeout. **Any volume-up press, a volume-down hold longer than 2.5 s, or both volume keys down together** (the Disarm chord) void the card: rejected and audited. A panicked Disarm therefore rejects. The soft-stop pattern (three short presses) never approves and stops the task. Never auto-approve.
7. **Fallback** when keys are not delivered (lock screen, in a call): the task pauses; the owner approves in the app's pending-approvals screen with BiometricPrompt.
8. **Attention-bound second factor (re-decided with R7, OQ-4 (d)):** tap a data-sensitive "Approve" button placed at a random one of three positions (its a11y delegate refuses `ACTION_CLICK`; 0x800 touches are refused as a second check), then the volume-down hold. It needs the owner to look at the card, and forging it would need both a hardware key and a framework-blocked tap. Offered from M2 if `05-M1-G1` confirms on OxygenOS that dispatched taps on data-sensitive views are dropped.
9. **`isAccessibilityTool=false`** (ADR-0004).
10. **No `:gate` process in M1.** The main process has no native code and no path that runs model-chosen code; token binding gives the replay and TOCTOU protection.

## Alternatives considered

| Alternative | Why not (now) |
|---|---|
| Tap approval alone, relying on data-sensitivity (lane 05 G-c) | Now platform-enforced in AOSP [V9], but OxygenOS behaviour is unmeasured, and a tap alone does not stop an agent if the OEM changed the filter. Kept as the second factor (Decision 8), not as the only factor. |
| Hold ≥ 1 s on a timer after key-down (the earlier draft) | Fires on inattentive volume adjustments and during the Disarm chord; unspecified behaviour with other keys (R6) |
| Random press count shown on the card | Collides with the soft-stop pattern (volume-down ×3) |
| Gate activity in a `:gate` process (lane 05 G-b) | Covers the target app, which changes the screen the agent acts on. The process boundary adds no guarantee over a Kotlin-only main process plus token binding. Revisit if a threat needs it. |
| BiometricPrompt for every R2 (OQ-4 option b) | Friction on frequent SMS and calls; offered to the owner |
| Executor lock only (G-a) | Does not stop a UI tap on a gate button |

## Consequences

- The agent cannot approve: it can inject neither a hardware key nor, on AOSP, a touch on the card's data-sensitive views.
- Tapjacking by other overlays cannot approve anything.
- Volume-down behaviour changes only while a card is visible; with media playing, approval needs the fingerprint.
- OxygenOS chords, proximity-sensor availability and key delivery during media are unmeasured (`05-M1-K1`, U39). S-12 tests the abuse gestures.
- The agent also cannot tap other apps' data-sensitive views (including views with `filterTouchesWhenObscured`), which shapes the T-suite fixtures.

## Evidence

- [05§F1–F2], [05§R1], [05§R5].
- [01§F7.1–F7.5], [01§R3].
- [V1] KeyEvent flag; [V2] a11y InputConnection; [V9] AOSP r4 data-sensitive touch filter; [V14] Android blog; [REV 16c] `AccessibilityInputFilter`.

## Open questions

- OQ-4 (approval levels, including (d)), OQ-7 (unknown numbers), OQ-12 (manual gate check per milestone).
- Measurements: `05-M1-G1` (with the injected-key add-on and the data-sensitive tap drop), `05-M1-G2`, `05-M1-K1` (with the proximity sensor), `01-M7`; S-05, S-12.
