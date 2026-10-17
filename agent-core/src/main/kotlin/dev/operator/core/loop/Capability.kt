package dev.operator.core.loop

import dev.operator.core.api.MediaAction
import dev.operator.core.api.ToolCall
import dev.operator.core.grammar.JsonValue

/*
 * The capability set CAPS fixes from the owner's words (§8.1, §9.3 item 2).
 *
 * A capability is one §7.2 tool plus the JSON schema of its arguments (§7.2 API rows). CAPS asks
 * `task.intent` over these descriptions plus "use the UI" and "ask owner"; the API branch then fills
 * the arguments under a grammar generated from the schema (C11). The registry itself is the executor
 * side's (S3/S9); the loop only carries the metadata it needs.
 *
 * Design: §8.1 (CAPS, API_ARGS), §8.2, §7.2, §9.3 item 2.
 */

/** One direct-API capability the owner's words may select. */
class Capability(
    val id: String,
    val toolName: String,
    val description: String,
    /** The app set this capability implies, used to bound the UI path's `open` enum (§8.3). */
    val apps: Set<String>,
    /** The argument schema the API_ARGS grammar is generated from (§7.2, C11). */
    val schema: JsonValue.Obj,
    /** Maps validated arguments to a typed call; null means the arguments do not fit the schema. */
    val build: (JsonValue.Obj) -> ToolCall?,
) {
    override fun toString(): String = "Capability($id, $toolName)"
}

/** The M1 direct-API capabilities the loop knows. Argument validation is the executor's (§7.4). */
object M1Capabilities {

    val ALL: List<Capability> = listOf(
        Capability(
            id = "alarm.set",
            toolName = "set_alarm",
            description = "set a clock alarm",
            apps = setOf("com.android.deskclock", "com.google.android.deskclock"),
            schema = obj(
                "type" to str("object"),
                "additionalProperties" to bool(false),
                "properties" to obj(
                    "hour" to obj("type" to str("integer")),
                    "minute" to obj("type" to str("integer")),
                    "label" to obj("type" to str("string")),
                ),
            ),
        ) { args ->
            val hour = int(args, "hour") ?: return@Capability null
            val minute = int(args, "minute") ?: return@Capability null
            ToolCall.SetAlarm(hour, minute, string(args, "label"), emptyList())
        },
        Capability(
            id = "timer.set",
            toolName = "set_timer",
            description = "start a countdown timer",
            apps = setOf("com.android.deskclock", "com.google.android.deskclock"),
            schema = obj(
                "type" to str("object"),
                "additionalProperties" to bool(false),
                "properties" to obj(
                    "durationMs" to obj("type" to str("integer")),
                    "label" to obj("type" to str("string")),
                ),
            ),
        ) { args ->
            val ms = int(args, "durationMs") ?: return@Capability null
            ToolCall.SetTimer(ms.toLong(), string(args, "label"))
        },
        Capability(
            id = "sms.send",
            toolName = "send_sms",
            description = "send a text message",
            apps = setOf("com.google.android.apps.messaging", "com.android.mms"),
            schema = obj(
                "type" to str("object"),
                "additionalProperties" to bool(false),
                "properties" to obj(
                    "number" to obj("type" to str("string")),
                    "text" to obj("type" to str("string")),
                ),
            ),
        ) { args ->
            val number = string(args, "number") ?: return@Capability null
            val text = string(args, "text") ?: return@Capability null
            ToolCall.SendSms(number, text)
        },
        Capability(
            id = "call.place",
            toolName = "call",
            description = "place a phone call",
            apps = setOf("com.android.dialer", "com.google.android.dialer"),
            schema = obj(
                "type" to str("object"),
                "additionalProperties" to bool(false),
                "properties" to obj("number" to obj("type" to str("string"))),
            ),
        ) { args ->
            val number = string(args, "number") ?: return@Capability null
            ToolCall.Call(number)
        },
        Capability(
            id = "calendar.insert",
            toolName = "calendar_insert",
            description = "add a calendar event",
            apps = setOf("com.google.android.calendar", "com.android.calendar"),
            schema = obj(
                "type" to str("object"),
                "additionalProperties" to bool(false),
                "properties" to obj(
                    "title" to obj("type" to str("string")),
                    "beginMs" to obj("type" to str("integer")),
                    "endMs" to obj("type" to str("integer")),
                    "attendees" to obj("type" to str("string")),
                ),
            ),
        ) { args ->
            val title = string(args, "title") ?: return@Capability null
            val begin = int(args, "beginMs") ?: return@Capability null
            val end = int(args, "endMs") ?: return@Capability null
            val attendees = string(args, "attendees")?.takeIf { it.isNotBlank() }?.split(',')?.map { it.trim() } ?: emptyList()
            ToolCall.CalendarInsert(title, begin.toLong(), end.toLong(), attendees, null)
        },
        Capability(
            id = "media.control",
            toolName = "media",
            description = "play or pause media",
            apps = emptySet(),
            schema = obj(
                "type" to str("object"),
                "additionalProperties" to bool(false),
                "properties" to obj(
                    "action" to obj(
                        "type" to str("string"),
                        "enum" to JsonValue.Arr(listOf("play", "pause", "next", "prev").map { str(it) }),
                    ),
                ),
            ),
        ) { args ->
            when (string(args, "action")) {
                "play" -> ToolCall.Media(MediaAction.PLAY)
                "pause" -> ToolCall.Media(MediaAction.PAUSE)
                "next" -> ToolCall.Media(MediaAction.NEXT)
                "prev" -> ToolCall.Media(MediaAction.PREV)
                else -> null
            }
        },
    )

    /** The capability the owner's words selected, by id. */
    fun byId(id: String): Capability? = ALL.firstOrNull { it.id == id }

    private fun obj(vararg entries: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(linkedMapOf(*entries))

    private fun str(s: String): JsonValue.Str = JsonValue.Str(s)

    private fun bool(b: Boolean): JsonValue.Bool = JsonValue.Bool(b)

    private fun string(o: JsonValue.Obj, key: String): String? = (o.entries[key] as? JsonValue.Str)?.value

    private fun int(o: JsonValue.Obj, key: String): Int? = (o.entries[key] as? JsonValue.Num)?.let {
        if (it.isIntegral) it.asLong.toInt() else null
    }
}
