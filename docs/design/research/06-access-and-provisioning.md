# 06 · Control access beyond the baseline, and device-owner provisioning

Lane 06, research note, 2026-09-27. Design only: no code, builds or device actions were involved.
Baseline (owner decision, not re-litigated here): an accessibility service, plus a one-time `pm grant … WRITE_SECURE_SETTINGS`, plus the device owner (`dpm set-device-owner`).
Hard limits: no root, no Shizuku, no wireless-adb self-connect. Every mechanism must survive a reboot without adb or wireless debugging; a one-time adb step at setup is fine.

Tags: **VERIFIED [n]** means checked against numbered source n. **UNVERIFIED** is followed by what would verify it. **INFERENCE** marks reasoning from verified facts.
AOSP line references are to tag `android-16.0.0_r4` (Android 16 QPR2) unless marked "mirror". The mirror is the GitHub `aosp-mirror` `android16-release` branch, which may lag QPR2. The device runs OxygenOS 16.0.10.501. Its AOSP base (QPR level) and any OEM patches to these code paths are **UNVERIFIED**. Every "VERIFIED" below is AOSP behaviour that the device can still contradict; see the M1 measurement list.

## Scope (questions covered)

1. One-time adb grants: which `pm grant`, `appops set`, `cmd deviceidle`, `cmd role` and standby-bucket changes exist on Android 16, what each one gives, and whether it persists across reboot and OTA.
2. Device-owner (DO) APIs on Android 14–16 beyond the basics: self-grant of runtime permissions (including sensors), the settings allowlists, user-control and uninstall blocking, the default dialer and SMS app, delegation, logging, lock task, keyguard and status bar, permitted a11y services and IMEs, system updates, the Device Policy Management Role, and what is new in Android 16.
3. Roles and privileged surfaces an ordinary app can hold: assistant (VoiceInteractionService), SMS, dialer, call screening, home, IME, autofill, notification listener and assistant, companion device, MediaProjection, and the accessibility-flag maximum.
4. Android 13–16 restricted settings (ECM), and how the install path changes them.
5. OEM (Oplus/OnePlus) specifics.
6. AVF/pKVM, Instrumentation/UiAutomation, and other things out of scope.
7. Device-owner provisioning: preconditions, runbook, escape hatches, side effects (OTA, Play Integrity, FRP, clone user), rollback and test plan.

## Findings

### F1. Protection levels on Android 16 (what `pm grant` can reach)

`pm grant` works only on runtime permissions and on permissions carrying the `development` flag. The levels below are VERIFIED [1] (parsed from `core/res/AndroidManifest.xml`):

| Permission | Level | Grantable by adb? | Use for operator |
|---|---|---|---|
| WRITE_SECURE_SETTINGS | signature\|privileged\|development\|role\|installer | yes (baseline) | re-enable a11y, default IME, animation scales, assist toggles, a11y shortcut |
| READ_LOGS | signature\|privileged\|development | yes | little: since Android 13 every logcat read needs an on-screen consent dialog while the app is in the foreground, and access lasts about 60 s (VERIFIED, secondary source [35]) |
| DUMP | signature\|privileged\|development | yes | window and activity dumps; whether an app process can use `dumpsys` under SELinux is **UNVERIFIED** (test: run `dumpsys window` from the app) |
| PACKAGE_USAGE_STATS | …\|development\|appop\|retailDemo | yes (or the appop `GET_USAGE_STATS`) | foreground-app and usage events |
| SET_ANIMATION_SCALE | signature\|privileged\|development | yes | not needed: WRITE_SECURE_SETTINGS can write the Global animation scales (INFERENCE) |
| CHANGE_CONFIGURATION | …\|development\|role | yes | locale and font scale, through hidden APIs only; reject |
| WRITE_SETTINGS | signature\|preinstalled\|appop\|pre23\|role | appop `WRITE_SETTINGS` (user toggle or adb) | brightness, screen timeout, ringtone |
| INTERACT_ACROSS_USERS | …\|development\|role | yes | only useful with hidden APIs and a second user; reject |
| INTERACT_ACROSS_USERS_FULL | signature\|installer\|module\|role | **no** | n/a |
| BATTERY_STATS, ACCESS_FINE_POWER_MONITORS | …\|development | yes | power measurement for lane 07 |
| SYSTEM_ALERT_WINDOW | …\|appop\|installer\|pre23\|development | yes | not needed: the a11y service can draw `TYPE_ACCESSIBILITY_OVERLAY` windows (INFERENCE) |
| SET_VOLUME_KEY_LONG_PRESS_LISTENER, SET_MEDIA_KEY_LISTENER | …\|development | yes | the API behind them is `@SystemApi`, so a hidden-API bypass is needed; reject (a11y key filtering covers the need, F4) |
| MANAGE_EXTERNAL_STORAGE | signature\|appop\|preinstalled | appop `MANAGE_EXTERNAL_STORAGE` | all-files access; later, when a file task needs it |
| INJECT_EVENTS, READ_FRAME_BUFFER, CAPTURE_VIDEO_OUTPUT, ACCESS_SURFACE_FLINGER, REAL_GET_TASKS, MANAGE_ACTIVITY_TASKS, CHANGE_COMPONENT_ENABLED_STATE, MODIFY_PHONE_STATE, STATUS_BAR, BIND_NOTIFICATION_ASSISTANT_SERVICE, REQUEST_COMPANION_SELF_MANAGED, INSTALL_TEST_ONLY_PACKAGE, MANAGE_ROLE_HOLDERS, MANAGE_APP_OPS_MODES | signature (plus privileged, role or recents variants) | **no** | not reachable without root or a platform signature |

