package dev.operator.core.grammar

import dev.operator.core.api.MediaAction
import dev.operator.core.api.ScrollDirection

/*
 * The model's short action form (C10): `{"a":"tap","i":9}`.
 *
 * The model emits a verb from the §7.1 table and an element number; the host stamps the snapshot id
 * and maps it to a typed `ToolCall` ([ActionMapper]). Raw coordinates and swipes are host-only
 * (C10, §7.1), so there is no field for them here and the parser rejects unknown keys: a model that
 * emits `{"a":"tap","x":10,"y":20}` is refused, never obeyed.
 *
 * Design: §7.1 (model verb → typed call), §8.3 (per-step grammar), C10, ADR-0009 items 3 and 4.
 */

/** The verbs of the §7.1 table plus the catalogue's other global actions, in the short form's `a` field. */
enum class ActionVerb(val wire: String) {
    TAP("tap"),
    LONG("long"),
    TYPE("type"),
    SCROLL("scroll"),
    BACK("back"),
    HOME("home"),
    RECENTS("recents"),
    NOTIFICATIONS("notifications"),
    QUICK_SETTINGS("quick_settings"),
    DISMISS_SHADE("dismiss_shade"),
    LOCK_SCREEN("lock_screen"),
    MEDIA("media"),
    OPEN("open"),
    WAIT("wait"),
    DONE("done"),
    ASK("ask"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(w: String): ActionVerb? = byWire[w]
    }
}

/** The global navigation verbs that take no argument (a `nav` alternative in the per-step grammar). */
enum class NavVerb(val wire: String) {
    BACK("back"),
    HOME("home"),
    RECENTS("recents"),
    NOTIFICATIONS("notifications"),
    QUICK_SETTINGS("quick_settings"),
    DISMISS_SHADE("dismiss_shade"),
    LOCK_SCREEN("lock_screen"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(w: String): NavVerb? = byWire[w]

        val wires: List<String> = entries.map { it.wire }
    }
}

/** A parsed short-form action. [key] is the loop-detection identity `(verb, argument)` (§8.4). */
sealed interface ShortAction {
    val verb: ActionVerb

    /** `(state, action)` identity used by the loop detector; the state comes from the snapshot hash. */
    val key: String

    data class Tap(val index: Int) : ShortAction {
        override val verb: ActionVerb = ActionVerb.TAP
        override val key: String = "tap:$index"
    }

    data class LongPress(val index: Int) : ShortAction {
        override val verb: ActionVerb = ActionVerb.LONG
        override val key: String = "long:$index"
    }

    data class Type(val index: Int, val text: String) : ShortAction {
        override val verb: ActionVerb = ActionVerb.TYPE
        override val key: String = "type:$index"
    }

    data class Scroll(val index: Int, val direction: ScrollDirection) : ShortAction {
        override val verb: ActionVerb = ActionVerb.SCROLL
        override val key: String = "scroll:$index:${direction.name.lowercase()}"
    }

    data class Nav(val nav: NavVerb) : ShortAction {
        override val verb: ActionVerb
            get() = when (nav) {
                NavVerb.BACK -> ActionVerb.BACK
                NavVerb.HOME -> ActionVerb.HOME
                NavVerb.RECENTS -> ActionVerb.RECENTS
                NavVerb.NOTIFICATIONS -> ActionVerb.NOTIFICATIONS
                NavVerb.QUICK_SETTINGS -> ActionVerb.QUICK_SETTINGS
                NavVerb.DISMISS_SHADE -> ActionVerb.DISMISS_SHADE
                NavVerb.LOCK_SCREEN -> ActionVerb.LOCK_SCREEN
            }
        override val key: String = "nav:${nav.wire}"
    }

    data class Media(val action: MediaAction) : ShortAction {
        override val verb: ActionVerb = ActionVerb.MEDIA
        override val key: String = "media:${action.name.lowercase()}"
    }

    data class Open(val app: String) : ShortAction {
        override val verb: ActionVerb = ActionVerb.OPEN
        override val key: String = "open:$app"
    }

    data class Wait(val ms: Long?) : ShortAction {
        override val verb: ActionVerb = ActionVerb.WAIT
        override val key: String = "wait"
    }

    data class Done(val answer: String) : ShortAction {
        override val verb: ActionVerb = ActionVerb.DONE
        override val key: String = "done"
    }

    data class Ask(val question: String) : ShortAction {
        override val verb: ActionVerb = ActionVerb.ASK
        override val key: String = "ask"
    }
}

/** The result of parsing a model action: [Ok] or a one-line [Err] that goes to the audit log. */
sealed interface ShortFormParse {
    data class Ok(val action: ShortAction) : ShortFormParse

    data class Err(val reason: String) : ShortFormParse
}

/** Strict reader for the short form. Unknown keys and unknown verbs are failures, never ignored. */
object ShortForm {

    /** The maximum length of free text (`type`, `answer`) — §8.3, the 04§R3 sketch's `{0,200}`. */
    const val MAX_TEXT: Int = 200

    /** The maximum length of an `ask`/`why` question — the 60-character `why` bound of §8.3. */
    const val MAX_QUESTION: Int = 60

    fun parse(text: String): ShortFormParse {
        val parsed = when (val r = Json.parse(text)) {
            is JsonParse.Err -> return ShortFormParse.Err(r.reason)
            is JsonParse.Ok -> r.value
        }
        val obj = parsed as? JsonValue.Obj ?: return ShortFormParse.Err("action must be a JSON object")
        val a = obj.entries["a"] as? JsonValue.Str ?: return ShortFormParse.Err("action needs a string \"a\"")
        val verb = ActionVerb.fromWire(a.value) ?: return ShortFormParse.Err("unknown verb \"${a.value}\"")
        return try {
            ShortFormParse.Ok(fromObject(verb, obj))
        } catch (e: JsonShapeException) {
            ShortFormParse.Err(e.message ?: "invalid action")
        }
    }

