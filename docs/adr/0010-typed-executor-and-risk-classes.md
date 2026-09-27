# ADR-0010: Typed executor, tool catalogue, risk classes and effect verification

- **Status:** Proposed
- **Date:** 2026-09-27 (revised after review findings R3, R4, R5, R12, R24)
- **Design reference:** FOUNDATION §7

## Context

- Prompt injection succeeds 40–93 % of the time against stronger agents, and prompting defences are unreliable [05§F6]. The boundary therefore has to be code.
- DO power (install, grants, suspend) and `WRITE_SECURE_SETTINGS` reach far beyond what any task needs [05§F4].
- A `performAction` return value only means the action was delivered, not that it had its effect.
- The owner requires an on-screen gate for irreversible actions. A verb word list plus a model guess leaves common irreversible taps ungated: dialog OK, smart replies, reactions, share-sheet targets, icon-only send buttons, Settings resets (review R5).
- "Nothing leaves the phone" must also hold when the agent types into other apps' networked UIs (review R4), and the agent must not be able to reach operator's own notifications (review R3).

## Decision

1. **Kotlin sealed `ToolCall` classes are the source of truth.** The JSON Schema and the GBNF are generated from them. Parsing is strict.
2. **Risk classes** [05§R2]:

   | Class | Meaning | Examples |
   |---|---|---|
   | R0 | Read-only | read_screen, list_notifications (operator's own excluded), next_alarm, calendar_query, ask_owner, finish |
   | R1 | Reversible: automatic, rate-limited, audited | UI verbs on targets Decision 3 leaves at R1, untainted `type`, global nav, lock_screen, media, launch_app, set_alarm/timer, dismiss_alarm, calendar insert without attendees, torch |
   | R2 | Irreversible: physical-hold gate | send_sms, call, reply_notification/notification_action (never on a `dev.operator` key), calendar with attendees or delete, headset_hook, hide/suspend (M2), permission deny (M2), UI taps Decision 3 classes R2, tainted `type` and any submit of tainted text |
   | R3 | Irreversible, high-power: hold + BiometricPrompt STRONG | install (owner-picked URI only), uninstall, permission grant, reboot (M2) |
   | F | Forbidden: no tool exists | open_url and arbitrary intents, power dialog, global screenshot, a11y shortcut and button actions, `ACTION_COPY`/`ACTION_CUT`/`ACTION_PASTE` and any clipboard access, raw Settings/DPM setting calls, wipeData/wipeDevice, removeUser, transferOwnership, clearDeviceOwnerApp, keyguard, system updates, user restrictions, certificates, VPN/proxy/DNS, permission policy, delegation, self-protection policies, `ADB_*` |

3. **UI targets are classed by target and context, not by tool (revised per R5):**
   - a denylisted package or Settings subpage → refuse;
   - `android:id/button1`, any button of a dialog window, share-sheet targets, smart-reply chips and reactions → R2;
   - **high-risk contexts** (messaging, email and social apps; Settings; `web` windows with an `edit` field; any dialog) → R2 unless the target is on the **navigation allowlist** (up/back/close, tabs, scroll/expand/collapse, the overflow button and menu items that open a screen, focusing an `edit`, and per-package navigation rules seeded for the T-suite apps and the owner's top apps);
   - EN+DE irreversible lexicon hit → R2. The lexicon adds OK, yes/ja, continue/weiter, done/fertig, apply/anwenden, reply/antworten, share/teilen, forward/weiterleiten, archive, discard, clear, erase, reset, block, report, unsubscribe, sign out/log out, force stop, uninstall, disable, leave, end, and icon-only buttons beside a filled `edit` (FOUNDATION §7.3);
   - `decide(action.irreversible)` at or above its threshold → R2;
   - otherwise R1. `decide()` can only raise the class.
4. **Typed text (revised per R4, R12):** a `SetText` whose text is tainted (a ≥ 8-character span, a ≥ 4-digit run or an OTP-like code seen in an untrusted observation of this task and absent from the owner's utterance) is R2 with a taint line. Taint stays on the field, and any later submit of it (IME action, search icon, suggestion row, send or form button) is R2. Under the OQ-24 default (b), every submit in a browser address or search bar or a web form is R2 whether or not the text is tainted.
5. **Validation order:** strict parse → full schema → snapshot generation → element valid (copy/cut/paste refused) → **owner channel protected** (host coordinates outside operator windows; not an operator notification row, action or reply field; no `dev.operator` notification key; nothing while an operator activity is in front) → package in the task's app set and not denylisted → emergency and short-code numbers refused → text limits, no password fields, taint → rate limits → armed/halted → class → gate. For an approved call, the token's screen signature and edited-field contents are re-checked just before acting (ADR-0011).
6. **Verification contract:** `pre → act → wait → post → ToolResult` (Ok / Failed / Unverified / Refused / NeedsConfirmation / Cancelled). `Ok` requires positive evidence:
   - UI: a reaction event, then idle with `waitForIdle` semantics excluding our own events, then a snapshot diff;
   - API: read-backs (next alarm, calendar `_ID`, SMS `sentIntent`, call state, playback state, install status, grant state).
   - Three `Unverified` results in a row → ask the owner.
7. **Module rule:** only executor and adapter packages may call gesture, node-action, DPM, SMS or telephony APIs. CI enforces it, and the S-08 lint also fails on clipboard APIs, `ACTION_COPY/CUT/PASTE` and `RemoteInput`.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Untyped free-text tool calls | Nothing to validate or gate [05§F6] |
| Raw coordinate tools for the model | Can aim at any pixel, including operator windows; coordinates stay host-only |
| Generic `open_url` / intent tool | Exfiltration and side-effect channel [05§R2] |
| Trust `performAction` results | Delivery is not effect [05§R3] |
| Lexicon + decide only (the earlier draft) | Misses common irreversible taps; accepted a residual without the owner (R5) |
| R2 for every tap outside the navigation allowlist in every app | Safest, but a card on most taps; offered to the owner as OQ-23 (b) |
| Taint only on R2/R3 arguments with a 20-character threshold (the earlier draft) | Lets OTPs, PINs and short identifiers flow into other apps' networked fields (R4) |

## Consequences

- More gate cards in messaging, email, social, Settings and web forms; S-04 measures recall, and "cards per task" is an M1 baseline.
- **Residual (OQ-23):** a silent irreversible tap is still possible outside the high-risk contexts or through a wrong per-package navigation rule; R1 stays rate-limited and confined to the per-task app set.
- **Residual (OQ-24):** text the model rephrases rather than copies is not tainted.
- Timer and alarm verification is partial: the alarm API shows only the earliest alarm [05§F3].

## Evidence

- [05§F1–F4], [05§R2–R3].
- [04§R3] grammar and loop.
- [07§R2] S-04 and S-08; FOUNDATION §12 S-10, S-11.

## Open questions

- OQ-5 (payments), OQ-6 (denylist), OQ-7 (unknown numbers), OQ-9 (limits), OQ-23 (irreversible-tap residual), OQ-24 (UI-path exfiltration residual).
- Measurements: `05-M1-V1`, `05-M1-D1…D6`, `05-M1-S2`, `05-M2-P1…P6`.
