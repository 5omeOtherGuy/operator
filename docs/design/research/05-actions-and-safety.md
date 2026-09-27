# 05: Tool/action API, effect verification and safety

Lane 05 of the operator design research (2026-09-27). Design only: no code, builds or device actions.
Tags: **VERIFIED [n]** = read in the numbered primary source; **UNVERIFIED** = plausible, with what would verify it;
**INFERENCE** = my reasoning or a design choice, not a fact. Numbers that depend on the device are marked
"measure" and listed in the on-device measurement section.

Note on sources: AOSP Java sources were read from the `aosp-mirror/platform_frameworks_base` `main` branch,
whose last commit is dated 2025-03-27 [1]. That is Android 16 development code, not the released Android 16
build and not OxygenOS. Anything that OxygenOS 16.0.10.501 could change is listed as a device measurement.

## Scope (questions covered)

1. Which actions can the operator take, and what risk class does each have (read-only / reversible /
   irreversible / forbidden)? Covers accessibility node actions, `dispatchGesture`, global actions, intents
   and direct APIs, `DevicePolicyManager` (DPM) as device owner, and `Settings.Secure`/`Global` through
   `WRITE_SECURE_SETTINGS`.
2. What typed tool schema does the model see (Kotlin + JSON Schema)? How are arguments validated and
   which allowlists apply?
3. How do we check that an action had its effect: per-tool postconditions, waiting on a11y events,
   timeouts and idle detection?
4. How do we build a confirmation gate that the operator's own accessibility service cannot press?
   Which of the proposed mechanisms actually work?
5. How do we defend against prompt injection from screen text, notifications and web pages?
6. Where are the permission boundaries on device-owner power, what goes on the sensitive-app
   denylist, and what does the audit log contain?
7. Kill switch (which mechanisms, how fast do they stop gestures and native inference) and rate limits.

Out of scope: screen serialisation and the agent loop (lane 04), inference (02), `decide()`/CLM internals
(03), provisioning and keeping access alive (06), evaluation/CI (07).

## Findings (tagged VERIFIED [n] / UNVERIFIED / INFERENCE)

### F1. Accessibility actions and global actions

- `dispatchGesture` "Dispatch a gesture to the touch screen. Any gestures currently in progress, whether
  from the user, this service, or another service, will be cancelled." It requires the
  `canPerformGestures` capability in the service meta-data. VERIFIED [5]
- The injector cancels an injected gesture when a real touch arrives: `MotionEventInjector.onMotionEvent`
  calls `cancelAnyPendingInjectedEvents()` for any incoming event, with an exception for mouse hover.
  So the user touching the screen aborts an in-flight agent gesture. VERIFIED [3]
- `MotionEventInjector.onDestroy()` cancels pending injected events and makes later injections fail
  (`onPerformGestureResult(sequence, false)`). VERIFIED [3]. It is INFERENCE that disabling our service
  tears down the injector: the injector exists only while some service can perform gestures.
- Global actions defined in source: BACK 1, HOME 2, RECENTS 3, NOTIFICATIONS 4, QUICK_SETTINGS 5,
  POWER_DIALOG 6, TOGGLE_SPLIT_SCREEN 7, LOCK_SCREEN 8, TAKE_SCREENSHOT 9, KEYCODE_HEADSETHOOK 10,
  ACCESSIBILITY_BUTTON 11, ACCESSIBILITY_BUTTON_CHOOSER 12, ACCESSIBILITY_SHORTCUT 13,
  ACCESSIBILITY_ALL_APPS 14, DISMISS_NOTIFICATION_SHADE 15, DPAD_UP..CENTER 16–20, MENU 21,
  MEDIA_PLAY_PAUSE 22. VERIFIED [5]
- `ACTION_SET_TEXT` "sets the text of the node. Performing the action without argument, using null or empty
  CharSequence will clear the text." `AccessibilityNodeInfo` exposes `isPassword()` and
  `isAccessibilityDataSensitive()`. VERIFIED [7]
- `takeScreenshot` fails with `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` on `FLAG_SECURE` content. The hidden
  minimum interval between screenshots is `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS = 333`.
  VERIFIED [5]
- `onKeyEvent` (needs `FLAG_REQUEST_FILTER_KEY_EVENTS`) receives key events "before they are passed to the
  rest of the system... before they are passed to the device policy, the input method, or applications".
  Returning true consumes the event. VERIFIED [5, 6]
- An accessibility service has no API to inject hardware key events such as VOLUME_UP. It can only use
  the global actions above (HEADSETHOOK, DPAD, MENU, MEDIA_PLAY_PAUSE) and text input into the focused
  editor. INFERENCE from the API surface [5]. Key events injected on behalf of accessibility carry
  `AKEY_EVENT_FLAG_IS_ACCESSIBILITY_EVENT`. VERIFIED [4]
- `UiAutomation.waitForIdle(idleTimeoutMillis, globalTimeoutMillis)` defines idle as no accessibility event
  within `idleTimeoutMillis`, bounded by a global timeout. It is not available to an accessibility
  service. VERIFIED [8]. We reimplement the same semantics on our own event stream (INFERENCE).

### F2. What an a11y service can and cannot see or press (the gate question)

- `View.ACCESSIBILITY_DATA_SENSITIVE_YES`: "Only allow interactions from AccessibilityServices with the
  isAccessibilityTool property set to true." Under `AUTO`, a view is data-sensitive when it sets
  `filterTouchesWhenObscured` or when any parent is data-sensitive. VERIFIED [1]
- Enforcement in `View.includeForAccessibility(forNodeTree)`: if the request does not come from an
  accessibility tool and the view is data-sensitive, the view is left out of the node tree. Events are
  filtered per recipient service in `AccessibilityManagerService`. VERIFIED [1]. So a service declared
  `isAccessibilityTool=false` cannot obtain such a node and so cannot `performAction` on it (INFERENCE).
- `isAccessibilityTool` is read from the service's manifest meta-data and cannot be changed at runtime
  (`setAccessibilityTool` is `@SystemApi`, "not dynamically configurable"). VERIFIED [6]
- Data-sensitivity does not block **coordinate taps**. `dispatchGesture` taps are delivered as real touch
  events. No code path in [1] or [3] drops them for data-sensitive views (INFERENCE from reading both).
  They are, however, marked:
  - `MotionEventInjector` adds `FLAG_INJECTED_FROM_ACCESSIBILITY` to injected events. The source comment
    says this is "to let applications distinguish it from events injected by other means". VERIFIED [3]
  - `InputDispatcher` turns that policy flag into `AMOTION_EVENT_FLAG_IS_ACCESSIBILITY_EVENT` on
    motion events and `AKEY_EVENT_FLAG_IS_ACCESSIBILITY_EVENT` on key events. VERIFIED [4]
  - Java `MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT = 0x800` is `@hide @TestApi`: "this event was modified
    by or generated from an accessibility service". VERIFIED [2]. An app can still test
    `event.flags and 0x800` because `getFlags()` is public. The constant is not public API, so the value
    could change (INFERENCE; risk R4).
  - `View.dispatchTouchEvent` calls the overridable `onFilterTouchEventForSecurity(event)`. The default
    drops touches only when `FILTER_TOUCHES_WHEN_OBSCURED` is set and the window is obscured.
    VERIFIED [1]