- All 30 development-flag permissions on Android 16 are VERIFIED [1]: ACCESS_AMBIENT_LIGHT_STATS, ACCESS_BLOBS_ACROSS_USERS, ACCESS_BROADCAST_RESPONSE_STATS, ACCESS_FINE_POWER_MONITORS, ACCESS_SMARTSPACE, BATTERY_STATS, BRIGHTNESS_SLIDER_USAGE, CHANGE_CONFIGURATION, CONFIGURE_DISPLAY_BRIGHTNESS, CONTROL_UI_TRACING, DUMP, GET_APP_OPS_STATS, GET_PROCESS_STATE_AND_OOM_SCORE, INSTANT_APP_FOREGROUND_SERVICE, INTERACT_ACROSS_USERS, MODIFY_QUIET_MODE, PACKAGE_USAGE_STATS, READ_DROPBOX_DATA, READ_LOGS, READ_UPDATE_ENGINE_LOGS, SET_ALWAYS_FINISH, SET_ANIMATION_SCALE, SET_DEBUG_APP, SET_MEDIA_KEY_LISTENER, SET_PROCESS_LIMIT, SET_VOLUME_KEY_LONG_PRESS_LISTENER, SIGNAL_PERSISTENT_PROCESSES, SYSTEM_ALERT_WINDOW, WRITE_EMBEDDED_SUBSCRIPTIONS, WRITE_SECURE_SETTINGS. Beyond WRITE_SECURE_SETTINGS and PACKAGE_USAGE_STATS, none of them adds agent control that the public SDK can use (INFERENCE).
- `ACCESS_RESTRICTED_SETTINGS` is an appop, not a manifest permission. VERIFIED [1][5]: it is absent from the manifest and ECM stores its state in that appop.
- **Persistence.** Granted development permissions live in the runtime-permission state; appops, the deviceidle allowlist (`/data/system/deviceidle.xml`, VERIFIED [12]) and role holders live in `/data/system`. All should survive a reboot and an OTA unless the OTA changes a permission's protection level (INFERENCE). M1 measures this.
- **Hidden APIs.** `settings put global hidden_api_policy 1` opens non-SDK interfaces "without root" (VERIFIED [28]). It is a Global setting, so an app holding WRITE_SECURE_SETTINGS could write it itself (INFERENCE; on-device check needed). It reaches only hidden APIs gated by permissions we already hold, never signature-gated ones, and it weakens a device-wide defence. Reject until a concrete need exists.

### F2. Device owner: what the adb-provisioned DO actually gets (AOSP 16 QPR2)

- **Sensor permissions.** An adb-provisioned DO may grant sensor permissions (location, camera, microphone, body sensors). `setDeviceOwner` sets `mAdminCanGrantSensorsPermissions = true` when the caller is adb (VERIFIED [2] DPMS ≈l.9991). `setPermissionGrantState` otherwise throws for sensor grants (VERIFIED [2] ≈l.17344).
- **Hard-restricted permissions.** SMS, call log, background location and outgoing calls are `hardRestricted` (VERIFIED [1]). `adb install` / `pm install` allowlists all restricted permissions unless `--restrict-permissions` is passed (VERIFIED [17]). So a DO installed by adb can self-grant READ_SMS, SEND_SMS, READ_CALL_LOG and the rest. Whether the allowlist survives a later self-update installed through the DO's own PackageInstaller session is **UNVERIFIED**: M1 must test self-update, then `dumpsys package` for restricted flags.
- **Keep-alive (strongest finding).**
  - The system binds the DO's `DeviceAdminService` through a `PersistentConnection` with `BIND_FOREGROUND_SERVICE` and rebinds it after a crash (VERIFIED [21]; DPMS starts it at `set-device-owner`, [2] ≈l.10024). The operator process therefore has a system-held, foreground-priority binding from boot, with no notification of its own.
  - Active device admins sit in `STANDBY_BUCKET_EXEMPTED`, and so do packages listed in `setUserControlDisabledPackages` (VERIFIED [9] l.1471–1476).
  - The DO is exempt from background-activity-start limits (VERIFIED [10] l.1246, "Device Owner").
  - The DO is exempt from foreground-service-from-background limits (VERIFIED [23], [11] l.2960). Its FGS gets while-in-use permissions (microphone, camera, location) even when started from the background, and it may use the `systemExempted` FGS type (VERIFIED [11], mirror).
- **Settings allowlists.** `setSecureSetting` accepts DEFAULT_INPUT_METHOD, SKIP_FIRST_USE_HINTS, INSTALL_NON_MARKET_APPS and (DO only) LOCATION_MODE. `setGlobalSetting` accepts ADB_ENABLED, ADB_WIFI_ENABLED, AUTO_TIME, AUTO_TIME_ZONE, DATA_ROAMING, USB_MASS_STORAGE_ENABLED, WIFI_SLEEP_POLICY, STAY_ON_WHILE_PLUGGED_IN, WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN and the PRIVATE_DNS pair (VERIFIED [2] l.703–723). WRITE_SECURE_SETTINGS already covers all of these and more, so the DO adds nothing here. The DO *could* switch wireless debugging on; that is the forbidden mechanism and must never be used (owner rule).
- **Present in the Android 16 public API** (VERIFIED [18]): `setUserControlDisabledPackages`, `setUninstallBlocked`, `setPermissionGrantState`, `setPermissionPolicy`, `setDefaultDialerApplication`, `setDefaultSmsApplication`, `setDelegatedScopes`, `setSecurityLoggingEnabled`, `setNetworkLoggingEnabled`, `setLockTaskFeatures`, `setKeyguardDisabled`, `setStatusBarDisabled`, `setPermittedAccessibilityServices`, `setPermittedInputMethods`, `setSystemUpdatePolicy`, `installSystemUpdate`, `installExistingPackage`, `enableSystemApp`, `setApplicationHidden`, `setPackagesSuspended`, `addPersistentPreferredActivity`, `reboot`, `setLocationEnabled`, `setBackupServiceEnabled`, `transferOwnership`, and `clearDeviceOwnerApp` (deprecated but present). New flagged Android 16 policies: `setAppFunctionsPolicy`, `setAutoTimePolicy`, `setAutoTimeZonePolicy`, `setContentProtectionPolicy` (VERIFIED [18]).
- **Not for us.** `setApplicationExemptions` (exempt from hibernation, suspension, power restrictions and so on) needs `MANAGE_DEVICE_POLICY_APP_EXEMPTIONS`, which is `internal|role` (VERIFIED [1][2] l.21106). The Device Policy Management Role is `static="true"`, its holder comes from OEM config, and it requires `LAUNCH_DEVICE_MANAGER_SETUP` (signature|role) (VERIFIED [19][1]). The operator cannot hold it.
- **Pitfall.** If the DO ever calls `setPermittedAccessibilityServices`, the list must include the operator's own package. Otherwise the a11y manager disables our service at bind time ("Skipping enabling service disallowed by device admin policy", VERIFIED [6] l.3103).
- **Side effects of becoming DO** (VERIFIED [2] l.9975–10022):
  - The backup manager is shut down. Re-enable it with `setBackupServiceEnabled(true)`.
  - `DISALLOW_ADD_MANAGED_PROFILE`, `DISALLOW_ADD_CLONE_PROFILE` and `DISALLOW_ADD_PRIVATE_PROFILE` are set on every user, citing the CDD. They are re-applied while a DO exists (l.2717–2745) and cleared only when the DO is cleared (l.4470–4510), which also re-enables backup (l.10523).
  - Consequence: **no Parallel Apps clone profile and no Private Space while operator is DO** (INFERENCE from the above, provided Oplus Parallel Apps uses the AOSP clone-profile path; **UNVERIFIED**, check with `dumpsys user`).

