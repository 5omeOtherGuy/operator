# ADR-0008: Screen representation "OSF v0"

- **Status:** Proposed
- **Date:** 2026-09-27
- **Design reference:** FOUNDATION §6

## Context

- Every screen token costs prefill time. Bonsai's published phone pp is 30.4 t/s [04§F23].
- AndroidWorld's field dump is about 10× larger per element than a merged line format (INFERENCE from character counts [04§F2]).
- The op13 draft `ScreenReader` has two problems [04§F11][04§R1.5]:
  - it would return an empty screen, because the service config that fills `getWindows()` is missing;
  - it writes raw label text, so an app string can forge element lines.

## Decision

**OSF v0** [04§R1]: one line per merged actionable or readable element.

1. **Windows:** interactive windows, top layer first. **Operator's own windows and every a11y overlay are dropped.** The IME becomes `kbd=up`; the status and navigation bars are dropped. The notification shade and heads-up windows are kept, **minus operator's own notification rows**, their action buttons and any reply field (review R3): SystemUI owns those nodes, so rows are matched against operator's active notifications (app-name header, title, text) and dropped; an ambiguous match is dropped too. The executor refuses actions on the same rows (ADR-0010).
1b. **One-time codes are redacted in every source** (review R4): app windows, the shade, heads-up windows and notification text. OTP-like spans become `‹code›` (OQ-8 governs the exception). Android 15's own redaction covers only the notification listener [REV 13].
2. **Nodes:** drop invisible and zero-area nodes. Non-actionable leaves fold into their nearest actionable ancestor, joined with ` · `. No layout containers.
3. **Roles:** fixed vocabulary `btn txt edit switch chk radio tab list web link img menu seek`.
4. **Labels:** quoted and JSON-escaped; 80 characters (200 for `edit`); password fields emitted without content.
5. **Flags:** non-default only. No coordinates: bounds stay in a host-side map.
6. **Sensitive-denylist windows** are rendered as `[hidden: sensitive app]`. The denylist names Settings subpages explicitly: reset options, factory reset, app info (force stop, uninstall, clear storage), developer options, accessibility, device admin and special app access, security and lock screen, accounts (review R5).
7. **Identity:** a composite element key (package, window type, uniqueId or viewId, role, id-ancestor path, row/col, normalised label). Numbers are sticky within a **screen signature** (package, window title, structural hash). A `CHANGES` diff of at most 10 lines follows each action. The key does not say which conversation or form a target belongs to, so the gate binds the screen signature too (ADR-0011, review R12).
8. **Budget:** soft 800 and hard 1,500 tokens; list items are trimmed first. Tuned by `04-M1-2` and `04-M1-4`.
9. **Shared serializer:** one serializer over a neutral `UiNode` in `:agent-core`, used for live screens, recorded replays and AndroidControl.
10. **Vision:**
    - M1: a poor-tree detector, then ASK_OWNER.
    - M2: `takeScreenshot` (≥ 333 ms apart) + Tesseract, as `ocr` pseudo-elements addressed by index.
    - **ML Kit never:** it sends usage data to Google [04§F27].
    - Anything beyond OCR is OQ-18.

## Alternatives considered

| Alternative | Why not |
|---|---|
| AndroidWorld field dump | About 10× the tokens per element; unstable indices [04§F1–F2] |
| Compressed XML (AndroidLab) | Closing tags and nesting cost tokens; per-step indices [04§F3] |
| AutoDroid HTML | Syntax overhead; pre-scrolling costs actions [04§F4] |
| Screenshot + set-of-marks | Bonsai 8B is text-only [04§F22] |
| Fresh numbering every step | History lines lose meaning; no diffs. Sticky vs fresh is A/B-tested in `04-M2-3`. |

## Consequences

- The model never sees or targets the gate, and never sees operator's own notifications, so it cannot answer them (review R3).
- Forged element lines are prevented by escaping.
- Data-sensitive views are invisible to the service, and injected touches on them are dropped by the framework [V9] (review R7); fixtures must not depend on tapping them.
- A custom format needs a few-shot example in the static prefix.
- WebView and Flutter keys fall back to label + ordinal.

## Evidence

- [04§F1–F27], [04§R1], [04§R5].
- [05§R5] denylist rendering.
- [07§F3] one serializer for replay.

## Open questions

- OQ-18 (vision beyond OCR).
- Measurements: `04-M1-1…M1-8`, `04-M2-3`, `04-M2-5`; U46 (matching operator's notification rows on OxygenOS).