- Consequence: a gate button that (a) is data-sensitive, (b) overrides `onFilterTouchEventForSecurity`
  to drop events with flag 0x800, and (c) refuses `performAccessibilityAction(ACTION_CLICK)` in its
  delegate cannot be pressed by our service through either the node path or the gesture path. This holds
  as long as the service is declared `isAccessibilityTool=false` (INFERENCE built on [1–4]; must be
  measured on OxygenOS, M1-G1).
- Declaring `isAccessibilityTool=false` also hides data-sensitive views in **other** apps (payment
  confirmations and similar) from the agent. For this project that is a safety feature (INFERENCE).
- Android 17's Advanced Protection Mode, which the user opts into, blocks accessibility services that are
  not `isAccessibilityTool="true"`. VERIFIED [30] (press report, not an AOSP document). It does not apply
  on Android 16, but if the owner ever turns it on, the operator loses its hands (risk R9).
- `setFilterTouchesWhenObscured(true)` plus `Window.setHideOverlayWindows(true)` (permission
  `HIDE_OVERLAY_WINDOWS`, protectionLevel `normal`, VERIFIED [11]) protects the gate from tapjacking by
  other apps' overlays (INFERENCE for the combined effect).

### F3. Intents and direct APIs

- `SmsManager.sendTextMessage` requires `SEND_SMS`. "If and only if an app is not selected as the default
  SMS app, the system automatically writes messages sent using this method to the SMS Provider."
  `sentIntent` reports `RESULT_OK` or a code such as `RESULT_ERROR_SHORT_CODE_NOT_ALLOWED`,
  `RESULT_ERROR_LIMIT_EXCEEDED` or `RESULT_ERROR_NO_SERVICE`. VERIFIED [13]. **So the default-SMS role is
  not needed to send.** Two conditions remain:
  - `SEND_SMS` is declared `permissionFlags="costsMoney|hardRestricted"`, and so is `READ_SMS`.
    VERIFIED [11]. An app can hold a hard-restricted permission only if its installer allowlisted it.
    VERIFIED [10]. `pm install` allowlists restricted permissions by default and offers
    `--restrict-permissions` to opt out, so a one-time `adb install` covers it. VERIFIED [12]. Whether the
    allowlisting survives the operator **self-updating** through PackageInstaller as device owner is
    UNVERIFIED (M2-P3).
  - OxygenOS/ColorOS may add its own "app wants to send SMS" confirmation. UNVERIFIED (M1-D3).
- `Intent.ACTION_CALL` "cannot be used to call emergency numbers" and needs `CALL_PHONE`. If the
  permission is declared but not granted, it throws `SecurityException`. VERIFIED [14]
- `AlarmClock.ACTION_SET_ALARM`/`ACTION_SET_TIMER` with `EXTRA_SKIP_UI` need the normal-level permission
  `com.android.alarm.permission.SET_ALARM`. VERIFIED [11, 15]. `AlarmManager.getNextAlarmClock()` returns
  only the **next** alarm clock set by any app through `setAlarmClock`, and broadcasts
  `ACTION_NEXT_ALARM_CLOCK_CHANGED`. VERIFIED [16]. So it proves a new alarm only when that alarm is the
  earliest one. No public API lists all alarms or timers (INFERENCE from [15, 16]).
- `MediaSessionManager.getActiveSessions(listener)` works for an enabled notification listener (pass its
  ComponentName) or with `MEDIA_CONTENT_CONTROL`. VERIFIED [17]
- Android 15+ hides OTP/2FA notification content from "untrusted" notification listeners, which see
  "sensitive notification content hidden". Only apps holding `RECEIVE_SENSITIVE_NOTIFICATIONS` through a
  role or a platform signature get the content. VERIFIED [29] (press report). The operator's listener will
  not see OTPs in notifications. The a11y tree of an SMS app or of the shade may still show them
  (INFERENCE; M1-D6).
- CalendarContract inserts need `WRITE_CALENDAR`. If the calendar syncs to a Google account and the event
  has attendees, sync sends invitations, which makes the insert irreversible in effect. INFERENCE; lane
  owner should check against the CalendarContract docs.

### F4. Device-owner powers (DevicePolicyManager)

- Silent install: a PackageInstaller commit completes "automatically", with no user intervention, when the
  caller is the device owner. The device owner may also uninstall any package. VERIFIED [10]
- `setPermissionGrantState`: admins targeting API 29+ "can grant and revoke permissions of all apps".
  On Android 12+ the device owner "by default, may continue granting" sensor permissions (location,
  camera, mic, activity recognition, body sensors) unless provisioning opted out. VERIFIED [9]. Whether
  the device owner can grant a hard-restricted permission that is not allowlisted (SEND_SMS) is
  UNVERIFIED (M2-P4).
- `setPackagesSuspended`: a suspended package cannot start activities; its notifications are hidden.
  "Some apps cannot be suspended, such as device admins, the active launcher, the required package
  installer... the default dialer, and the permission controller." VERIFIED [9]
- `reboot()` is device-owner only and throws `IllegalStateException` during an ongoing call.
  VERIFIED [9]
- `setGlobalSetting` is "mostly deprecated". It currently covers `ADB_ENABLED`,
  `USB_MASS_STORAGE_ENABLED`, `STAY_ON_WHILE_PLUGGED_IN` and `WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN`.
  `setSecureSetting` covers a short list (`DEFAULT_INPUT_METHOD`, `SKIP_FIRST_USE_HINTS`, ...).
  VERIFIED [9]. `WRITE_SECURE_SETTINGS` reaches far more keys, including `enabled_accessibility_services`,
  which is why the operator holds it (README). So the model gets **no** raw settings tool (INFERENCE).
- Present in the DPM source and dangerous: `wipeData`/`wipeDevice`, `removeUser`, `transferOwnership`,
  `clearDeviceOwnerApp`, `setKeyguardDisabled`, `installSystemUpdate`, `setSystemUpdatePolicy`,
  `setUninstallBlocked`, `setLockTaskPackages`, `setUserControlDisabledPackages`, `addUserRestriction`.
  VERIFIED [9] for their existence and device-owner gating. The forbidden list below is INFERENCE.

