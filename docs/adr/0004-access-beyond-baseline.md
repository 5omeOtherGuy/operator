# ADR-0004: Access beyond the baseline (answer to op13 addendum 2)

- **Status:** Proposed
- **Date:** 2026-09-27
- **Design reference:** FOUNDATION §3 (conflict C5)

## Context

The owner excluded root, Shizuku and wireless-adb self-connect (op13 addendum 2, owner verbatim "Also no shinzu"), and requires every access path to survive a reboot with no adb and no wireless debugging (ADR-0001). The question was which additional capabilities are reachable within those rules, and at what cost.

## Decision

**Recommendation (the addendum answer):** keep the owner's baseline and get the extra access by using the device owner fully, in the milestone order below; add no other mechanism. Wireless-adb self-connect is dropped from the idea list. No runtime path uses adb or wireless debugging; adb appears only in one-time setup and, until M4, in testOnly updates (OQ-25).

**Adopt now, M1:**
- The a11y flag maximum: `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`, `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`, `FLAG_REPORT_VIEW_IDS`, `FLAG_REQUEST_FILTER_KEY_EVENTS`, `FLAG_REQUEST_ACCESSIBILITY_BUTTON`, `canRetrieveWindowContent`, `canTakeScreenshot`, `canPerformGestures`.
- One-time grants: WSS; runtime permissions through `pm grant`; `appops ACCESS_RESTRICTED_SETTINGS allow`; `appops GET_USAGE_STATS allow`; `cmd deviceidle whitelist +pkg`; notification-listener approval.
- `USE_EXACT_ALARM` (a `normal` install-time permission [V11]) for the exact watchdog alarm, which also exempts the FGS start from the background limit (ADR-0003).

**Adopt at M2, device owner:**
- the DAS binding;
- DO self-grant of runtime permissions, sensors included for an adb-provisioned DO;
- `setUserControlDisabledPackages`, `setUninstallBlocked`, `setBackupServiceEnabled(true)`;
- silent install, uninstall, suspend and hide behind the gate;
- `appops WRITE_SETTINGS allow`; `ANSWER_PHONE_CALLS`;
- agent-mode animation scales through WSS.

**`isAccessibilityTool=false`, decided.**

**Never used:** the DO's `setGlobalSetting(ADB_ENABLED | ADB_WIFI_ENABLED)`. It is F-class, and the CI lint S-08 fails on any reference.

**Later, only on measured need:**
- `FLAG_INPUT_METHOD_EDITOR`: exposes `sendKeyEvent` into the focused app [V2];
- an own IME;
- MediaProjection;
- DO permitted lists (must include self);
- `ROLE_ASSISTANT` (M4, OQ-15);
- OTA policy (OQ-16).

## Alternatives considered (rejected)

| Option | Why not |
|---|---|
| Shizuku; wireless-adb self-connect; DO `ADB_*` | Owner rule; fails the reboot-without-adb requirement |
| Root; privileged or platform-signed install | Owner rule; root-equivalent [06§F8] |
| UiAutomation / Instrumentation | Shell-only on user builds; dies at reboot [06§F8] |
| `READ_LOGS` | Consent dialog on every read [06§F1] |
| `hidden_api_policy=1` | Device-wide weakening, no concrete need [06§F1] |
| `setApplicationExemptions`; the Device Policy Management role | Not grantable [01§F5][06§F2] |
| SMS, Dialer, Home, Autofill and notification-assistant roles; CDM | Replace core apps or weaken OTP protection. SEND_SMS works without the SMS role [05§F3][06§F6]. |
| AVF VM | No host control [06§F8] |
| `isAccessibilityTool=true` (lane 06 wanted to test and maybe adopt it) | With `false`, the gate's data-sensitive views are hidden from our own service, and the framework drops touches our service injects on them (AOSP r4 `View.onFilterTouchEventForSecurity` [V9], review R7); payment and login views are hidden too, which fits "payments forbidden" [05§F2]. A false tool claim is rejected by Play and blocked by Play Protect [V14][06§F4]. Cost: some sensitive views are invisible and untappable, and fall to the poor-tree path or ASK_OWNER. |
| `systemExempted` FGS | Eligible through DO or `USE_EXACT_ALARM` [V11], but no verified gain over `specialUse` (ADR-0003) |

## Consequences

- Every runtime capability rests on OS-persisted state or the app's own components. Survival is checked by S-09, `06-#4` and `06-#15`.
- The largest gain comes from using the DO fully, not from new mechanisms.
- Two things outside the design can cut access with no adb path back, so they are owner questions:
  - Android 17 Advanced Protection would revoke a non-tool a11y service [01§F6.7], so the owner keeps it off (risk K14, OQ-26).
  - Play Protect acting against a sideloaded app that combines a11y, key filtering, SMS/call permissions and device owner (risk K19, OQ-27). Play Protect does act on a11y apps [V14]; its treatment of an honest non-tool app is UNVERIFIED and observed from the first install (U38).

## Evidence

- [06§F1–F9] and [06§Options] (AOSP android-16.0.0_r4 sources).
- [05§F2] data-sensitive enforcement.
- [01§F6.3] binding checks only the DPM permitted list.
- [V2] a11y `InputConnection.sendKeyEvent`.

## Open questions

- OQ-15 (assistant role), OQ-16 (OTA policy), OQ-25 (USB debugging until M4), OQ-26 (Advanced Protection), OQ-27 (Play Protect).
- Measurements: `06-#4`, `06-#6`, `06-#7`, `06-#12`, `06-#14`; U38 (Play Protect observation).