### F3. `dpm set-device-owner` preconditions (adb path, Android 16)

VERIFIED [2] (`setDeviceOwner` l.9941ff, `checkDeviceOwnerProvisioningPreConditionLocked` l.17696ff, `hasIncompatibleAccountsOrNonAdbNoLock` ≈l.19300):
1. No DO exists, and user 0 has no profile owner.
2. Once setup is complete, no users may exist beyond the default set (1, or 2 on headless-system-user devices), counting everything not marked FOR_TESTING. **Clone and parallel users count**, so user 999 blocks with "Not allowed to set the device owner because there are already several users on the device."
3. Accounts: with a *non*-testOnly admin, any account on any user blocks ("Non test-only owner can't be installed with existing accounts"). With a testOnly admin, accounts are allowed only if every one of them carries `ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_ALLOWED`. Google, OnePlus and app accounts do not (INFERENCE), so **in practice remove every AccountManager account on every user**. That includes app-registered ones such as messengers and Microsoft (list them with `dumpsys account`).
4. The admin package must be installed and its receiver must be an active admin (`dpm set-device-owner` activates it).
5. Usage: `dpm set-device-owner [--user <id>|current] [--device-owner-only] [--provisioning-context …] <COMPONENT>` (VERIFIED [4]).
- **Escape hatches.**
  - `dpm remove-active-admin <COMPONENT>` works only if the admin is testOnly (VERIFIED [2] l.4413 "Attempt to remove non-test admin").
  - The testOnly flag is captured when the admin is activated and kept on refresh (VERIFIED [2] l.4171). Whether it survives an update to a non-testOnly APK is **UNVERIFIED**.
  - Any DO can release itself in-app with `clearDeviceOwnerApp(pkg)`, which is deprecated but implemented (VERIFIED [2] l.10422). This is the non-testOnly escape and needs no adb.
  - Factory reset always removes the DO.
- **testOnly blocks self-update.** For callers other than shell, `INSTALL_ALLOW_TEST` is stripped unless the caller holds `INSTALL_TEST_ONLY_PACKAGE`, which is signature-only (VERIFIED [3] l.833–836, [1]). A testOnly operator can therefore only be updated with `adb install -t -r`; CI builds cannot self-install over the air. INFERENCE: testOnly suits M1/M2 while a laptop is attached; later a non-testOnly release with an in-app release switch.

### F4. Accessibility service at maximum (Android 16)

- **Flags and capabilities** (VERIFIED [7]): `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`, `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`, `FLAG_REPORT_VIEW_IDS`, `FLAG_REQUEST_FILTER_KEY_EVENTS` (see and consume hardware keys, for a volume-key trigger), `FLAG_REQUEST_ACCESSIBILITY_BUTTON`, `FLAG_INPUT_METHOD_EDITOR` (gives an `InputConnection` and selection events: typing without replacing the keyboard), `FLAG_SEND_MOTION_EVENTS` (only under touch exploration, so of diagnostic value only), `CAPABILITY_CAN_TAKE_SCREENSHOT`, and `CAPABILITY_CAN_PERFORM_GESTURES`.
- **Screenshots.** `takeScreenshot` has a minimum interval of 333 ms (`ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS`), and secure windows return `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` (VERIFIED [8]). `takeScreenshotOfWindow` exists (VERIFIED [8]).
- **Global actions.** They go up to DPAD, KEYCODE_HEADSETHOOK, DISMISS_NOTIFICATION_SHADE and flagged MENU / MEDIA_PLAY_PAUSE (VERIFIED [8]).
- **Sensitive views.** Android 16 hides views marked `accessibilityDataSensitive` from every a11y service that does not declare `isAccessibilityTool=true`. Views that use `setFilterTouchesWhenObscured(true)` count as sensitive automatically. Google says a false claim is rejected by Play and "Google Play Protect will block it" (VERIFIED [27]).
  - Trade-off: `isAccessibilityTool=true` shows the operator login, payment and permission-style screens, but risks a Play Protect block of a sideloaded app.
  - What Play Protect does to a sideloaded, adb-installed tool-flagged app is **UNVERIFIED**; M1 measures it.
- **Advanced Protection Mode.** Press reports say it revokes a11y access for services that are not tools; reported for Android 17 betas, with a start in 16 claimed (**UNVERIFIED**, secondary [36]). Operator requires Advanced Protection to stay off.

### F5. Restricted settings / Enhanced Confirmation Mode (ECM)

- The Android 16 CDD §9.8 H-0-1 covers apps whose package source is `DOWNLOADED_FILE` or `LOCAL_FILE`. Covered settings are accessibility, notification listener, device admin, display over other apps, usage access, the Dialer and SMS roles, and SMS runtime permissions (VERIFIED [22]).
- How the AOSP ECM service decides (VERIFIED [5] l.389–437):
  - Preinstalled or allowlisted packages and installers are trusted.
  - An explicit appop `ACCESS_RESTRICTED_SETTINGS` state wins; `allow` means NOT_GUARDED.
  - Otherwise LOCAL_FILE or DOWNLOADED_FILE means guarded.
  - Otherwise the app is guarded unless its installer is preinstalled or allowlisted. When the OEM configured no trusted installers, everything else is trusted.
