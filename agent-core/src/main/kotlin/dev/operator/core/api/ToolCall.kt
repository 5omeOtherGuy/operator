package dev.operator.core.api

/*
 * Frozen M1 API (`dev.operator.core.api`) — the typed effects.
 *
 * Design: FOUNDATION §7.1 (model verb → typed call), §7.2 (tool catalogue with risk classes),
 * §7.3 (irreversibility of UI targets), §7.4 (validation order), §9.2 (the gate the agent cannot
 * press), §9.3 (taint). Every tool of §7.2 has a class here; F-class "tools" have none, by design.
 *
 * The only path from the loop to an effect is `ExecutorPort.execute(call, approval)` (§2.2, ADR-0002).
 * Coordinates and `swipe` are host-only (C10): the model never emits them, so they are not ToolCalls.
 */

/** §7.2: R0 read-only, R1 reversible, R2 irreversible (physical-hold gate), R3 irreversible and high-power (hold + `BIOMETRIC_STRONG`). */
enum class RiskClass { R0, R1, R2, R3 }

/** §7.1 `scroll i dir`. */
enum class ScrollDirection { DOWN, UP, LEFT, RIGHT }

/** §7.2 `media(play/pause/next/prev)`. */
enum class MediaAction { PLAY, PAUSE, NEXT, PREV }

/** §7.2 `set_permission` (M2, device owner). */
enum class PermissionGrantState { DENIED, DEFAULT, GRANTED }

/**
 * A typed effect request, one class per §7.2 row, [name] = the §7.2 tool name as it appears in the
 * catalogue, the audit log and the decision log.
 *
 * UI calls carry the snapshot and the element number the model used plus the [ElementKey] and label
 * the host saw, because the executor re-resolves the target by key and label just before acting
 * (§7.4 step 11, R12). The model never emits coordinates or snapshot ids of its own (§7.1).
 */
sealed interface ToolCall {
    val name: String

    // ---- R0: read-only (§7.2) ----

    /** §7.2 R0 `read_screen`: ask the hands for a fresh [Snapshot] (settle + read, §7.5). */
    data class ReadScreen(val snapshotId: Long) : ToolCall {
        override val name: String = "read_screen"
    }

    /** §7.2 R0 `screenshot_internal` (M2, §14 M2 OCR fallback). */
    data object ScreenshotInternal : ToolCall {
        override val name: String = "screenshot_internal"
    }

    /** §7.2 R0 `list_notifications`, operator's own notification rows excluded (§6.1 rule 1), OTP spans redacted (§6.1 rule 1b). */
    data object ListNotifications : ToolCall {
        override val name: String = "list_notifications"
    }

    /** §7.2 R0 `next_alarm`. */
    data object NextAlarm : ToolCall {
        override val name: String = "next_alarm"
    }

    /** §7.2 R0 `calendar_query`. */
    data class CalendarQuery(val query: String, val fromMs: Long, val toMs: Long) : ToolCall {
        override val name: String = "calendar_query"
    }

    /** §7.2 R0 `ask_owner`: the question is asked in an operator activity and answered there (§9.3 item 1). */
    data class AskOwner(val question: String) : ToolCall {
        override val name: String = "ask_owner"
    }

    /** §7.2 R0 `finish`. */
    data class Finish(val answer: String) : ToolCall {
        override val name: String = "finish"
    }

    /** §7.1 `wait`: host settle, R0. */
    data class Wait(val ms: Long) : ToolCall {
        override val name: String = "wait"
    }

    // ---- R1: reversible, rate-limited and audited (§7.2) ----

    /** §7.1 `tap i` → `Click(el)`; R1 base, R2 by §7.3 (high-risk context or irreversible target). */
    data class Click(
        val snapshotId: Long,
        val elementIndex: Int,
        val elementKey: ElementKey,
        val label: String,
    ) : ToolCall {
        override val name: String = "click"
    }

    /** §7.1 `long i` → `LongClick(el)`; R1 base, R2 by §7.3. */
    data class LongClick(
        val snapshotId: Long,
        val elementIndex: Int,
        val elementKey: ElementKey,
        val label: String,
    ) : ToolCall {
        override val name: String = "long_click"
    }

