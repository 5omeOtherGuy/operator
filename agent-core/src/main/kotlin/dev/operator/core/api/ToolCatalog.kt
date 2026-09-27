package dev.operator.core.api

import kotlin.reflect.KClass

/*
 * Frozen M1 API (`dev.operator.core.api`) — the §7.2 catalogue, as data.
 *
 * Design: FOUNDATION §7.2 (risk classes per tool), §7.3 (UI targets raise to R2), §9.3 item 3
 * (tainted text raises to R2), §5.2 (the resolution may raise a class and never lower it).
 *
 * This is the catalogue only; the executor (S3) is the single place that resolves the final class of
 * a concrete call and applies the gate. Every [ToolCall] subclass must appear in [base] — the unit
 * test reflects over the sealed hierarchy and fails otherwise.
 */
object ToolCatalog {

    /**
     * The §7.2 class of each tool when its arguments and context are not raisers. R0 read-only,
     * R1 reversible (rate-limited, audited), R2 irreversible, R3 irreversible and high-power.
     */
    val base: Map<KClass<out ToolCall>, RiskClass> = mapOf(
        // R0
        ToolCall.ReadScreen::class to RiskClass.R0,
        ToolCall.ScreenshotInternal::class to RiskClass.R0,
        ToolCall.ListNotifications::class to RiskClass.R0,
        ToolCall.NextAlarm::class to RiskClass.R0,
        ToolCall.CalendarQuery::class to RiskClass.R0,
        ToolCall.AskOwner::class to RiskClass.R0,
        ToolCall.Finish::class to RiskClass.R0,
        ToolCall.Wait::class to RiskClass.R0,

        // R1
        ToolCall.Click::class to RiskClass.R1,
        ToolCall.LongClick::class to RiskClass.R1,
        ToolCall.SetText::class to RiskClass.R1,
        ToolCall.Scroll::class to RiskClass.R1,
        ToolCall.Back::class to RiskClass.R1,
        ToolCall.Home::class to RiskClass.R1,
        ToolCall.Recents::class to RiskClass.R1,
        ToolCall.Notifications::class to RiskClass.R1,
        ToolCall.DismissShade::class to RiskClass.R1,
        ToolCall.QuickSettings::class to RiskClass.R1,
        ToolCall.LockScreen::class to RiskClass.R1,
        ToolCall.Media::class to RiskClass.R1,
        ToolCall.LaunchApp::class to RiskClass.R1,
        ToolCall.SetAlarm::class to RiskClass.R1,
        ToolCall.SetTimer::class to RiskClass.R1,
        ToolCall.DismissAlarm::class to RiskClass.R1,
        ToolCall.CalendarInsert::class to RiskClass.R1,
        ToolCall.Torch::class to RiskClass.R1,

        // R2
        ToolCall.SendSms::class to RiskClass.R2,
        ToolCall.Call::class to RiskClass.R2,
        ToolCall.ReplyNotification::class to RiskClass.R2,
        ToolCall.NotificationAction::class to RiskClass.R2,
        ToolCall.CalendarDelete::class to RiskClass.R2,
        ToolCall.HeadsetHook::class to RiskClass.R2,
        ToolCall.HideApp::class to RiskClass.R2,
        ToolCall.SuspendApp::class to RiskClass.R2,
        ToolCall.SetPermission::class to RiskClass.R2,

        // R3
        ToolCall.InstallApk::class to RiskClass.R3,
        ToolCall.Uninstall::class to RiskClass.R3,
        ToolCall.Reboot::class to RiskClass.R3,
    )

    /**
     * The classes whose arguments or context raise the class above [base], with the raised class:
     * §7.3 (a tap in a high-risk context, on an irreversible target or in a browser form is R2),
     * §9.3 item 3 (tainted `type` and submits of tainted text are R2), §7.2 (calendar insert with
     * attendees is R2; `set_permission GRANTED` is R3). The executor may raise a class and never
     * lower it (§5.2).
     */
    val risers: Map<KClass<out ToolCall>, RiskClass> = mapOf(
        ToolCall.Click::class to RiskClass.R2,
        ToolCall.LongClick::class to RiskClass.R2,
        ToolCall.SetText::class to RiskClass.R2,
        ToolCall.CalendarInsert::class to RiskClass.R2,
        ToolCall.SetPermission::class to RiskClass.R3,
    )

    /**
     * §7.2 F-class: forbidden, no [ToolCall] exists and the S-08 lint checks for them
     * (`open_url`, arbitrary intents and deep links; `power_dialog`; `global_screenshot`;
     * the a11y button, chooser, shortcut and all-apps actions; the clipboard copy, cut and paste
     * actions and every clipboard read or write (§7.4 step 4); raw Settings and DPM calls;
     * `wipeData`/`wipeDevice`,
     * `removeUser`, `transferOwnership`, `clearDeviceOwnerApp`, `setKeyguardDisabled`,
     * system-update calls, user restrictions, CA certificates, VPN/proxy/private DNS,
     * `setPermissionPolicy`, `setDelegatedScopes`, `setUserControlDisabledPackages`,
     * `setUninstallBlocked`, `setPermittedAccessibilityServices`, `hidden_api_policy`).
     * Payments and purchases are F by default (OQ-5).
     */
    val forbiddenNames: Set<String> = setOf(
        "open_url",
        "power_dialog",
        "global_screenshot",
        "accessibility_button",
        "accessibility_chooser",
        "accessibility_shortcut",
        "accessibility_all_apps",
        "clipboard_read",
        "clipboard_write",
        "wipe_data",
        "wipe_device",
        "remove_user",
        "transfer_ownership",
        "clear_device_owner",
        "set_keyguard_disabled",
        "system_update",
        "user_restriction",
        "ca_certificate",
        "vpn",
        "private_dns",
        "set_permission_policy",
        "set_delegated_scopes",
        "set_user_control_disabled_packages",
        "set_uninstall_blocked",
        "set_permitted_accessibility_services",
        "hidden_api_policy",
        "purchase",
        "payment",
    )
}