- **adb install**: the installer is `com.android.shell`, which is preinstalled, so the app is **not guarded** (INFERENCE from [5][3]).
- **Self-update through the operator's own PackageInstaller session**: the installer becomes operator, which is not preinstalled, so the app becomes guarded *if* the OEM configured trusted installers (**UNVERIFIED** on OxygenOS). Mitigation: a one-time `appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow`, which becomes an explicit state and persists (INFERENCE).
- **ECM does not block our re-enable path.** AOSP `AccessibilityManagerService` binds services listed in `enabled_accessibility_services`, checking only the DPM permitted list and not ECM (VERIFIED [6] l.3103, `isAccessibilityTargetAllowed`). The WRITE_SECURE_SETTINGS path works even for a guarded app. An OEM patch could differ (**UNVERIFIED**).

### F6. Roles and other surfaces

- **ROLE_ASSISTANT (VoiceInteractionService).**
  - The role is `requestable="false"`; the user sets it in Settings, or adb sets it with `cmd role add-role-holder` (VERIFIED [19]).
  - It grants the SMS permission set, READ_CALL_LOG, SYSTEM_ALERT_WINDOW, EXECUTE_APP_ACTION, READ_ASSISTANT_APP_SEARCH_DATA, EMBED_ANY_APP_IN_UNTRUSTED_MODE, SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE, and the appop `receive_sensitive_notifications` (VERIFIED [19]).
  - The system binds the active VIS with `BIND_FOREGROUND_SERVICE | BIND_INCLUDE_CAPABILITIES | BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS` (VERIFIED [15]): a second always-on binding that includes while-in-use capabilities (microphone).
  - The VIS may call `showSession(args, SHOW_WITH_ASSIST|SHOW_WITH_SCREENSHOT)` on itself (VERIFIED [14]). The system then fetches AssistStructure, AssistContent and a screenshot of the top activity, gated by `assist_structure_enabled`, `assist_screenshot_enabled` and `isAssistDataAllowed` (VERIFIED [15]).
  - Cost: it replaces Gemini/Google Assistant as the long-press assistant. OxygenOS's default holder is **UNVERIFIED**.
  - Value: long-press power as the owner's entry point, structured web and app content (AssistContent URIs and JSON), and unredacted OTP notifications. The last one is a safety *risk*, not a feature.
- **ROLE_DIALER (InCallService)** gives full call control but replaces the OnePlus/Google dialer. The cheaper path is ANSWER_PHONE_CALLS (`dangerous|runtime`, VERIFIED [1]), self-granted by the DO, for `TelecomManager.acceptRingingCall/endCall`. Reject the role.
- **ROLE_SMS** would replace Google Messages (RCS). SEND_SMS and READ_SMS are self-grantable (F2). Reject the role.
- **ROLE_CALL_SCREENING** can silence, reject or screen incoming calls, but the role is exclusive (VERIFIED [19]) and probably held by the Phone app (**UNVERIFIED**). Later.
- **ROLE_HOME** gives nothing the a11y service lacks, at a large UX cost. Reject.
- **Own IME.** The current IME is exempt from background FGS and activity-start limits (VERIFIED [23][24]) and is settable through WRITE_SECURE_SETTINGS or DO `setSecureSetting(DEFAULT_INPUT_METHOD)` (VERIFIED [2]). `FLAG_INPUT_METHOD_EDITOR` already gives an InputConnection (F4). Later, only if M1 finds fields where neither `ACTION_SET_TEXT` nor the a11y InputConnection works.
- **AutofillService** is a second channel into form fields, but it replaces the password manager's autofill. Reject.
- **NotificationListenerService** (baseline plan).
  - Since Android 15, untrusted listeners receive OTP notifications redacted; only trusted ones (CDM associations) are exempt (VERIFIED [25]). RECEIVE_SENSITIVE_NOTIFICATIONS is `signature|preinstalled|knownSigner|role` (VERIFIED [1]).
  - Accept the redaction as a safety property.
  - Grant it once: through the settings toggle (not ECM-guarded for an adb install), or with `cmd notification allow_listener <component>` (persistence **UNVERIFIED**, M1).
- **NotificationAssistantService** would replace Android System Intelligence, including the OTP detection itself. Reject.
- **CompanionDeviceManager.** A self-managed association needs REQUEST_COMPANION_SELF_MANAGED (`signature|privileged`, VERIFIED [1]). Other profiles need a real paired device. Reject.
- **MediaProjection.**
  - The appop `PROJECT_MEDIA=allow` makes `hasProjectionPermission` true (VERIFIED [13]). Whether OxygenOS SystemUI then skips the consent dialog is **UNVERIFIED**.
  - Since 15 QPR1 projection stops when the screen locks and shows a large status-bar chip. OTP and sensitive content is redacted while sharing (VERIFIED [25]).
  - Only useful for continuous video frames. Later, only if lane 04 needs more than 3 fps.
- **Android 16 AppFunctions.** `EXECUTE_APP_FUNCTIONS` is `internal|privileged`, or `normal` plus a device allowlist plus user approval when the flag `app_function_access_api_enabled` is on (VERIFIED [1][20]). The flag state on OxygenOS is **UNVERIFIED**. Watch it: it is the future typed agent-to-app channel.

### F7. OEM (Oplus/OnePlus)

- Community reports describe aggressive background killing on OnePlus: "deep optimization", "sleep standby optimization", and auto-launch limits that revert. They say there is "no known solution on the developer end" (**UNVERIFIED**, community source [34], OxygenOS version unstated).
- No public Oplus API grants more access. Oplus auto-launch and "allow background activity" are UI toggles whose storage is **UNVERIFIED**.
- The debloat (REPORT [38]) removed 15 packages. DO `installExistingPackage` or `enableSystemApp` can restore them without adb (INFERENCE from [18]).
- Whether the Oplus freezer (Hans/Athena, **UNVERIFIED** names) honours DO, standby-EXEMPTED and deviceidle exemptions is the key M1 measurement.
- The owner brief calls user 999 "App Cloner". That is **UNVERIFIED**: App Cloner (applisto) repackages APKs inside user 0, while user 999 is typically OnePlus Parallel Apps. `pm list users` and `dumpsys user` decide it.

### F8. Out of scope or failing the rules