    /** §7.1 `type i text` → `SetText(el, text)`; refused on password nodes, R2 when the text is tainted (§9.3 item 3). */
    data class SetText(
        val snapshotId: Long,
        val elementIndex: Int,
        val elementKey: ElementKey,
        val label: String,
        val text: String,
    ) : ToolCall {
        override val name: String = "set_text"
    }

    /** §7.1 `scroll i dir`; gesture fallback ≤ 1000 ms (§9.6). */
    data class Scroll(
        val snapshotId: Long,
        val elementIndex: Int,
        val elementKey: ElementKey,
        val direction: ScrollDirection,
    ) : ToolCall {
        override val name: String = "scroll"
    }

    /** §7.2 R1 `back` (global action). */
    data object Back : ToolCall {
        override val name: String = "back"
    }

    /** §7.2 R1 `home` (global action). */
    data object Home : ToolCall {
        override val name: String = "home"
    }

    /** §7.2 R1 `recents` (global action). */
    data object Recents : ToolCall {
        override val name: String = "recents"
    }

    /** §7.2 R1 `notifications`: open the notification shade. */
    data object Notifications : ToolCall {
        override val name: String = "notifications"
    }

    /** §7.2 R1 `dismiss_shade`: close the shade. */
    data object DismissShade : ToolCall {
        override val name: String = "dismiss_shade"
    }

    /** §7.2 R1 `quick_settings`: open the quick settings shade. */
    data object QuickSettings : ToolCall {
        override val name: String = "quick_settings"
    }

    /** §7.2 R1 `lock_screen` (global action). */
    data object LockScreen : ToolCall {
        override val name: String = "lock_screen"
    }

    /** §7.2 R1 `media`: play/pause/next/prev (§7.5 PlaybackState evidence). */
    data class Media(val action: MediaAction) : ToolCall {
        override val name: String = "media"
    }

    /** §7.2 R1 `launch_app`, `getLaunchIntentForPackage` only, inside the task's app set (§7.1, §8.3). */
    data class LaunchApp(val packageName: String) : ToolCall {
        override val name: String = "launch_app"
    }

    /** §7.2 R1 `set_alarm` with `EXTRA_SKIP_UI` (§7.5 `getNextAlarmClock` evidence). */
    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val label: String?,
        val daysOfWeek: List<Int>,
    ) : ToolCall {
        override val name: String = "set_alarm"
    }

    /** §7.2 R1 `set_timer` with `EXTRA_SKIP_UI` (§7.5 Clock notification evidence). */
    data class SetTimer(val durationMs: Long, val label: String?) : ToolCall {
        override val name: String = "set_timer"
    }

    /** §7.2 R1 `dismiss_alarm`. */
    data class DismissAlarm(val triggerTimeMs: Long) : ToolCall {
        override val name: String = "dismiss_alarm"
    }

    /** §7.2 `calendar_insert`: R1 without attendees, R2 with them (see [ToolCatalog.risers]). */
    data class CalendarInsert(
        val title: String,
        val beginMs: Long,
        val endMs: Long,
        val attendees: List<String>,
        val location: String?,
    ) : ToolCall {
        override val name: String = "calendar_insert"
    }

    /** §7.2 R1 `torch`. */
    data class Torch(val on: Boolean) : ToolCall {
        override val name: String = "torch"
    }

    // ---- R2: irreversible, physical-hold gate (§7.2, §9.2) ----

    /** §7.2 R2 `send_sms` through the platform SMS API (no SMS role needed), emergency and short codes refused (§7.4 step 7). */
    data class SendSms(val number: String, val text: String) : ToolCall {
        override val name: String = "send_sms"
    }

    /** §7.2 R2 `call` through `ACTION_CALL`, which cannot reach emergency numbers. */
    data class Call(val number: String) : ToolCall {
        override val name: String = "call"
    }

    /** §7.2 R2 `reply_notification`; never a `dev.operator` key (§7.4 step 5, S-10). */
    data class ReplyNotification(val key: String, val text: String) : ToolCall {
        override val name: String = "reply_notification"
    }

    /** §7.2 R2 `notification_action`; never a `dev.operator` key (§7.4 step 5). */
    data class NotificationAction(val key: String, val actionIndex: Int) : ToolCall {
        override val name: String = "notification_action"
    }

    /** §7.2 R2 `calendar_delete`. */
    data class CalendarDelete(val eventId: Long) : ToolCall {
        override val name: String = "calendar_delete"
    }

    /** §7.2 R2 `headset_hook`. */
    data object HeadsetHook : ToolCall {
        override val name: String = "headset_hook"
    }

    /** §7.2 R2 `hide_app` (M2, device owner). */
    data class HideApp(val packageName: String) : ToolCall {
        override val name: String = "hide_app"
    }

    /** §7.2 R2 `suspend_app` (M2, device owner). */
    data class SuspendApp(val packageName: String) : ToolCall {
        override val name: String = "suspend_app"
    }

    /** §7.2 `set_permission`: R2 for DENIED/DEFAULT, R3 for GRANTED (see [ToolCatalog.risers]), M2, device owner. */
    data class SetPermission(
        val packageName: String,
        val permission: String,
        val grantState: PermissionGrantState,
    ) : ToolCall {
        override val name: String = "set_permission"
    }

    // ---- R3: irreversible and high-power, hold + BiometricPrompt STRONG (§7.2, §9.2 item 4) ----

    /** §7.2 R3 `install_apk`; owner-picked URI only (§7.2). */
    data class InstallApk(val uri: String) : ToolCall {
        override val name: String = "install_apk"
    }

    /** §7.2 R3 `uninstall`; the denylist applies (§7.2, §6.1 rule 8). */
    data class Uninstall(val packageName: String) : ToolCall {
        override val name: String = "uninstall"
    }

    /** §7.2 R3 `reboot` (M2, device owner). */
    data object Reboot : ToolCall {
        override val name: String = "reboot"
    }
}