### F5. Native inference stop latency (kill switch)

- `llama_context_params.abort_callback`: "if it returns true, execution of llama_decode() will be
  aborted; currently works only with CPU execution." Also `llama_set_abort_callback(ctx, cb, data)`.
  VERIFIED [18] at the commit op13 pinned (`95887577`).
- The upstream Android JNI lib (`examples/llama.android/lib`, `ai_chat.cpp`) generates **one token per JNI
  call** (`generateNextToken`). It prefills in batches of `BATCH_SIZE = 512` tokens (context 8192,
  temperature 0.3, 2–4 threads). It does not set an abort callback. VERIFIED [19]. So stopping between
  tokens takes about one decode step. Stopping during prefill takes up to one 512-token batch unless we add
  the CPU abort callback. Both durations must be measured (M1-K3). On a GPU/NPU backend the abort callback
  does not fire mid-graph [18].
- llama.cpp ships `common/json-schema-to-grammar.cpp`, which can constrain output to the tool JSON Schema.
  VERIFIED [20]. Its use is lane 02/04's call.

### F6. Prompt injection against mobile GUI agents (literature)

- Pop-ups crafted against VLM computer agents: "attack success rate of 86% on average and decreases the task
  success rate by 47%. Basic defense techniques, such as asking the agent to ignore pop-ups or including
  an advertisement notice, are ineffective." VERIFIED [21]
- Active environmental injection on Android (AEIA-MN): "maximum attack success rate of 93% on the
  AndroidWorld benchmark". VERIFIED [22]
- GhostEI-Bench (spoofed notifications, overlays, pop-ups in dynamic Android environments): "current
  models systematically fail to perceive and reason about manipulated UIs". VERIFIED [23]
- MobileWorldSafety (2026-08): six agents, "attack success rates ranging from 40.4% to 66.9%".
  VERIFIED [24]. MobileSafetyBench: baseline agents "often fail to effectively prevent harm". VERIFIED [25]
- Defenses: spotlighting/datamarking cut indirect-injection ASR from >50% to <2% on GPT-class models.
  VERIFIED [28]. It is UNVERIFIED for a 1-bit 8B model (M1-S1). System-level designs give guarantees
  that do not depend on the model: CaMeL separates control flow from data flow and adds capabilities,
  solving 77% of AgentDojo tasks with provable security versus 84% undefended. VERIFIED [26]. The design
  patterns paper names Action-Selector, Plan-Then-Execute, LLM Map-Reduce, Dual LLM, Code-Then-Execute
  and Context-Minimization. VERIFIED [27]
- Conclusion: with attack success rates of 40–93% on stronger models, prompting defenses cannot be the
  boundary. The boundary must be the typed executor, allowlists and the gate (INFERENCE).

### F7. Current draft

- op13's draft `ScreenService` registers a `READ_SCREEN` receiver guarded by `android.permission.DUMP`,
  which only the shell holds. `ScreenReader` indexes every visible text-, description-, id- or
  actionable node and keeps a reference to the `AccessibilityNodeInfo` [34]. Tool arguments can therefore
  address `el` indices. Indices are only valid for the snapshot that produced them (INFERENCE; see the
  `snapshot` argument below).

## Options (table: option | what it gives | cost / risk | evidence)

### Gate mechanisms

| Option | What it gives | Cost / risk | Evidence |
|---|---|---|---|
| G-a Executor lock only (irreversible tool returns NEEDS_CONFIRMATION; release only from gate callback) | Model cannot bypass by calling the tool again | Does not stop the model tapping the gate's own button via UI tools | INFERENCE |
| G-b Gate activity in separate process `:gate`, buttons data-sensitive (`filterTouchesWhenObscured`), service `isAccessibilityTool=false` | Our service cannot see or `performAction` on the button | Coordinate taps still reach it | VERIFIED [1, 6] |
| G-c G-b + `onFilterTouchEventForSecurity` drops `flags and 0x800` + a11y delegate refuses clicks | Gesture taps from any a11y service are dropped | Hidden constant; OEM could differ | VERIFIED [2, 3, 4]; measure M1-G1 |
| G-d Executor refuses every UI action whose target window belongs to `dev.operator` and every action while the gate is shown | Defence in depth, pure code | None | INFERENCE |
| G-e BiometricPrompt, `BIOMETRIC_STRONG` only (no device-credential fallback) | Needs a finger; a11y cannot produce one | Friction; SystemUI prompt layout must be measured | INFERENCE; M1-G2 |
| G-f Hardware-key confirm (e.g. hold volume-up 1 s while gate shown), read in `onKeyEvent` | A11y cannot inject volume keys; injected keys are flagged | Volume keys may already be bound to other things; screen-off delivery unknown | VERIFIED [4, 5]; M1-K1 |
| G-g Gate as TYPE_ACCESSIBILITY_OVERLAY drawn by our own service | No extra permission | Same process as the hands; weakest separation | INFERENCE, rejected |

### Kill switch mechanisms

| Option | What it gives | Cost / risk | Evidence |
|---|---|---|---|
| K-a Volume-key chord in `onKeyEvent` (e.g. vol-down ×3 within 1 s) | Physical, works in any app | Must avoid the system a11y shortcut (hold both volume keys 3 s [31]) and OEM chords; lock-screen and screen-off delivery unknown | VERIFIED [5, 31]; M1-K1 |
| K-b Stop action on the foreground-service notification | Always one pull away | Needs the shade | INFERENCE |
| K-c Quick Settings tile (TileService) | Two taps | Needs the shade | INFERENCE |
| K-d Floating stop button (a11y overlay) | One tap, visible while running | Takes screen space; the serializer must leave it out | INFERENCE; lane 04 |
| K-e System a11y shortcut targeting the operator service | OS-level hard stop: service unbinds, injector destroyed | The watchdog (lane 06) must not re-enable it | VERIFIED [3, 31]; INFERENCE |
| K-f User touches the screen | Cancels any in-flight injected gesture | Does not stop the next step | VERIFIED [3] |

### Injection defenses