- **Instrumentation and UiAutomation.** On non-debuggable builds `startInstrumentation` is refused unless the caller is root, shell or system ("not started from SHELL", VERIFIED [16]). The session dies at reboot, so it fails the reboot rule. Reject.
- **AVF / Microdroid / Linux terminal.**
  - MANAGE_VIRTUAL_MACHINE can be pm-granted "for development purposes" (VERIFIED [29]). The terminal is a non-protected VM (VERIFIED [30]).
  - A guest VM has no Binder or framework access to the host, so it gives no control over it (INFERENCE from the VM isolation model [30]).
  - As an inference venue it adds a hypervisor and memory split and has no Hexagon NPU path (INFERENCE). Support on SM8750 is **UNVERIFIED**. Reject.
- **Shizuku, wireless-adb self-connect, and DO `ADB_WIFI_ENABLED`**: rejected by the owner.
- **Root, Magisk/KernelSU, a custom AVB-signed system image, or installing operator as a privileged or platform-signed system app** (which would unlock INJECT_EVENTS, READ_FRAME_BUFFER and similar): root-equivalent, out of scope.
- **Secure keyguard.** No non-root mechanism lets the agent unlock a PIN or biometric keyguard. `setKeyguardDisabled` does not remove a secure lock (INFERENCE). The agent works only on an unlocked phone.

### F9. Play Integrity, Play Protect, sideloading policy

- MEETS_DEVICE_INTEGRITY requires a locked bootloader on Android 13+ (VERIFIED [26]). The phone is unlocked (REPORT [38]), so it already fails, with or without operator.
- The `appAccessRiskVerdict` reports non-Play apps that can capture or control the screen as `UNKNOWN_CAPTURING` / `UNKNOWN_CONTROLLING`. Only Play-verified accessibility services are excluded (VERIFIED [26]). Banking apps may refuse to run while operator's a11y service is active (**UNVERIFIED** per app).
- Mitigation (INFERENCE): switch the a11y service off through WRITE_SECURE_SETTINGS while an allowlisted banking app is in the foreground (detected through UsageStats), then switch it back on.
- Nothing found says that being a DO changes integrity verdicts (the docs are silent [26]; **UNVERIFIED**).
- **Developer verification.** Unregistered apps will still install through adb or an "advanced flow" (secondary [32]). The regional requirement starts 2026-09-30 in BR/ID/SG/TH, with a global rollout in 2027 (VERIFIED [31]). The limited-distribution account (up to 20 devices, no ID or fee) is the fallback (VERIFIED [31]).

## Options (access matrix)

Setup cost: "adb once" means one command in the setup session; "tap" means a one-time user toggle on the phone.

| # | Capability | Baseline covers? | Addition | One-time setup cost | Survives reboot / OTA? | Risk (Play Protect / Integrity / security / OEM reset) | Recommendation |
|---|---|---|---|---|---|---|---|
| A1 | Read UI tree, act, global actions | yes (a11y) | max flags: interactive windows, not-important views, view IDs, screenshot, gestures, key filter, a11y button, IME flag | none (manifest XML) | yes / yes (INFERENCE) | a larger a11y surface; nothing new versus the baseline | **adopt now** |
| A2 | Sensitive views (payment, login, obscured-touch) | **no** on Android 16 | `isAccessibilityTool=true` | none | yes / yes | Play Protect may block the app [27]; it is a policy misstatement | **owner decision**; default: measure in M1, adopt only if Play Protect tolerates it |
| A3 | Survive OEM kill, stay warm | partly (the a11y bind) | DO DeviceAdminService persistent FGS bind [21]; standby EXEMPTED [9]; FGS-from-bg + while-in-use [11]; `setUserControlDisabledPackages(self)`; `setUninstallBlocked(self)` | none beyond the DO | yes / yes | user cannot force-stop or clear data; Oplus freezer behaviour **UNVERIFIED** | **adopt now** |
| A4 | Doze exemption for night and scheduled runs | no | `cmd deviceidle whitelist +pkg`, or ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | adb once or tap | yes [12] / yes (INFERENCE) | battery cost only while it runs | **adopt now** |
| A5 | Re-enable own a11y after an OEM disable | yes (WRITE_SECURE_SETTINGS) | `appops set … ACCESS_RESTRICTED_SETTINGS allow` as a belt-and-braces for ECM | adb once | yes / yes (INFERENCE) | none | **adopt now** |
| A6 | Direct APIs: SMS, call log, contacts, calendar, location, mic, camera, phone | via the DO | DO `setPermissionGrantState` for itself, sensors included (adb DO) [2] | none | yes / yes | revocation locked while the DO grants it; hard-restricted allowlist after self-update **UNVERIFIED** | **adopt now** |
| A7 | Call answer and end | no | ANSWER_PHONE_CALLS (DO grant) | none | yes | irreversible action, so the confirm gate applies (lane 05) | **adopt now**; dialer role **reject** |
| A8 | Foreground app, usage events | a11y window package | PACKAGE_USAGE_STATS: `appops set … GET_USAGE_STATS allow` or tap | adb once | yes / yes | ECM covers usage access only for file installs | **adopt now** |
| A9 | System settings: brightness, timeout, ringtone | no | appop WRITE_SETTINGS | adb once or tap | yes / yes | low | **adopt now** |
| A10 | Faster, stabler UI for the agent | no | Global animation scales through WRITE_SECURE_SETTINGS, "agent mode" | none | the setting persists; restore on exit | visible UX change | **adopt now** (lane 05 owns when) |
| A11 | Owner trigger | none | a11y key filter (volume chord), a11y shortcut target through WRITE_SECURE_SETTINGS, a11y button | none | yes | may collide with the OxygenOS volume shortcut | **adopt now** (lane 01 picks the gesture) |
| A12 | Notifications read and reply | planned | listener, granted through the toggle or `cmd notification allow_listener` | adb once or tap | yes / yes (INFERENCE) | OTPs redacted (a feature) | **adopt now** |
| A13 | App install and remove, restore debloated apps | yes (DO) | `installExistingPackage`, `enableSystemApp`, suspend/hide | none | yes | model-exposed only behind the confirm gate | **adopt now** (DO baseline) |
| A14 | Google backup kept working | broken by the DO | `setBackupServiceEnabled(true)` right after provisioning | none | yes | none | **adopt now** |
| A15 | Assistant surface: AssistStructure, AssistContent, screenshot, long-press entry, 2nd persistent bind | no | ROLE_ASSISTANT through `cmd role add-role-holder` or Settings | adb once or tap | yes / yes (INFERENCE) | loses Gemini; role grants unredacted-OTP access (disable in code) | **later (M2)**, owner decision |
| A16 | Own keyboard | no | InputMethodService, default set through WRITE_SECURE_SETTINGS | none | yes | replaces Gboard while active | **later**, only if M1 finds untypeable fields |
| A17 | Continuous screen video | no | MediaProjection + appop PROJECT_MEDIA | adb once | appop yes; each session stops on lock | status chip; content redaction [25] | **later** (lane 04 need) |
| A18 | Guard against rogue a11y services and IMEs | no | DO `setPermittedAccessibilityServices` / `setPermittedInputMethods` (include self!) | none | yes | self-lockout if misconfigured [6] | **later** |
| A19 | Audit trail | no | DO security and network logging | none | yes | a "managed" disclosure; storage | **later** (lane 05/07) |
| A20 | OTA control | no | DO `setSystemUpdatePolicy` (postpone or freeze) | none | yes | OxygenOS honouring it **UNVERIFIED** | **later** |
| A21 | Power telemetry | no | pm grant BATTERY_STATS, ACCESS_FINE_POWER_MONITORS | adb once | yes | none | **later** (lane 07) |
| A22 | Hidden framework APIs | no | `hidden_api_policy=1` | none | yes | device-wide weakening | **reject** for now |
| A23 | Logs | no | READ_LOGS | adb once | yes | consent dialog per read [35] | **reject** |
| A24 | Typed app actions (AppFunctions) | no | EXECUTE_APP_FUNCTIONS | n/a | n/a | not grantable today (privileged or allowlist) | **watch** |
| A25 | SMS / Dialer / Home / Autofill / Notification-assistant roles; CDM | no | roles | tap | yes | replaces core apps or weakens OTP protection | **reject** |
| A26 | UiAutomation / Instrumentation | no | `am instrument` | adb **every boot** | **no** [16] | n/a | **reject** (reboot rule) |
| A27 | AVF VM | no | pm grant MANAGE_VIRTUAL_MACHINE | adb once | yes | no host control | **reject** |
| A28 | Shizuku, wireless-adb self-connect, root, custom system image | n/a | n/a | n/a | n/a | n/a | **reject / out of scope** (owner) |