/**
 * Outcome of one [ToolCall], §7.5 (`Ok | Failed | Unverified | Refused | NeedsConfirmation |
 * Cancelled`), one state per §7.5 name: [Done], [Failed], [Unverified], [Refused],
 * [NeedsApproval], [Cancelled]. `Ok` requires positive evidence; the return value of a node action
 * alone never counts (§7.5).
 */
sealed interface ExecResult {
    /** §7.5 `Ok`: the post-check produced positive evidence (described in [evidence]). */
    data class Done(val evidence: String) : ExecResult

    /** §7.4/§7.5 `Refused`: validation rejected the call, e.g. `Refused("stale")` on a stale snapshot. */
    data class Refused(val reason: String) : ExecResult

    /** §7.5 `Failed`: the action ran but the effect did not appear. */
    data class Failed(val reason: String) : ExecResult

    /** §9.2 item 1: the executor blocks on the gate; [pendingId] identifies the pending approval. */
    data class NeedsApproval(val pendingId: String) : ExecResult

    /** §7.5: the action ran but the post-check could neither confirm nor rule out the effect. */
    data class Unverified(val evidence: String, val reason: String) : ExecResult

    /** §9.6: the task was stopped (soft stop, disarm, halted) or the owner declined at the gate. */
    data class Cancelled(val reason: String) : ExecResult
}

/** How an approval was given: §9.2 item 3 (hold) and item 4 (hold + fingerprint). The audit log records which (§9.5). */
enum class ApprovalMethod { VOLUME_HOLD, BIOMETRIC_STRONG }

/**
 * §9.2 item 5: the gate mints a single-use token that binds the arguments, the screen signature and
 * the contents of fields edited in this task
 * (`HMAC(K_boot, taskId‖step‖sha256(canonicalArgs)‖screenSignature‖sha256(editedFieldContents)‖nonce)`).
 * It expires after 30 s. The executor re-reads the screen and re-checks all of it just before acting
 * (§7.4 step 11, R12); the HMAC is recomputed from these fields, so it is not stored here.
 */
data class ApprovalToken(
    /** The nonce / pending id of §9.2 item 1. */
    val id: String,
    val call: ToolCall,
    val screenSignature: ScreenSignature,
    val editedFieldContents: Map<ElementKey, String>,
    val method: ApprovalMethod,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
)