| Option | What it gives | Cost / risk | Evidence |
|---|---|---|---|
| I-a Delimit and provenance-tag every observation (`<obs src=pkg trust=untrusted>`) | Cheap; helps the model | Not a boundary; small models may ignore it | [28]; M1-S1 |
| I-b Datamarking (interleave a marker in untrusted text) | Large ASR reduction on big models | May hurt comprehension for a 1-bit model | [28]; M1-S1 |
| I-c Per-task tool allowlist fixed **before** any screen text is read (plan-then-execute) | Injection cannot widen capabilities | Needs an intent classifier; wrong class blocks the task (fail-safe) | [27] |
| I-d Taint check on irreversible arguments (value appears in untrusted observations but not in the user's request → gate with a "from screen" warning) | CaMeL-lite, no second model | Substring heuristic misses paraphrase | [26]; INFERENCE |
| I-e Dual-LLM / quarantined reader (CLM decide() on untrusted text, generator sees only symbols) | Strong isolation | Large change to the loop; lane 04/03 | [26, 27] |

## Recommendation (rationale; what is decided now vs deferred to an on-device measurement)

### R1. Architecture of the hands (proposal to lane 01)

Three trust zones, in separate processes:

1. `:agent`: llama.cpp, loop and prompt. Untrusted output.
2. The main process holds the **executor**: the a11y service, the DPM receiver, the direct-API
   adapters, the policy engine, the audit log and the kill switch.
3. `:gate`: the confirmation activity and the approval key.

The model produces only a typed `ToolCall`. The executor parses it, validates it, applies policy, and
then runs it or refuses. Approval of an irreversible call is an HMAC over `(taskId, step, canonical args)`,
minted in `:gate` only after a genuine user press. The executor checks it before execution. So no
`ToolCall`, not even a replayed one, can run an irreversible action without a fresh human press.
INFERENCE; the process split is lane 01's decision.

### R2. Tool catalogue (decided now; the classes are policy, not facts)

Classes: **R0** read-only, **R1** reversible (runs without asking, rate-limited, audited), **R2**
irreversible (gate: in-app tap), **R3** irreversible and high-power (gate: BiometricPrompt STRONG or
hardware key), **F** forbidden (no tool exists; the CI lint in lane 07 checks that the registry never
references it).

| Tool (model-facing) | Backing API | Class | Postcondition (see R3) |
|---|---|---|---|
| `read_screen` | a11y windows/nodes (lane 04) | R0 | returns snapshot id |
| `screenshot_internal` | `AccessibilityService.takeScreenshot` (≥333 ms apart [5]) | R0 | bitmap or SECURE_WINDOW error |
| `list_notifications` | NotificationListenerService | R0 | n/a |
| `click(el)` / `long_click(el)` | `ACTION_CLICK` / `ACTION_LONG_CLICK`, fallback tap on bounds | R1, **R2 if the target is classified irreversible** (below) | event from target window + tree change |
| `set_text(el, text)` | `ACTION_SET_TEXT` | R1; refused on `isPassword()` nodes | `refresh()`; normalised text equals |
| `scroll(el, dir)` | `ACTION_SCROLL_FORWARD/BACKWARD` | R1 | `TYPE_VIEW_SCROLLED` or child set changed, else "at end" |
| `focus(el)` | `ACTION_FOCUS` | R1 | `isFocused` |
| `tap(x,y)` / `swipe(x1,y1,x2,y2,ms)` | `dispatchGesture`, duration ≤1000 ms | R1 / R2 by target at (x,y) | `onCompleted` + tree change |
| `back`, `home`, `recents`, `open_notifications`, `open_quick_settings`, `dismiss_shade`, `split_screen`, `dpad_*`, `menu` | global actions [5] | R1 | window-state change |
| `lock_screen` | `GLOBAL_ACTION_LOCK_SCREEN` | R1 (ends the agent's UI reach) | keyguard shown |
| `media(play/pause/next/prev/seek)` | MediaController via listener [17]; `GLOBAL_ACTION_MEDIA_PLAY_PAUSE` fallback | R1 | `PlaybackState` changes |
| `launch_app(package)` | `getLaunchIntentForPackage` only | R1 | `TYPE_WINDOW_STATE_CHANGED` from package |
| `set_alarm(h,m,label,days)` / `set_timer(s,label)` | `ACTION_SET_ALARM/TIMER` + `EXTRA_SKIP_UI` [15] | R1 (undo via UI) | `getNextAlarmClock()` if earliest; else UI check [16] |
| `dismiss_alarm` | `ACTION_DISMISS_ALARM` | R1 | next alarm changed |
| `calendar_insert(...)` no attendees | `CalendarContract` insert | R1 (delete by id) | query by `_ID` |
| `calendar_insert(...)` with attendees, `calendar_delete` | same | **R2** | query |
| `send_sms(to, body)` | `SmsManager` for subscription [13] | **R2** | `sentIntent` RESULT_OK; Sent-box row optional |
| `call(number)` | `ACTION_CALL` [14] | **R2** | call state OFFHOOK within T |
| `reply_notification(key, text)` / `notification_action(key, i)` | `Notification.Action` + RemoteInput | **R2** | action PendingIntent sent; UI check |
| `headset_hook` | `GLOBAL_ACTION_KEYCODE_HEADSETHOOK` (answers/ends calls) | **R2** | call state |
| `reboot` | DPM `reboot` [9] | **R3** | n/a (the process dies); audit before |
| `install_apk(uri)` | PackageInstaller as device owner [10] | **R3** | STATUS_SUCCESS + versionCode |
| `uninstall(package)` | PackageInstaller as device owner [10] | **R3**, denylist | `NameNotFoundException` |
| `set_permission(package, perm, state)` | `setPermissionGrantState` [9] | **R3** for GRANTED; R2 for DENIED/DEFAULT | `getPermissionGrantState` + `checkPermission` |
| `hide_app` / `suspend_app` (and undo) | `setApplicationHidden` / `setPackagesSuspended` [9] | **R2**, denylist | `isApplicationHidden` / suspended-list return value |
| `open_url` / arbitrary `Intent` / deep link | n/a | **F** (exfiltration and side-effect channel); browsing only through `launch_app` + UI tools | n/a |
| `power_dialog`, `global_screenshot` (saves to gallery, may sync), `accessibility_button/_chooser/_shortcut/_all_apps` | global actions | **F** | n/a |
| `put_secure_setting` / `put_global_setting` / `setSecureSetting` / `setGlobalSetting` | `WRITE_SECURE_SETTINGS`, DPM | **F** for the model; the operator uses them internally only for its own a11y/listener re-enable (lane 06) | n/a |
| `wipeData`, `wipeDevice`, `removeUser`, `transferOwnership`, `clearDeviceOwnerApp`, `setKeyguardDisabled`, `resetPassword*`, `installSystemUpdate`, `setSystemUpdatePolicy`, `addUserRestriction`/`clearUserRestriction`, `setUninstallBlocked`, `setLockTaskPackages`, `setUserControlDisabledPackages`, `installCaCert`/`installKeyPair`, `setAlwaysOnVpnPackage`, `setRecommendedGlobalProxy`, `setGlobalPrivateDns`, `setPermissionPolicy`, `setDelegatedScopes`, `createAndManageUser`, network/security logging | DPM [9] | **F**; owner-only features go in the operator's own settings UI, behind BiometricPrompt, never in the tool registry | n/a |
| `ask_user(question)`, `finish(summary)` | loop control | R0 | n/a |

**Irreversibility of UI-path targets** (`click`, `tap`, `set_text`+IME action): the class depends on the
target, not the tool. The executor classifies the target before acting:

1. The window package is on the **sensitive denylist** → refuse (see R5).
2. The label, content description or id of the target or its nearest clickable ancestor matches the
   irreversible-verb lexicon in EN+DE: send/senden, pay/bezahlen/kaufen, buy, order/bestellen,
   delete/löschen, remove/entfernen, confirm/bestätigen, install/installieren, allow/zulassen, transfer,
   post/posten, publish, submit, sign/unterschreiben, accept/annehmen, call/anrufen, and a send-icon id
   pattern → R2.
3. `decide()` (lane 03, CLM) returns a probability that the action commits something irreversible. At or
   above a threshold (measured, M1-S2), the target is R2. CLM is an **extra** signal and never downgrades
   a lexicon hit.
4. Otherwise R1.

The lexicon and CLM are heuristics. They reduce but do not eliminate silent irreversible taps. That is
why R1 actions are also rate-limited and confined to the per-task app allowlist (INFERENCE).

**Typed schema.** Kotlin is the source of truth; the JSON Schema is generated from it and fed to the prompt
and, optionally, the llama.cpp grammar [20]:

```kotlin
@Serializable sealed interface ToolCall { val snapshot: String? }   // snapshot id the indices refer to
@Serializable @SerialName("click") data class Click(val el: Int, override val snapshot: String) : ToolCall
@Serializable @SerialName("set_text") data class SetText(val el: Int, val text: String, override val snapshot: String) : ToolCall
@Serializable @SerialName("set_alarm") data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null,
    val days: List<DayOfWeek> = emptyList(), override val snapshot: String? = null) : ToolCall
@Serializable @SerialName("send_sms") data class SendSms(val to: ContactRef, val body: String, override val snapshot: String? = null) : ToolCall
@Serializable sealed interface ContactRef   // {"contact_id": 42} or {"number": "+4915..."}
sealed interface ToolResult { data class Ok(val evidence: Evidence); data class Failed(val reason: String)
  data class Unverified(val why: String); data class Refused(val rule: String)
  data class NeedsConfirmation(val pendingId: String); object Cancelled }
```

```json
{"oneOf":[
 {"type":"object","required":["tool","el","snapshot"],"additionalProperties":false,
  "properties":{"tool":{"const":"click"},"el":{"type":"integer","minimum":0},"snapshot":{"type":"string","pattern":"^s[0-9]+$"}}},
 {"type":"object","required":["tool","hour","minute"],"additionalProperties":false,
  "properties":{"tool":{"const":"set_alarm"},"hour":{"type":"integer","minimum":0,"maximum":23},
   "minute":{"type":"integer","minimum":0,"maximum":59},"label":{"type":"string","maxLength":60},
   "days":{"type":"array","items":{"enum":["MO","TU","WE","TH","FR","SA","SU"]},"uniqueItems":true}}},
 {"type":"object","required":["tool","to","body"],"additionalProperties":false,
  "properties":{"tool":{"const":"send_sms"},"body":{"type":"string","minLength":1,"maxLength":480},
   "to":{"oneOf":[{"type":"object","required":["contact_id"],"properties":{"contact_id":{"type":"integer"}}},
                  {"type":"object","required":["number"],"properties":{"number":{"type":"string","pattern":"^\\+?[0-9]{3,15}$"}}}]}}}
]}
```

**Validation, in order, all in the executor:**

1. Parse strictly: unknown fields reject.
2. Schema ranges.
3. `snapshot` equals the current snapshot generation. A stale index returns `Refused("stale")` and a
   fresh read.
4. `el` exists, is visible and enabled, and supports the action.
5. `(x,y)` lies inside the display and outside operator-owned windows and overlays.
6. The package is installed and not denylisted, and it is in the per-task app allowlist (R4).
7. SMS/call numbers are not emergency or short codes. Premium short codes also trigger a system prompt
   [13]. Emergency-number detection on OxygenOS is M1-D4.
8. Text: length cap; no `set_text` into `isPassword()`.
9. Rate limits (R6).
10. Risk class, then gate.

### R3. Verification contract (decided now; timeouts deferred to M1-V1)

Every tool call runs `pre-snapshot → act → wait → post-check → ToolResult`, and the result is the next
observation. `Ok` requires **positive evidence**. A dispatch return value alone never counts:
`performAction` returning true only means the action was delivered (INFERENCE). Evidence types:

- **UI tools:**
  1. Wait for the first a11y event from the target window, or `onCompleted` from
     `GestureResultCallback`, within `T_react`.
  2. Then wait for **idle**: no `TYPE_WINDOW_CONTENT_CHANGED`/`WINDOW_STATE_CHANGED`/`VIEW_SCROLLED`
     from the foreground window for `T_quiet`, bounded by `T_max`. These are the `waitForIdle` semantics
     [8].
  3. Then compare the post-snapshot with the pre-snapshot: tree hash changed, or tool-specific
     (`set_text`: `refresh()` text equals the input after normalisation; `scroll`: first visible child
     changed).
  4. Timeout without a change returns `Unverified`, not `Failed`. `onCancelled` returns `Cancelled`
     (for example, the user touched the screen [3]).
  5. Animated screens that never go idle return `Ok/Unverified` with `idle=false`.
  6. Events whose package is `dev.operator`, or which come from our own overlays, are excluded from the
     idle calculation (the self-event filter).
- **Direct APIs:**
  - Alarm: `ACTION_NEXT_ALARM_CLOCK_CHANGED` + `getNextAlarmClock().triggerTime` equals the requested
    time [16]. If the new alarm is not the earliest, `Unverified`, and the loop may read the Clock UI.
  - Timer: no API; check the Clock app's timer notification through the listener (M1-D2).
  - Calendar: query by returned `_ID`.
  - SMS: `sentIntent` result code [13]; Sent-box row only if `READ_SMS` is held.
  - Call: `TelephonyCallback` call state OFFHOOK within `T_react`.
  - Media: `PlaybackState.state` changes.
  - Launch: window-state event with `packageName == target`.
  - Install/uninstall: `EXTRA_STATUS` + `PackageManager` lookup [10].
  - Permission: `getPermissionGrantState` [9].
- **Loop guard:** three consecutive `Unverified` results or identical tree hashes after the same tool and
  arguments stop the task with `ask_user` (INFERENCE).

Starting values to measure, not facts: `T_react` 1.5 s, `T_quiet` 400 ms, `T_max` 5 s for UI;
`T_react` 10 s for SMS/call (M1-V1).

### R4. Prompt-injection defense (decided now)

The boundary is the executor, not the prompt:

1. **Provenance:** every observation carries `src` (package/window, notification key or URL host) and
   `trust=untrusted`. Only the user's utterance and operator UI text are `trusted`. Lane 04 renders the
   delimiters.
2. **Per-task capability set** chosen from the user's utterance **before** any screen text is read
   (Plan-Then-Execute [27]): tool subset + app allowlist. Example: "set an alarm for 7" →
   `{set_alarm, read_screen, launch_app(clock), UI tools in clock}`. The model cannot widen it. Widening
   takes `ask_user` and a user answer typed or spoken in operator UI.
3. **Taint check** on R2/R3 arguments: if a recipient, number, URL-like string, package or ≥20-char text
   span occurs in an untrusted observation but not in the trusted utterance, the gate shows a red "this
   value came from the screen of <pkg>" line, and the action is never auto-approved [26].
4. **No action justified only by screen text:** tools with `NeedsConfirmation` show the typed arguments
   rendered by the executor, never the model's prose.
5. **Delimiting/datamarking** [28] as a model-side aid, adopted only if M1-S1 shows it does not break task
   success.

Residual risk accepted: an injected page can steer R1 navigation inside allowlisted apps (INFERENCE).

### R5. Permission boundaries, denylist and gate (decided now; measurements for the gate)

- **Gate** = G-a + G-b + G-c + G-d for R2, plus G-e (BiometricPrompt STRONG) for R3.
  - The gate shows `<verb> <object>` from the typed arguments.
  - The confirm button enables only after 1 s on screen.
  - The gate uses `setFilterTouchesWhenObscured(true)` and `setHideOverlayWindows(true)`.
  - The a11y service is declared `isAccessibilityTool=false` for this reason [1, 6].
  - G-f (hardware key) is the fallback if M1-G1 shows OxygenOS strips the 0x800 flag.
- **Device-owner power:** the model sees only the typed R2/R3 wrappers above; the F rows have no tool.
  `setUninstallBlocked(dev.operator, true)` is set once by the operator itself, not by the model
  (INFERENCE; lane 06 decides).
- **Sensitive denylist** is enforced on read and write. On read, lane 04 does not serialise these windows
  and shows `[hidden: sensitive app]`. On write, the executor refuses.
  - Seed list: banking and payment apps, authenticators (Google Authenticator, Microsoft Authenticator,
    Aegis and others), password managers, `com.android.vending` purchase flows, Google/OnePlus account
    settings, the permission controller, the package installer UI, Settings subpages
    (Accessibility, Device admin apps, Developer options, Security, Special app access), and
    `dev.operator` itself.
  - Uninstall/hide/suspend denylist additionally: launcher, dialer, SystemUI, Settings, IME, telephony,
    Play services, and the clock app used for alarms.
  - The owner extends it in operator settings (see owner questions).

### R6. Kill switch, rate limits, audit log (decided now; latencies in M1-K)

**Stop semantics.** Whatever triggers the stop, it runs in the executor process:

1. Set the atomic `halted` flag. Every tool checks it at entry and between sub-steps; pending gate
   approvals are voided.
2. Tap and swipe durations are capped at 1000 ms by validation, so an in-flight gesture ends within 1 s.
   The user's own touch cancels it at once [3].
3. Signal `:agent` to stop. The JNI token loop checks a flag per token [19]. Prefill uses
   `llama_set_abort_callback` where the backend is CPU [18].
4. Write the audit record. Resume needs a tap in the operator activity (data-sensitive and 0x800-filtered
   like the gate).

Safety does not depend on inference stopping fast: after step 1 no model output can act (INFERENCE).

**Triggers:** K-a, the vol-down ×3 chord (final chord after M1-K1), plus K-b, K-c and K-d, and K-e as the
OS-level hard stop.

**Rate limits** (defaults for owner approval, not facts):

| Scope | Default limit |
|---|---|
| UI actions | ≤ 3/s |
| Steps per task | ≤ 60 |
| SMS | ≤ 5/h, ≤ 20/day |
| Calls | ≤ 5/h |
| Install/uninstall | ≤ 3/day |
| Permission/hide/suspend | ≤ 10/day |
| Same R2 tool+args | ≤ 1 per 60 s |

Exceeding a limit returns `Refused("rate")` and asks the user.

**Audit log.** Append-only, in the executor process's credential-encrypted app storage.

- Format: one JSON line per step. Fields: `ts`, `taskId`, `step`, `tool`, canonical args, `class`,
  `decision` (auto/gated/refused/confirmed-tap/confirmed-biometric/cancelled), `ToolResult`,
  `sha256(observation)`, `sha256(model output)`, `prev` (hash chain).
- Full screen text is **not** stored by default; typed arguments are stored in full for R2/R3 (owner
  question Q4).
- Rotation: 5 × 10 MB files; R2/R3 lines are also copied into a separate file kept for 180 days.
- Excluded from backup (`dataExtractionRules`). No model-facing tool reads or writes it. The user views
  and exports it from operator settings.
- Sizes and retention are defaults, not measurements.

### Deferred to device measurement

The OxygenOS behaviour of the 0x800 flag and of data-sensitive hiding (M1-G1), the kill chord (M1-K1),
the timeouts (M1-V1), the SMS OEM prompt (M1-D3), restricted-permission survival across self-update
(M2-P3) and the CLM threshold (M1-S2).

## Interfaces this lane assumes from other lanes

- **01 architecture:** a process split `:agent` / executor / `:gate` with Binder (AIDL) between them; the
  executor owns the kill switch; the operator settings UI hosts the owner-only features (denylist edits,
  rate limits, audit viewer, F-class device-owner actions if ever wanted).
- **02 inference:** the JNI loop exposes `requestStop()`, checked per token; the CPU abort callback is
  wired for prefill; it reports the backend in use (abort latency depends on it [18]); optional grammar
  from the tool JSON Schema [20].
- **03 decide()/CLM:** `decide(IrreversibleAction(targetLabel, windowPkg, context)) -> P(irreversible)`
  and `decide(TaskIntent(utterance)) -> capability-set id`. Both are typed, both are logged as in
  ADR-0012 [32], and both only **raise** risk or **narrow** capabilities, never the reverse.
- **04 screen/agent loop:** snapshot ids with a generation counter; observations with provenance and trust
  tags; sensitive windows and operator overlays left out of serialisation; the loop consumes `ToolResult`
  as the next observation, and `NeedsConfirmation` blocks the loop until the gate resolves.
- **06 control access/provisioning:**
  - The operator holds `SEND_SMS`, `CALL_PHONE`, `WRITE_CALENDAR` and `SET_ALARM`, plus an enabled
    notification listener.
  - The first install is via `adb install` so hard-restricted permissions are allowlisted [12].
  - The a11y watchdog does **not** re-enable the service after a user-initiated stop (K-e) until the user
    resumes in the app.
  - The service manifest declares `isAccessibilityTool=false`, `canPerformGestures`,
    `FLAG_REQUEST_FILTER_KEY_EVENTS` and `canRetrieveWindowContent`.
- **07 evaluation/CI:**
  - An injection test set built from [21–24] styles (pop-up, spoofed notification, web text).
  - A gate-bypass test (the agent tries `click`/`tap` on the gate; expected `Refused` and a dropped touch).
  - A CI lint that the tool registry references no F-class API.
  - Postcondition unit tests with fake adapters.

## Risks (table: risk | likelihood | impact | mitigation)

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Injected screen/notification/web text steers the agent into an irreversible action | High (40–93% ASR in literature [21–24]) | High | Executor gate on R2/R3, taint check, per-task capability set, no generic intent/URL tool |
| R2 | UI-path irreversible tap not recognised by lexicon/CLM (silent send/delete) | Medium | High | App allowlist per task, rate limits, CLM threshold tuned on the eval set, sensitive denylist, audit |
| R3 | Agent presses its own gate | Low after G-b/c/d | Critical | Data-sensitive + 0x800 filter + executor lock + HMAC approval; M1-G1 test in CI on device |
| R4 | `FLAG_IS_ACCESSIBILITY_EVENT` (hidden, 0x800) changes or OxygenOS does not set it | Low–Medium | High | G-d executor lock stays; fall back to G-f hardware key or G-e biometric for all R2 |
| R5 | Other apps' overlays tapjack the gate | Low | High | `filterTouchesWhenObscured`, `setHideOverlayWindows(true)` [1, 11] |
| R6 | The model uses device-owner power to weaken the device (install malware, grant permissions, suspend security apps) | Medium without controls | Critical | Only typed R3 wrappers + biometric; F list has no tools; install only from user-picked URIs; denylists |
| R7 | Kill switch misses: chord not delivered (screen off, OEM intercept) | Medium | Medium | Four independent triggers; OS-level K-e; user touch cancels gestures [3] |
| R8 | Inference keeps running after stop (GPU backend ignores abort [18]) | High on GPU | Low (battery only; actions already halted) | Per-token stop flag; kill `:agent` process if it has not stopped within 2 s (INFERENCE) |
| R9 | Android 17 Advanced Protection revokes non-tool a11y services [30] | Low (opt-in; device is on 16) | High | Owner told not to enable it; do not falsely declare `isAccessibilityTool=true` |
| R10 | SEND_SMS lost after DO self-update (restricted allowlist) or OEM SMS prompt | Medium | Medium | M2-P3/M1-D3; fallback via the Messages UI path with gate |
| R11 | Stale element index hits the wrong node after a screen change | Medium | Medium | Snapshot generation check; re-read on mismatch |
| R12 | Audit log leaks private content (SMS bodies) on backup/export | Low | Medium | CE storage, backup excluded, hashes for screen text, user-only export |
| R13 | Emergency or premium numbers dialled/texted via the UI path | Low | High | Number validation, `ACTION_CALL` cannot reach emergency [14], short-code system prompt [13], R2 gate on dialer "call" button |
| R14 | Runaway loop (spam, battery) | Medium | Medium | Step cap, identical-state loop guard, rate limits |
| R15 | Partial state when the OEM kills the service mid-action | Medium | Low–Medium | Audit "intent" line before the act and "result" line after; on restart report unfinished steps, never auto-retry R2/R3 |

## Owner-only questions (only what the owner alone can decide or provide; each with options and your recommended default)

1. **Which actions need which confirmation?** (a) all R2 tap, R3 biometric; (b) everything R2+ biometric;
   (c) allow per-contact standing approval for SMS/calls. **Default: (a).**
2. **Payments and purchases:** (a) forbidden entirely (denylist + data-sensitive hiding); (b) allowed
   behind biometric. **Default: (a).**
3. **Sensitive-app denylist:** which banking, authenticator and password apps do you use? Anything to add
   or remove from the seed list in R5? **Default:** the seed list, plus every installed app whose package
   matches bank/pay/auth/pass keywords, shown to you once for ticking.
4. **Audit-log content:** (a) full typed arguments for R2/R3 (SMS body, number), hashes for screen text;
   (b) hashes only; (c) full screen text too. Retention 5 × 10 MB rolling + 180 days for R2/R3.
   **Default: (a).**
5. **Rate limits and step cap:** accept the defaults in R6? **Default: yes.**
6. **Kill switch:** may the operator bind the system accessibility shortcut (hold both volume keys) to
   itself as the hard stop? Is vol-down ×3 acceptable as the soft chord? **Default: yes to both.**
7. **OTP codes:** may the model ever read one-time codes shown in SMS apps (e.g. to complete a login you
   asked for)? (a) never, redact; (b) only when the task names the service. **Default: (a).**
8. **Unknown numbers:** may the agent text or call numbers that are not in your contacts? (a) yes with the
   gate's red warning; (b) no. **Default: (a).**

## On-device measurements needed (for M1/M2)

M1 (a11y + direct APIs):

- **M1-G1** Gate bypass: from the service, `dispatchGesture` tap on the gate button, and `performAction`
  if the node is found. Expect: node absent from `getWindows()` trees, touch dropped (log the event flags;
  check 0x800 is set), gate stays pending. Repeat with `isAccessibilityTool` true in a throwaway build to
  confirm the difference.
- **M1-G2** BiometricPrompt STRONG window: can the service see or click anything that approves without a
  finger? Time from prompt to result.
- **M1-K1** `onKeyEvent` delivery of VOLUME_DOWN/UP: screen on, locked, screen off, media playing, in a
  call; conflicts with OxygenOS chords and the system a11y shortcut.
- **M1-K2** End-to-end stop latency per trigger (K-a…K-e): trigger → `halted` → last injected event, in ms.
- **M1-K3** Inference stop latency: time for one decode token and one 512-token prefill batch on the chosen
  backend (Bonsai 8B Q1_0); abort-callback latency on CPU.
- **M1-V1** Event-to-idle distribution (`T_react`, `T_quiet`) across 10 common apps (Clock, Messages,
  Phone, Chrome, WhatsApp-like, Settings, Calendar, Play Store browse, Gallery, launcher); share of screens
  that never go idle within 5 s.
- **M1-D1** OxygenOS Clock: does `ACTION_SET_ALARM` + `EXTRA_SKIP_UI` set silently? Does it use
  `setAlarmClock` (visible to `getNextAlarmClock`)?
- **M1-D2** Timer: is a Clock notification posted that the listener can match?
- **M1-D3** `SmsManager.sendTextMessage` from a non-default app: any OxygenOS confirmation dialog? Is the
  Sent-box row written?
- **M1-D4** Emergency-number detection API available to the operator without privileged permissions.
- **M1-D5** `getActiveSessions(listener)` with Spotify/YouTube Music-like apps.
- **M1-D6** OTP redaction: the listener sees "sensitive notification content hidden"; what does the a11y
  tree of the shade show?
- **M1-S1** Injection test set on Bonsai 8B: task success and attack success with/without delimiting and
  datamarking.
- **M1-S2** CLM `P(irreversible)` on 200 labelled buttons (EN+DE): precision/recall at candidate
  thresholds.

M2 (device owner):

- **M2-P1** Silent install/uninstall as device owner: status codes and duration.
- **M2-P2** `setPermissionGrantState` for CALL_PHONE, SEND_SMS, WRITE_CALENDAR, location.
- **M2-P3** Are hard-restricted permissions (SEND_SMS) still allowlisted and granted after the operator
  self-updates through PackageInstaller?
- **M2-P4** Can the device owner grant a hard-restricted permission to an app installed without
  allowlisting?
- **M2-P5** `setPackagesSuspended` refusals on OxygenOS system apps.
- **M2-P6** `reboot()` then service restore and the audit "unfinished step" report (with lane 06).

## Sources (numbered: URL or absolute path, with access date 2026-09-27)

AOSP files [1–3], [5–17] are from `raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/…`
(last commit 2025-03-27), unless noted otherwise. All accessed 2026-09-27.

1. View.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/view/View.java (ACCESSIBILITY_DATA_SENSITIVE_*, calculateAccessibilityDataSensitive, includeForAccessibility, onFilterTouchEventForSecurity)
2. MotionEvent.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/view/MotionEvent.java (FLAG_IS_ACCESSIBILITY_EVENT = 0x800, @hide @TestApi)
3. MotionEventInjector.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/accessibility/java/com/android/server/accessibility/MotionEventInjector.java
4. InputDispatcher.cpp (frameworks/native, main): https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/services/inputflinger/dispatcher/InputDispatcher.cpp (lines ~4850–4931: POLICY_FLAG_INJECTED_FROM_ACCESSIBILITY → *_FLAG_IS_ACCESSIBILITY_EVENT)
5. AccessibilityService.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/accessibilityservice/AccessibilityService.java
6. AccessibilityServiceInfo.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/accessibilityservice/AccessibilityServiceInfo.java
7. AccessibilityNodeInfo.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/view/accessibility/AccessibilityNodeInfo.java
8. UiAutomation.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/app/UiAutomation.java
9. DevicePolicyManager.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/app/admin/DevicePolicyManager.java
10. PackageInstaller.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/content/pm/PackageInstaller.java
11. Platform AndroidManifest.xml: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/res/AndroidManifest.xml (SEND_SMS costsMoney|hardRestricted; HIDE_OVERLAY_WINDOWS normal; SET_ALARM normal)
12. PackageManagerShellCommand.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/pm/PackageManagerShellCommand.java (`--restrict-permissions`)
13. SmsManager.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/telephony/java/android/telephony/SmsManager.java
14. Intent.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/content/Intent.java (ACTION_CALL)
15. AlarmClock.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/provider/AlarmClock.java
16. AlarmManager.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/apex/jobscheduler/framework/java/android/app/AlarmManager.java
17. MediaSessionManager.java: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/media/java/android/media/session/MediaSessionManager.java
18. llama.cpp `include/llama.h` @95887577: https://raw.githubusercontent.com/ggml-org/llama.cpp/master/include/llama.h (master = 95887577ab5f…, 2026-09-26)
19. llama.cpp `examples/llama.android/lib/src/main/cpp/ai_chat.cpp` @95887577: https://raw.githubusercontent.com/ggml-org/llama.cpp/95887577ab5fead779581a7030a83c7752ff3234/examples/llama.android/lib/src/main/cpp/ai_chat.cpp
20. llama.cpp `common/json-schema-to-grammar.cpp` @95887577: https://raw.githubusercontent.com/ggml-org/llama.cpp/95887577ab5fead779581a7030a83c7752ff3234/common/json-schema-to-grammar.cpp
21. Zhang, Yu, Yang, "Attacking Vision-Language Computer Agents via Pop-ups", arXiv 2411.02391: https://arxiv.org/abs/2411.02391
22. Chen, Hu, Yin, "Evaluating the Robustness of Multimodal Agents Against Active Environmental Injection Attacks", arXiv 2502.13053: https://arxiv.org/abs/2502.13053
23. Chen, Song, Chai et al., "GhostEI-Bench", arXiv 2510.20333: https://arxiv.org/abs/2510.20333
24. Chen, Li, Du et al., "MobileWorldSafety", arXiv 2608.17659: https://arxiv.org/abs/2608.17659v1
25. Lee, Hahm, Choi et al., "MobileSafetyBench", arXiv 2410.17520: https://arxiv.org/abs/2410.17520
26. Debenedetti, Shumailov, Fan et al., "Defeating Prompt Injections by Design" (CaMeL), arXiv 2503.18813: https://arxiv.org/abs/2503.18813
27. Beurer-Kellner, Buesser, Creţu et al., "Design Patterns for Securing LLM Agents against Prompt Injections", arXiv 2506.08837: https://arxiv.org/html/2506.08837
28. Hines, Lopez, Hall et al., "Defending Against Indirect Prompt Injection Attacks With Spotlighting", arXiv 2403.14720: https://arxiv.org/abs/2403.14720
29. Android Authority, "Here's how Android 15 protects your two-factor authentication codes from malicious apps" (2024-10-21): https://www.androidauthority.com/android-15-two-factor-authentication-codes-3492585/
30. Security Affairs, "Advanced Protection Mode in Android 17 prevents apps from misusing Accessibility Services" (2026-03-16): https://securityaffairs.com/189497/security/advanced-protection-mode-in-android-17-prevents-apps-from-misusing-accessibility-services.html
31. Google, "Use accessibility shortcuts" (Android Accessibility Help): https://support.google.com/accessibility/android/answer/7650693
32. /home/phaseonebig/brain/wiki/decisions/adr-0012-typed-decide-seam-jev-backend.md
33. /home/phaseonebig/op13/REPORT.md
34. /home/phaseonebig/projects/operator/app/src/main/java/dev/operator/ScreenService.kt and ScreenReader.kt (op13 draft, uncommitted)
35. /home/phaseonebig/projects/operator-design/README.md