## Recommendation

**Decided now (M1):**
1. Keep the baseline. Add A1, A3–A14 and A11: none of them needs more than the DO plus four one-time adb commands (deviceidle, two appops, notification listener), and all persist by design.
2. Treat the DO's system-held `DeviceAdminService` binding as the primary keep-alive, not a user-visible foreground service. Host the LLM process in it (or behind it), use the `systemExempted` FGS type while inference runs, and keep `setUserControlDisabledPackages(self)`.
3. M1 and M2 ship a **testOnly** build installed by `adb install -t`, with `dpm remove-active-admin` as the escape. Also build an owner-only "Release device owner" switch (`clearDeviceOwnerApp`) from day one, never exposed to the model, so the later non-testOnly build has a no-adb escape.
4. Parallel Apps / user 999 must be removed and **cannot be re-created while operator is DO** (F2), and Private Space goes too. Clone needs are met by APK-level cloners in user 0 (App Cloner-style), which the DO rules do not touch (INFERENCE).

**Deferred to on-device measurement:**
- A2 (`isAccessibilityTool`): decide after the Play Protect behaviour test.
- A15 (assistant role): decide after the owner accepts losing Gemini on long-press. It is the largest *additional* access (structured content plus a second persistent bind) that costs no security posture.
- A16 and A17: only on a lane 04/05 demand backed by M1 data.
- A20 (OTA freeze): once OxygenOS behaviour is known.

**Rejected:** READ_LOGS, hidden APIs, the Dialer/SMS/Home/Autofill/NAS roles, CDM, UiAutomation, AVF, and everything the owner excluded.

## Interfaces this lane assumes from other lanes

- **01 (architecture, UX):** owns the owner-trigger gesture (A11) and the "Release device owner" and "agent mode" switches. It must accept that the process layout centres on the DeviceAdminService binding.
- **02 (inference):** the model process may run inside the DO app process. Its memory is not protected from the low-memory killer by the DO binding alone (foreground-service priority, not persistent). 02 measures residency.
- **04 (screen representation):** gets a11y nodes plus a11y screenshots, at most about 3 per second (333 ms) and blank for secure windows. Sensitive views are hidden unless A2 is adopted. AssistStructure/AssistContent arrive only if A15 is adopted.
- **05 (actions, safety):** exposes DO APIs to the model only through typed, confirm-gated actions. It never exposes `wipeData`, `clearDeviceOwnerApp`, `setGlobalSetting(ADB_*)`, `setPermittedAccessibilityServices` or `hidden_api_policy`. Screen and notification text stays data.
- **07 (evaluation, CI):** CI must build two variants: testOnly (debug) and non-testOnly (release, same signing key). Adding permissions or declarations later needs a re-provisioning check. 07 also owns the power-telemetry grants (A21).
- **All lanes:** the operator manifest declares no INTERNET permission, which enforces "nothing leaves the phone" (INFERENCE; the owner brief implies it).

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Oplus freezer kills or freezes the DO process despite AOSP exemptions | medium (UNVERIFIED) | agent dead until app open | M1 soak test; Oplus UI toggles (auto-launch, background) set once; the a11y bind as a second anchor |
| Parallel Apps / user 999 data lost when the user is removed | high if it is used | loss of cloned-app data | back up the cloned apps' data first; APK-level cloner afterwards |
| Account removal side effects (Wallet cards, FRP window, app re-logins) | high | re-setup effort | full backup; remove only for the minutes of provisioning; re-add immediately |
| `isAccessibilityTool` flags the app in Play Protect | medium (UNVERIFIED) | app disabled or uninstalled | keep it off by default; test in M1 with a throwaway package name |
| Banking apps refuse while a non-Play a11y service runs | medium | owner cannot bank while the agent is enabled | auto-toggle the a11y service off for allowlisted apps |
| testOnly build cannot self-update | certain | updates need the laptop | acceptable for M1/M2; release build with the in-app escape later |
| OTA changes a permission level or resets appops / Oplus toggles | low–medium | lost grant | post-OTA self-check screen in the app; re-run the grant script |
| DO permitted-list misconfiguration locks out our own a11y service | low | agent blind | code rule: always include self; unit test |
| Assistant role gives unredacted OTP access | certain if A15 is adopted | prompt-injection or exfiltration surface | drop OTP notifications in the listener; lane 05 rule |
| Future Android (Advanced Protection, 2027 verification) blocks non-tool a11y or unverified apps | medium, 2027+ | stack breaks after an upgrade | limited-distribution registration; keep Advanced Protection off; watch releases |

