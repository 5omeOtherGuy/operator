# ADR-0001: Control stack baseline: accessibility service + WRITE_SECURE_SETTINGS + device owner

- **Status:** Accepted (owner, 2026-09-27)
- **Date:** 2026-09-27
- **Decider:** the owner. Recorded from `README.md` §Decisions ("2026-09-27: control stack = accessibility service + one-time `WRITE_SECURE_SETTINGS` grant + device owner. No Shizuku, no root.") and op13 addendum 2 ("Also no shinzu": no Shizuku; drop wireless-adb self-connect; access must survive reboot without adb or wireless debugging).

## Context

operator needs to read the screen and act on any app, keep that ability through OEM interference and reboots, and use admin powers (install, permissions, suspend) without root. The phone is a daily-use OnePlus 13 on stock OxygenOS 16 with an unlocked bootloader [REPORT.md].

## Decision

1. **Hands:** an `AccessibilityService` in the operator app. It reads windows and nodes, performs node actions, gestures and global actions, and takes screenshots.
2. **Keeping it on:** a one-time `adb shell pm grant dev.operator android.permission.WRITE_SECURE_SETTINGS`, so the app can re-enable its own service in `enabled_accessibility_services`.
3. **Admin:** operator is the device owner (`dpm set-device-owner`). Development builds are `android:testOnly="true"` so `dpm remove-active-admin` can undo it. `wipeData` is never exposed to the model.
4. **Excluded:**
   - root;
   - Shizuku;
   - wireless-adb self-connect, including the DO's own `ADB_ENABLED`/`ADB_WIFI_ENABLED` switches (same mechanism, see ADR-0004).
5. **Reboot rule:** every access path must work after a reboot with no adb and no wireless debugging. One-time adb steps at setup are allowed.

## Alternatives considered

| Alternative | Why not (owner) |
|---|---|
| Shizuku | Needs re-activation or Wi-Fi at boot [README] |
| Wireless-adb self-connect | The same mechanism as Shizuku; excluded by addendum 2 |
| Root (Magisk/KernelSU) | Keeps the bootloader unlocked, breaks Play Integrity, needs re-patching after every OTA [README] |

## Consequences

- Provisioning needs a device with no accounts and no secondary users. The owner removes the accounts and user 999 first, then re-adds the accounts [README]. Lane 06 found that user 999 (Parallel Apps) **cannot** come back while operator is DO (OQ-13).
- A testOnly DO build can only be updated through `adb install -t` (ADR-0014).
- Everything else in the design builds on this stack. ADR-0004 lists what is added on top of it and what is rejected.

## Evidence

- `README.md` §Design, §Decisions.
- Research: `docs/design/research/06-access-and-provisioning.md` §F2–F3 (DO preconditions and exemptions); `docs/design/research/01-architecture-and-ux.md` §F1, §F6 (a11y lifecycle and self-heal).

## Open questions

None for this ADR; it is the owner's decision. Downstream questions are OQ-13 (user 999) and OQ-14 (provisioning session).
