package dev.operator.core.grammar

import dev.operator.core.api.NodeAction
import dev.operator.core.api.NodeState
import dev.operator.core.api.Role
import dev.operator.core.api.Snapshot
import dev.operator.core.api.ToolCall
import dev.operator.core.api.UiNode

/*
 * The host half of C10: short-form action → typed `ToolCall`.
 *
 * The model names a verb and an element number; this mapper stamps the current snapshot id and the
 * host-side `ElementKey` and label the executor re-resolves just before acting (§7.4 step 11). It
 * never reads coordinates or any other field from the model's text, and it refuses an action that
 * does not fit the element it named (a `tap` on a non-clickable node, a `type` on a password node),
 * so the executor still sees a call whose shape the §7.2/§7.3 policy can classify.
 *
 * Design: §7.1 (model verb → typed call), §8.3 (verb-specific index enums), C10, §7.4 steps 3-4.
 */

/** The outcome of a mapping: a typed call, or a one-line rejection for the history/audit. */
sealed interface ActionResult {
    data class Mapped(val call: ToolCall) : ActionResult

    data class Rejected(val reason: String) : ActionResult
}

/** Why a short-form action did not map onto the snapshot. */
class ActionMapException(message: String) : IllegalArgumentException(message)

/**
 * Maps actions for one snapshot. [appSet] is the task's app set (§8.3 `open` enum, §7.4 step 6);
 * [appAliases] maps the display name the grammar may emit to a package name.
 */
class ActionMapper(
    private val snapshot: Snapshot,
    private val appSet: Set<String> = emptySet(),
    private val appAliases: Map<String, String> = emptyMap(),
    private val defaultWaitMs: Long = 1_000,
) {
    private val byIndex: Map<Int, UiNode> = snapshot.nodes.associateBy { it.index }

    fun map(action: ShortAction): ActionResult = try {
        ActionResult.Mapped(toCall(action))
    } catch (e: ActionMapException) {
        ActionResult.Rejected(e.message ?: "invalid action")
    }

    private fun toCall(action: ShortAction): ToolCall = when (action) {
        is ShortAction.Tap -> {
            val node = node(action.index, "tap")
            requireAction(node, NodeAction.CLICK, "tap")
            ToolCall.Click(snapshot.id, node.index, node.key, node.label)
        }

        is ShortAction.LongPress -> {
            val node = node(action.index, "long")
            requireAction(node, NodeAction.LONG_CLICK, "long")
            ToolCall.LongClick(snapshot.id, node.index, node.key, node.label)
        }

        is ShortAction.Type -> {
            val node = node(action.index, "type")
            if (node.role != Role.EDIT) throw ActionMapException("type needs an edit element, [${node.index}] is ${node.role.name.lowercase()}")
            if (NodeState.PASSWORD in node.state) throw ActionMapException("type refused on the password element [${node.index}] (§7.1)")
            requireAction(node, NodeAction.SET_TEXT, "type")
            ToolCall.SetText(snapshot.id, node.index, node.key, node.label, action.text)
        }

        is ShortAction.Scroll -> {
            val node = node(action.index, "scroll")
            val scrollable = NodeAction.SCROLL_FORWARD in node.actions || NodeAction.SCROLL_BACKWARD in node.actions ||
                node.role == Role.LIST
            if (!scrollable) throw ActionMapException("scroll needs a scroll container, [${node.index}] is not one")
            ToolCall.Scroll(snapshot.id, node.index, node.key, action.direction)
        }

        is ShortAction.Nav -> when (action.nav) {
            NavVerb.BACK -> ToolCall.Back
            NavVerb.HOME -> ToolCall.Home
            NavVerb.RECENTS -> ToolCall.Recents
            NavVerb.NOTIFICATIONS -> ToolCall.Notifications
            NavVerb.QUICK_SETTINGS -> ToolCall.QuickSettings
            NavVerb.DISMISS_SHADE -> ToolCall.DismissShade
            NavVerb.LOCK_SCREEN -> ToolCall.LockScreen
        }

        is ShortAction.Media -> ToolCall.Media(action.action)

        is ShortAction.Open -> ToolCall.LaunchApp(resolvePackage(action.app))

        is ShortAction.Wait -> ToolCall.Wait(action.ms ?: defaultWaitMs)

        is ShortAction.Done -> ToolCall.Finish(action.answer)

        is ShortAction.Ask -> ToolCall.AskOwner(action.question)
    }

    private fun resolvePackage(app: String): String {
        val pkg = appAliases[app] ?: app
        // The task's app set bounds launch_app (§7.1, §8.3). An empty set cannot happen once CAPS ran,
        // but if it does, refuse rather than widen the capability silently.
        if (appSet.isNotEmpty() && pkg !in appSet && app !in appSet) {
            throw ActionMapException("open \"$app\" is outside the task's app set")
        }
        return pkg
    }

    private fun node(index: Int, verb: String): UiNode =
        byIndex[index] ?: throw ActionMapException("$verb names element [$index], which is not on the current screen (§7.4 step 4)")

    private fun requireAction(node: UiNode, action: NodeAction, verb: String) {
        if (NodeState.DISABLED in node.state) throw ActionMapException("$verb refused: [${node.index}] is disabled")
        if (action !in node.actions) {
            throw ActionMapException("$verb refused: [${node.index}] does not support ${action.name.lowercase()}")
        }
    }
}