## Owner-only questions

1. **Parallel Apps (user 999).** It must be deleted before provisioning and cannot return while operator is DO.
   - Options: (a) delete it, using an APK cloner in user 0 for any second-account apps; (b) keep it and drop the DO.
   - Recommended default: (a).
2. **Account removal window.** Every account (Google, OnePlus, app-registered) must be removed for the provisioning minutes.
   - Options: (a) remove all and re-add; (b) factory-reset-style fresh setup with the DO set before any account.
   - Recommended default: (a), after a verified backup.
3. **`isAccessibilityTool=true`**, which is needed to see sensitive views on Android 16.
   - Options: (a) never; (b) M1 test, then adopt if Play Protect allows; (c) adopt and turn off Play Protect app scanning.
   - Recommended default: (b).
4. **Assistant role (M2).**
   - Options: (a) operator becomes the default digital assistant, replacing Gemini on long-press; (b) keep Gemini.
   - Recommended default: (a) at M2, if lane 04 shows value from AssistContent.
5. **OTA policy under DO.**
   - Options: (a) leave OTAs to OxygenOS; (b) postpone through `setSystemUpdatePolicy` until the owner approves each one.
   - Recommended default: (a) until M1 shows OxygenOS honours policy.

## On-device measurements needed (for M1/M2)

All of these run in the provisioning session by the session that owns the phone, never by this lane.
1. `pm list users`; `dumpsys user` shows the type and creator of user 999 (clone profile or Oplus type).
2. `dumpsys account` lists every account and authenticator on every user.
3. After provisioning: `dpm list-owners`, `dumpsys device_policy` (testOnly flag, restrictions), and `pm list users` checked against DISALLOW_ADD_CLONE_PROFILE.
4. Reboot test, with no adb afterwards: the a11y service bound; the DeviceAdminService process alive (`dumpsys activity services dev.operator`, oom_adj/procstate); grants present (`dumpsys package`, `appops get`, `dumpsys deviceidle whitelist`, `cmd notification` listener list).
5. Soak test: 12 h screen off, then 12 h mixed use. Is the process ever frozen or killed (Oplus)? Record procstate over time.
6. Self-update through the DO PackageInstaller session with a non-testOnly build: does it install, keep the restricted-permission allowlist, and change the ECM state?
7. ECM on OxygenOS: `appops get <pkg> ACCESS_RESTRICTED_SETTINGS` after an adb install and after a self-update; whether trusted installers are configured.
8. A2 test: a throwaway package with `isAccessibilityTool=true`. Record the Play Protect reaction and whether obscured-touch views appear.
9. Can an a11y screenshot run at 3 fps without throttling? What happens on FLAG_SECURE apps?
10. `appops set <pkg> PROJECT_MEDIA allow`: is the consent dialog skipped on OxygenOS (only if A17 is pursued)?
11. Is `hidden_api_policy` writable from the app? (Information only; A22 stays rejected.)
12. `cmd role get-role-holders android.app.role.ASSISTANT`, and the same for CALL_SCREENING and DEVICE_POLICY_MANAGEMENT.
13. Does App Cloner (applisto) run with a DO present, if it is used?
14. AppFunctions flag: `pm list permissions -f | grep -A3 EXECUTE_APP_FUNCTIONS` (the protection level shows which flag branch is live).
15. After the first OTA with the DO present: DO retained, grants retained, update installed normally.

## Provisioning runbook (numbered checklist)

The phone-owning session runs this; there are no device actions from this lane. `PKG=dev.operator`, `ADMIN=dev.operator/.admin.OperatorAdminReceiver` (placeholder name for lane 01/07).

**A. Preparation (laptop)**
1. CI produces `operator-debug.apk` with `android:testOnly="true"`: a `DeviceAdminReceiver` (`BIND_DEVICE_ADMIN`), a `DeviceAdminService`, the a11y service, the notification listener, and all permissions declared.
2. Record `adb shell getprop ro.build.fingerprint` and the security patch into the device log.

**B. Backup**
3. Google backup "Back up now". OnePlus Clone Phone / local backup to the TOSHIBA disk. Export 2FA seeds or confirm cloud sync. Photos synced.
4. List Wallet cards (they will need re-verification after the account returns; **UNVERIFIED**).
5. Back up the data of the Parallel Apps clones (user 999), or accept losing it.

**C. Clear the preconditions**
6. `adb shell pm list users`, then remove every user except 0: Settings > Apps > Parallel Apps > turn off per app, then `adb shell pm remove-user 999` if it is still present.
7. `adb shell dumpsys account`, then remove every account on every user (Settings > Passwords & accounts). Do not open apps that re-register accounts until step 12.
8. Re-check: `pm list users` shows only user 0; `dumpsys account` shows 0 accounts.

**D. Install and provision**
9. `adb install -t operator-debug.apk`
10. `adb shell dpm set-device-owner dev.operator/.admin.OperatorAdminReceiver`. Expect "Success: Device owner set". An error names the failed precondition (F3).
11. Verify: `adb shell dpm list-owners`, and `adb shell dumpsys device_policy | grep -iE "owner|test"`.

**E. One-time grants (all persist)**
12. `adb shell pm grant dev.operator android.permission.WRITE_SECURE_SETTINGS`
13. `adb shell appops set dev.operator ACCESS_RESTRICTED_SETTINGS allow`
14. `adb shell appops set dev.operator GET_USAGE_STATS allow`, then `adb shell appops set dev.operator WRITE_SETTINGS allow`
15. `adb shell cmd deviceidle whitelist +dev.operator`
16. `adb shell cmd notification allow_listener dev.operator/.notify.OperatorListener` (placeholder)
17. Open the app once. It self-grants runtime permissions (F2), calls `setBackupServiceEnabled(true)`, `setUserControlDisabledPackages([self])` and `setUninstallBlocked(self)`, and enables its a11y service through the secure setting.
18. Set the Oplus UI toggles for operator: auto-launch on, background activity allowed, battery "unrestricted" (**UNVERIFIED** names).

