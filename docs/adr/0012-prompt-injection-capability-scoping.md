# ADR-0012: Prompt injection: owner channel, capability scoping, taint and the sensitive denylist

- **Status:** Proposed
- **Date:** 2026-09-27 (revised after review findings R3, R4, R12, R24)
- **Design reference:** FOUNDATION §9.3 (conflict C8)

## Context

- The model reads untrusted web pages, notifications and messages.
- Measured attack success rates on mobile agents are 40–93 % [05§F6].
- Spotlighting and datamarking help large models, but their effect on a 1-bit 8B model is unknown [05§F6].
- System-level designs (CaMeL, Plan-Then-Execute) give guarantees that do not depend on the model [05§F6].
- **Review R3:** in the earlier draft, owner input could arrive by RemoteInput on operator's notifications, which render in SystemUI's shade. The agent can open the shade and type there, so it could start its own tasks or widen its own capabilities.
- **Review R4:** taint covered only R2/R3 arguments with a 20-character threshold, so short codes could be typed into other apps' networked fields and submitted with an R1 tap.

## Decision

1. **Goals come only from the owner channel, which the agent cannot reach (R3).**
   - The owner channel is operator's own activities: the command screen and command sheet, the ASK_OWNER thread, pending approvals, Settings. Their windows are dropped from the OSF, the executor refuses every action on them and acts on nothing while one is in front, and their views are data-sensitive, so the framework drops injected touches [V9].
   - **No RemoteInput** on any operator notification (the S-08 lint fails on it). Notification actions are only *Stop* and *Open*.
   - Operator's own notification rows are removed from the OSF and from `list_notifications`; the executor refuses actions on them and any `reply_notification`/`notification_action` on a `dev.operator` key.
   - Screen text enters the prompt only as escaped, quoted OSF labels. The static prefix declares quoted strings to be data.
2. **Capability set before reading:**
   - CAPS fixes the tool subset and the app set from the owner's utterance, using rules plus `decide()` of kind `task.intent` (R24), before any screen text is read.
   - The model cannot widen it. Widening and budget extensions need ASK_OWNER and an answer given in an operator activity.
   - The `open` grammar enum and the executor both enforce the app set.
3. **Taint (widened per R4 and R12).** Untrusted observations are screen text of other packages and windows, notifications, and web or fixture content. A value is tainted when it contains, after whitespace and separator normalisation, a span seen in an untrusted observation of this task but absent from the owner's utterance: any substring ≥ 8 characters, any run of ≥ 4 digits, or an OTP-like code.
   - Tainted R2/R3 arguments (recipient, number, URL-like string, package, message body) get a red "from the screen of <pkg>" line and are never auto-approved.
   - A tainted `type` is itself R2.
   - Taint stays on the field: a later submit of it (IME action, search icon, suggestion row, send or form button) is R2 with the taint line.
   - Copy, cut, paste and clipboard access are F (no tool).
   - Under the OQ-24 default (b), every submit in a browser address or search bar or a web form is R2 even for untainted text (ADR-0010).
4. **Rendering:** the gate shows executor-rendered typed arguments, never model prose. For UI-path taps it says *Tap "<label>" in <app label>* and marks screen-derived context as such (ADR-0011).
5. **Sensitive denylist**, enforced on read (`[hidden: sensitive app]`) and on write (refused):
   - banking, payment, authenticator and password-manager apps;
   - Play purchase flows;
   - account settings;
   - permission controller and package-installer UI;
   - sensitive Settings subpages, named explicitly: reset options, factory reset, app info (force stop, uninstall, clear storage), developer options, accessibility, device admin and special app access, security and lock screen, accounts;
   - operator itself.
   - Uninstall, hide and suspend additionally exclude core system apps [05§R5].
   - Lane 04's "read banking apps with every action gated" is **not** adopted (C8).
6. **OTPs are redacted in every OSF source** (app windows, the shade, heads-up windows, `list_notifications`), not only where Android 15 redacts them for untrusted listeners [06§F6][REV 13]. OQ-8 governs the exception.
7. **Model-side aids:** provenance tags on every observation. Delimiting and datamarking are adopted only if `05-M1-S1` shows they do not hurt Bonsai's task success.
8. **Residuals, put to the owner:** injected text can steer R1 navigation inside the task's apps; a silent irreversible tap outside the high-risk contexts remains possible (OQ-23); text the model rephrases or remembers, rather than copies, is not tainted and could leave through another app's UI (OQ-24).

## Alternatives considered

| Alternative | Why not |
|---|---|
| Prompt-only defences ("ignore pop-ups") | Ineffective in the literature [05§F6] |
| RemoteInput with BiometricPrompt for widening and extensions | Keeps an input path the agent can type into; the only gain is convenience. Rejected for M1–M3; ADR-0016 keeps it as a later option. |
| Dual-LLM / quarantined reader | Large loop change. Revisit at M3 with CLM-8B on the phone. |
| Read banking apps with gating (lane 04) | An extra injection surface; conflicts with "payments forbidden"; banking apps may also refuse to run while a non-Play a11y service is active [06§F9] |

## Consequences

- A wrong, too-narrow capability set fails safe and asks the owner.
- Some legitimate tasks need one extra owner answer to widen the set, given in the app.
- More gate cards when the agent types text it has seen (for example copying an address from a message into Maps).
- S-01…S-03 measure the attack rate split into attempted, reached the gate and executed; S-10 measures owner-channel forgery and S-11 UI-path exfiltration. The executed count must be 0.

## Evidence

- [05§F6], [05§R4], [05§R5].
- [04§R1.5], [04§R4].
- [06§F6], [06§F9]; [REV 13] Android 15 OTP redaction for listeners.
- [V9] AOSP data-sensitive touch filter.
- [07§R2] S tier.

## Open questions

- OQ-5 (payments), OQ-6 (denylist contents), OQ-8 (OTP codes), OQ-23, OQ-24 (residuals).
- Measurement: `05-M1-S1`; U46.
