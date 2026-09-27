# ADR-0014: Provisioning, signing key and build variants

- **Status:** Proposed
- **Date:** 2026-09-27 (revised after review findings R14, R15, R16)
- **Design reference:** FOUNDATION §10, §12 (draft fixes)

## Context

**Signing.**
- Android updates require the same certificate [07§F5].
- The op13 draft signs release builds with each CI runner's fresh debug key, so every build has a different certificate [07§F6 D2].
- For a device-owner app that is fatal: the only way out is removing DO and provisioning again, which means removing every account again.

**Updates and escape hatches.**
- A testOnly DO can only be updated through `adb install -t`, because `INSTALL_ALLOW_TEST` is stripped for non-shell installers [06§F3].
- `dpm remove-active-admin` works only on testOnly admins.
- `clearDeviceOwnerApp` is the in-app escape [06§F3].

**Preconditions and side effects** [06§F2–F3]:
- DO needs no accounts on any user and no extra users; clone users count.
- Becoming DO shuts down the backup manager.
- It sets DISALLOW_ADD_CLONE_PROFILE/PRIVATE_PROFILE while the DO exists.

## Decision

1. **One signing key for all variants**, created by the fleet before the first phone install.
   - Stored as repo secrets, decoded into `$RUNNER_TEMP` and never logged.
   - Backed up on the laptop and the TOSHIBA EXT drive.
   - Custody needs no owner input: credentials are never an owner question.
   - Fork PRs build unsigned.
2. **Variants share the `applicationId` and the key.**
   - `dev`: testOnly, debuggable, eval source set with `EvalReceiver` and dev-only `APPROVE_GATE`.
   - `release`: non-testOnly, no eval components.
   - `emulatorStub` (CI only, x86_64): the app with a Kotlin stub `:llm` and no native code (review R25).
   - CI asserts that release lacks the eval components and the testOnly flag, and that **neither variant declares INTERNET**.
3. **M1:** the dev build installed by `adb install -t`, with the one-time grants of FOUNDATION §10.1. No DO.
4. **M2:** DO provisioning of the same installed dev build (FOUNDATION §10.2).
   - Remove user 999 and every account, then `dpm set-device-owner`.
   - The app applies `setBackupServiceEnabled(true)`, `setUserControlDisabledPackages([self])` and `setUninstallBlocked(self)`, and self-grants its permissions.
   - **The owner-only "Release device owner" switch ships in the same M2 build** (review R16; the earlier "from day one" and "M4" were inconsistent). Behind BiometricPrompt, never exposed to the model, it runs `setUninstallBlocked(self, false)`, `setUserControlDisabledPackages([])`, then `clearDeviceOwnerApp`, auditing each step.
   - Reboot check without adb (the owner unlocks); re-add accounts.
5. **M4:** switch to the non-testOnly release with the same key. It self-updates through the DO PackageInstaller after `05-M2-P3` and `06-#6` confirm that the SEND_SMS allowlist and the ECM state survive. From then on USB debugging can be switched off; until then it stays on for `adb install -t` updates (OQ-25; wireless debugging stays off throughout).
6. **Rollback (R16):** first the in-app Release switch (clears both blocks, then the DO), then a normal uninstall. With adb on the testOnly build: `dpm remove-active-admin` + uninstall. That this also clears the uninstall block is INFERENCE from the DPMS source (clearing the DO ends with `removePoliciesForAdmin`, and the block is a policy-engine policy) and is verified by `06-#3`; if `pm uninstall` fails, run Release first. Last resort: factory reset.
7. **Owner's escapes without adb** (FOUNDATION §9.4): Stop; Disarm by the a11y shortcut or the Settings switch; a safe-mode boot (no third-party app runs); Release device owner; factory reset. They are shown in onboarding.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Per-runner debug key (draft) | A different certificate every run; no update-in-place; DO re-provisioning [07§F6] |
| Key only on the laptop, laptop signs (lane 07 option c) | Couples every build to the shared laptop and its ~11 GB disk |
| Non-testOnly from M2 | Loses the `remove-active-admin` escape while the design is still moving |
| Fresh factory setup with DO before accounts (lane 06 option b) | The owner already chose remove-and-re-add [README] |

## Consequences

- M1–M3 updates need USB adb, so USB debugging stays on on the daily phone until M4 (OQ-25). Runtime access never uses adb.
- Parallel Apps / user 999 cannot come back while operator is DO (OQ-13).
- Losing the key would force re-provisioning, hence two backups.
- Each M1 and M2 reboot test needs the owner (or an owner-provided test-window unlock) to unlock the phone, because a11y binds only after the first unlock (OQ-12).
- Play Protect is observed at install and for 7 days (OQ-27, K19).

## Evidence

- [06§F2–F3], [06§Runbook].
- [07§F5–F6], [07§R4].
- [05§F3] adb install allowlists hard-restricted permissions.
- [README] §Admin.

## Open questions

- OQ-12 (owner time, unlocks), OQ-13 (user 999), OQ-14 (provisioning session), OQ-25 (USB debugging), OQ-27 (Play Protect).
- Measurements: `06-#1…#4` (including `#3`, uninstall block after admin removal), `06-#6`, `06-#7`, `07-E-03`.
