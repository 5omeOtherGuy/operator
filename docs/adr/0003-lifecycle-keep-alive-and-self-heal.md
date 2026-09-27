# ADR-0003: Lifecycle: specialUse FGS, Keeper self-heal, device-owner anchors

- **Status:** Proposed
- **Date:** 2026-09-27
- **Design reference:** FOUNDATION §2.5 (conflict C3)

## Context

OnePlus is rated worst for background killing [01§F6.1]. What AOSP does to us:
- A killed a11y host leaves the service marked crashed [01§F1.1], but AMS restarts the bound process after a backoff and the reconnect clears the mark; only after `BOUND_SERVICE_MAX_CRASH_RETRY` crashes, or a kill that allows no restart, does it stay down (corrected per review R9 [V10]).
- A force-stop removes the service from the enabled setting [01§F1.3].
- On Android 15+ a force-stop also cancels every PendingIntent, including the watchdog alarm. `BOOT_COMPLETED` is delivered only after a user action lifts the stopped state [V4].

What the device owner gets:
- a system-held `DeviceAdminService` binding at foreground priority, rebound after a crash;
- standby exemption;
- background-start exemptions [06§F2];
- `setUserControlDisabledPackages`, which blocks force-stop and clear-data from Settings [01§F5].

## Decision

1. **FGS:**
   - `AgentService` with `foregroundServiceType="specialUse"` (no timeout; allowed from `BOOT_COMPLETED` [01§F4]) and `START_STICKY`.
   - Started from `Application.onCreate` when armed, from `BOOT_COMPLETED` and from `MY_PACKAGE_REPLACED`.
   - One low-importance notification carries status and the actions *Open* and *Stop*; no RemoteInput (ADR-0016, review R3).
2. **Keeper** (main process):
   - **Triggers:** process start, a `ContentObserver` on `ENABLED_ACCESSIBILITY_SERVICES`, a 15-minute **exact** watchdog alarm (`setExactAndAllowWhileIdle` with `USE_EXACT_ALARM`, a `normal` install-time permission [V11]), DAS `onCreate` (M2), boot and package-replaced. The exact alarm also exempts the FGS start from the background-start limit [V11].
   - **Service missing:** if the removal was observed with the process alive and no crash, it is an owner switch-off, so Disarm and notify (OQ-11 default). Otherwise re-add it and notify.
   - **Service listed but not connected (revised per R9):** wait for the system's own restart (`onServiceConnected`) for `T_reconnect` (placeholder 60 s, set from the `01-M3` time-to-reconnect p95). Remove and re-add it only after that, or at once when the exit reasons or the crash count show AMS has stopped restarting it.
   - **Limits:** at most 6 toggles per hour, then notify. Never act while disarmed. If the service connects while disarmed (the OS shortcut is a toggle), it stays inert and the Keeper switches it off again (ADR-0013).
   - **Logging:** `getHistoricalProcessExitReasons` for both processes on every start; every WSS write, re-enable and DO policy application is an audit line (ADR-0013).
3. **Battery:** `cmd deviceidle whitelist +dev.operator` at setup, so Doze and FGS-from-background allowances apply [01§F5][06§A4].
4. **M2, device owner, applied on every start** (idempotent):
   - `setUserControlDisabledPackages([self])`;
   - `setUninstallBlocked(self)`;
   - the `DeviceAdminService` declared in the main process as a second anchor.
   - `setPermittedAccessibilityServices` stays `null`; if it is ever set, it must include self [06§F2].
   - The owner's "Release device owner" switch (M2, ADR-0014) clears both blocks before `clearDeviceOwnerApp`, so the owner can always get force-stop and uninstall back without adb.
5. **OEM toggles** (Oplus auto-launch, background, Deep/Sleep optimisation for operator only) go into the setup checklist only if the `01-M2` soak shows kills.

## Alternatives considered

| Alternative | Why not |
|---|---|
| `systemExempted` FGS (lane 06), including via `USE_EXACT_ALARM` | Corrected per review R8: the earlier reason ("throws if DO is removed") was wrong, because holders of `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` are also eligible [V11], and operator now holds `USE_EXACT_ALARM`. Still not adopted: the docs reserve the type for "system applications and specific system integrations", and no verified behaviour gives it anything measurable over `specialUse` for us (both have no timeout and may start from boot). Re-opened if the `01-M2` soak shows the FGS being killed; the measurement would be FGS survival and oom_adj under each type. |
| Inexact 15-minute watchdog (earlier draft) | Replaced by the exact alarm: on time in Doze, and the exact alarm exempts the FGS start from the background limit [V11]. Neither survives a force-stop [V4]. |
| `dataSync` / `mediaProcessing` / `shortService` | 6 h per 24 h caps, a boot ban, or about a 3 min ANR [01§F4] |
| No FGS; rely on the a11y binding | Priority only while the screen is awake; no sticky restart [01§F5.2] |
| Separate FGS in `:llm` (lane 02) | A second notification. A bound process already gets the client's importance [V5]. |
| `setApplicationExemptions` | Not grantable (`internal\|role`) [01§F5] |

## Consequences

- **M1 (no DO):** a force-stop from Settings or by the OEM leaves operator dead until the owner opens it. That residual risk is measured in M1 (U12) and closed for Settings force-stops in M2.
- Every automatic re-enable is visible to the owner through a notification.

## Evidence

- [01§F1], [01§F4], [01§F5], [01§F6] (AOSP android16-release; developer.android.com FGS docs).
- [06§F2] DeviceAdminServiceController, AppStandbyController.
- [V4] Android 15 package stopped state.
- [V5] bound-process importance.
- [V7] community report of swipe-away killing on an older OnePlus.
- [V10] AOSP r4 a11y restart semantics; [V11] FGS types, background-start exemptions, `USE_EXACT_ALARM` protection level.

## Open questions

- OQ-11: re-enable policy after a manual switch-off.
- Measurements: `01-M1`, `01-M2` (24 h soak incl. swipe-away and clear-all), `01-M3` (time to reconnect), `06-#4`, `06-#5`; U45 (exact alarms in Doze on OxygenOS).