**F. Verify without adb**
19. Unplug USB, turn wireless debugging off, reboot. Unlock. Check: a11y active, the DeviceAdminService bound (the app's self-check screen), and the notification listener active.
20. Run the M1 measurements 4, 5 and 9 in the next sessions.

**G. Restore the personal state**
21. Re-add the Google account, then the OnePlus account, then the app accounts. Restore Wallet cards. Confirm Google backup is on.
22. Parallel Apps cannot be re-created while the DO exists (DISALLOW_ADD_CLONE_PROFILE). Use an APK cloner in user 0 if needed, after checking that it works (measurement 13).

**H. Rollback** (any time)
23. testOnly build: `adb shell dpm remove-active-admin dev.operator/.admin.OperatorAdminReceiver`, then uninstall. This clears the clone, private and managed profile restrictions and re-enables backup (VERIFIED [2]).
24. Non-testOnly build: the owner-only "Release device owner" switch in the app (`clearDeviceOwnerApp`), then uninstall.
25. Last resort: factory reset from recovery (wipes data; FRP needs the Google account).

**Test plan for the unknowns:** measurements 1–15 above, in this order: 1–3 during provisioning, 4 and 9 on day 0, 5 over 24 h, then 6–8 and 12–14 in M1, and 10, 11 and 15 as needed.

## Sources (all accessed 2026-09-27)

1. AOSP `core/res/AndroidManifest.xml` @ android-16.0.0_r4: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/core/res/AndroidManifest.xml
2. `DevicePolicyManagerService.java` @ r4: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java
3. `PackageInstallerService.java` @ r4: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/core/java/com/android/server/pm/PackageInstallerService.java
4. `DevicePolicyManagerServiceShellCommand.java` @ r4: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerServiceShellCommand.java
5. `EnhancedConfirmationService.java` @ r4: https://android.googlesource.com/platform/packages/modules/Permission/+/refs/tags/android-16.0.0_r4/service/java/com/android/ecm/EnhancedConfirmationService.java
6. `AccessibilityManagerService.java` @ r4: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java
7. `AccessibilityServiceInfo.java` @ r4: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/core/java/android/accessibilityservice/AccessibilityServiceInfo.java
8. `AccessibilityService.java` @ r4: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/core/java/android/accessibilityservice/AccessibilityService.java
9. `AppStandbyController.java` @ r4: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/apex/jobscheduler/service/java/com/android/server/usage/AppStandbyController.java
10. `BackgroundActivityStartController.java` @ r4: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/core/java/com/android/server/wm/BackgroundActivityStartController.java
11. `ActiveServices.java` (mirror): https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/am/ActiveServices.java
12. `DeviceIdleController.java` (mirror): https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/apex/jobscheduler/service/java/com/android/server/DeviceIdleController.java
13. `MediaProjectionManagerService.java` (mirror): https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/media/projection/MediaProjectionManagerService.java
14. `VoiceInteractionService.java` (mirror): https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/service/voice/VoiceInteractionService.java
15. `VoiceInteractionManagerServiceImpl.java` and `VoiceInteractionSessionConnection.java` (mirror): https://github.com/aosp-mirror/platform_frameworks_base/tree/android16-release/services/voiceinteraction/java/com/android/server/voiceinteraction
16. `ActivityManagerService.java` `startInstrumentation` (mirror): https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/am/ActivityManagerService.java
17. `PackageManagerShellCommand.java` (mirror): https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java
18. `core/api/current.txt`, class DevicePolicyManager (mirror): https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/api/current.txt
19. PermissionController `roles.xml` @ r4: https://android.googlesource.com/platform/packages/modules/Permission/+/refs/tags/android-16.0.0_r4/PermissionController/res/xml/roles.xml
20. AppFunctions `CallerValidatorImpl.java` @ r4: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/appfunctions/java/com/android/server/appfunctions/CallerValidatorImpl.java
21. `DeviceAdminServiceController.java` @ r4: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/devicepolicy/java/com/android/server/devicepolicy/DeviceAdminServiceController.java
22. Android 16 CDD (§9.8 restricted settings): https://source.android.com/docs/compatibility/16/android-16-cdd
23. Restrictions on starting a foreground service from the background: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
24. Restrictions on starting activities from the background: https://developer.android.com/guide/components/activities/background-starts
25. Android 15 behaviour changes, all apps (OTP redaction, projection chip, lock stop): https://developer.android.com/about/versions/15/behavior-changes-all
26. Play Integrity verdicts: https://developer.android.com/google/play/integrity/verdicts
27. Android Developers Blog, "Enhancing Android security: stop malware from snooping on your app data" (2025-12): https://android-developers.googleblog.com/2025/12/enhancing-android-security-stop-malware.html
28. Restrictions on non-SDK interfaces: https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces
29. AVF framework API README: https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/tags/aml_net_351410000/libs/framework-virtualization/README.md
30. AVF use cases: https://source.android.com/docs/core/virtualization/usecases
31. Android developer verification: https://developer.android.com/developer-verification ; blog: https://android-developers.googleblog.com/2026/03/android-developer-verification.html
32. Android Authority, sideloading timeline (secondary): https://www.androidauthority.com/android-sideloading-changes-timeline-3679204/
33. Android Authority, Android 15 restricted settings (secondary): https://www.androidauthority.com/android-15-restricted-settings-sideloading-3481098/
34. Don't kill my app, OnePlus (community): https://dontkillmyapp.com/oneplus
35. XLogcatManager README (secondary, Android 13 LogcatManager behaviour): https://github.com/agnostic-apollo/XLogcatManager
36. Android Authority, Advanced Protection and accessibility (secondary): https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860/
37. Hail issue #317 ("several users" error, community): https://github.com/aistra0528/Hail/issues/317
38. Device report: /home/phaseonebig/op13/REPORT.md
39. Project README: /home/phaseonebig/projects/operator-design/README.md
40. `UserRestrictionsUtils.java` @ r4: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/core/java/com/android/server/pm/UserRestrictionsUtils.java