    private fun fromObject(verb: ActionVerb, o: JsonValue.Obj): ShortAction {
        fun index(): Int {
            val v = o.entries["i"] ?: throw JsonShapeException("${verb.wire} needs \"i\"")
            val i = JsonAccess.int(v, "\"i\"")
            if (i < 0) throw JsonShapeException("\"i\" must not be negative")
            return i
        }

        return when (verb) {
            ActionVerb.TAP -> {
                JsonAccess.exactKeys(o, verb.wire, "a", "i")
                ShortAction.Tap(index())
            }

            ActionVerb.LONG -> {
                JsonAccess.exactKeys(o, verb.wire, "a", "i")
                ShortAction.LongPress(index())
            }

            ActionVerb.TYPE -> {
                JsonAccess.exactKeys(o, verb.wire, "a", "i", "text")
                val text = JsonAccess.str(o.entries.getValue("text"), "\"text\"")
                if (text.length > MAX_TEXT) throw JsonShapeException("\"text\" longer than $MAX_TEXT")
                ShortAction.Type(index(), text)
            }

            ActionVerb.SCROLL -> {
                JsonAccess.exactKeys(o, verb.wire, "a", "i", "dir")
                val dir = JsonAccess.str(o.entries.getValue("dir"), "\"dir\"").lowercase()
                val direction = when (dir) {
                    "down" -> ScrollDirection.DOWN
                    "up" -> ScrollDirection.UP
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> throw JsonShapeException("unknown scroll direction \"$dir\"")
                }
                ShortAction.Scroll(index(), direction)
            }

            ActionVerb.BACK, ActionVerb.HOME, ActionVerb.RECENTS, ActionVerb.NOTIFICATIONS,
            ActionVerb.QUICK_SETTINGS, ActionVerb.DISMISS_SHADE, ActionVerb.LOCK_SCREEN,
            -> {
                JsonAccess.exactKeys(o, verb.wire, "a")
                ShortAction.Nav(NavVerb.fromWire(verb.wire) ?: throw JsonShapeException("unknown nav verb"))
            }

            ActionVerb.MEDIA -> {
                JsonAccess.exactKeys(o, verb.wire, "a", "act")
                val act = JsonAccess.str(o.entries.getValue("act"), "\"act\"").lowercase()
                val action = when (act) {
                    "play" -> MediaAction.PLAY
                    "pause" -> MediaAction.PAUSE
                    "next" -> MediaAction.NEXT
                    "prev" -> MediaAction.PREV
                    else -> throw JsonShapeException("unknown media action \"$act\"")
                }
                ShortAction.Media(action)
            }

            ActionVerb.OPEN -> {
                JsonAccess.exactKeys(o, verb.wire, "a", "app")
                ShortAction.Open(JsonAccess.str(o.entries.getValue("app"), "\"app\""))
            }

            ActionVerb.WAIT -> {
                // §7.1 lists a bare `wait`; an optional `ms` is host-bounded, so it is accepted but capped.
                val ms = o.entries["ms"]?.let { JsonAccess.int(it, "\"ms\"").toLong() }
                if (ms != null && (ms < 0 || ms > 10_000)) throw JsonShapeException("\"ms\" out of range")
                val extra = o.entries.keys - setOf("a", "ms")
                if (extra.isNotEmpty()) throw JsonShapeException("wait has unexpected ${extra.sorted()}")
                ShortAction.Wait(ms)
            }

            ActionVerb.DONE -> {
                JsonAccess.exactKeys(o, verb.wire, "a", "answer")
                val answer = JsonAccess.str(o.entries.getValue("answer"), "\"answer\"")
                if (answer.length > MAX_TEXT) throw JsonShapeException("\"answer\" longer than $MAX_TEXT")
                ShortAction.Done(answer)
            }

            ActionVerb.ASK -> {
                JsonAccess.exactKeys(o, verb.wire, "a", "q")
                val q = JsonAccess.str(o.entries.getValue("q"), "\"q\"")
                if (q.length > MAX_QUESTION) throw JsonShapeException("\"q\" longer than $MAX_QUESTION")
                ShortAction.Ask(q)
            }
        }
    }

    /** Encodes an action back to the short form; the inverse of [parse], used by tests and tracing. */
    fun encode(action: ShortAction): String = when (action) {
        is ShortAction.Tap -> """{"a":"tap","i":${action.index}}"""
        is ShortAction.LongPress -> """{"a":"long","i":${action.index}}"""
        is ShortAction.Type -> """{"a":"type","i":${action.index},"text":${Json.write(JsonValue.Str(action.text))}}"""
        is ShortAction.Scroll -> """{"a":"scroll","i":${action.index},"dir":"${action.direction.name.lowercase()}"}"""
        is ShortAction.Nav -> """{"a":"${action.nav.wire}"}"""
        is ShortAction.Media -> """{"a":"media","act":"${action.action.name.lowercase()}"}"""
        is ShortAction.Open -> """{"a":"open","app":${Json.write(JsonValue.Str(action.app))}}"""
        is ShortAction.Wait -> action.ms?.let { """{"a":"wait","ms":$it}""" } ?: """{"a":"wait"}"""
        is ShortAction.Done -> """{"a":"done","answer":${Json.write(JsonValue.Str(action.answer))}}"""
        is ShortAction.Ask -> """{"a":"ask","q":${Json.write(JsonValue.Str(action.question))}}"""
    }
}
