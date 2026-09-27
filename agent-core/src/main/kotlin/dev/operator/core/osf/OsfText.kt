package dev.operator.core.osf

/*
 * Text-level rules of OSF v0 (FOUNDATION §6.1 rules 1b, 5 and 7).
 *
 * Pure functions over raw label text, so every text source (labels, hints, window titles, app
 * labels, and — via the same object — notification text reaching the model, §6.1 rule 1b) applies
 * the same redaction, truncation and escaping in the same order: raw → OTP-redacted → truncated →
 * JSON-style escaped.
 *
 * Input convention for [UiNode.label] (the frozen type has one label field, §6.1 rule 3 merges
 * several leaves into one label): the reader stores the merged parts joined by [MERGE_SEPARATOR]
 * as raw, unescaped text; the serializer splits them apart again and quotes each part. For an
 * `edit` field with empty content and a hint, the label is the two parts `""` and the hint, i.e.
 * the string `" · <hint>"` (an empty first part); see OsfSerializer.
 */

/** Separator §6.1 rule 3 joins merged leaves with, and the serializer splits on. */
const val MERGE_SEPARATOR = " · "

/** §6.1 rule 1b: an OTP-like span becomes this marker. */
const val CODE_MARKER = "‹code›"

object OsfText {

    /** §6.1 rule 5: maximum 80 label characters, 200 for `edit` content. */
    const val MAX_LABEL_CHARS = 80
    const val MAX_EDIT_CHARS = 200

    /**
     * JSON-style escaping (§6.1 rule 5, [04§R1.5]): `\` and `"` are escaped and a newline becomes
     * the two characters `\n`, so an app string such as `\n[99] btn "Confirm"` cannot forge an
     * element line. Other control characters (< 0x20 and 0x7F) are stripped.
     */
    fun escape(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        for (c in raw) when {
            c == '\\' -> sb.append("\\\\")
            c == '"' -> sb.append("\\\"")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> {}
            c < ' ' || c == '\u007F' -> {}
            else -> sb.append(c)
        }
        return sb.toString()
    }

    /** §6.1 rule 5 truncation, code-point aware so no surrogate pair is cut in half. */
    fun truncate(raw: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (raw.codePointCount(0, raw.length) <= maxChars) return raw
        return raw.substring(0, raw.offsetByCodePoints(0, maxChars))
    }

    /**
     * §6.1 rule 1b: one-time codes are redacted in every source. An OTP-like span becomes
     * `‹code›`:
     *  - a standalone run of 4–8 digits, unless it sits in a phone-number context (a run directly
     *    preceded by `+`, or a chain of two or more digit groups separated by single spaces or
     *    dashes — the phone context is what keeps the §6.2 golden `+49 151 0000000` intact);
     *  - a 4–8 character alphanumeric token containing both a digit and a letter, with a cue word
     *    (code, otp, pin, tan, bestätigungscode — case-insensitive) within 24 characters on either
     *    side.
     */
    fun redactOtp(text: String): String {
        if (text.isEmpty()) return text
        val spans = ArrayList<Pair<Int, Int>>()

        val runs = DIGIT_RUN.findAll(text).map { it.range }.toList()
        if (runs.isNotEmpty()) {
            // Group consecutive digit runs into phone-context chains.
            val phone = BooleanArray(runs.size)
            var i = 0
            while (i < runs.size) {
                var j = i
                while (j + 1 < runs.size && isPhoneGap(text, runs[j].last + 1, runs[j + 1].first)) j++
                val hasPlus = (i..j).any { k -> runs[k].first > 0 && text[runs[k].first - 1] == '+' }
                if (j > i || hasPlus) for (k in i..j) phone[k] = true
                i = j + 1
            }
            for (k in runs.indices) {
                val len = runs[k].last - runs[k].first + 1
                if (len in 4..8 && !phone[k]) spans.add(runs[k].first to runs[k].last + 1)
            }
        }

        for (m in ALNUM_TOKEN.findAll(text)) {
            val s = m.value
            if (s.length in 4..8 && s.any { it.isDigit() } && s.any { it.isLetter() }) {
                val before = text.substring(maxOf(0, m.range.first - 24), m.range.first)
                val after = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 1 + 24))
                if (CUE.containsMatchIn(before) || CUE.containsMatchIn(after)) {
                    spans.add(m.range.first to m.range.last + 1)
                }
            }
        }

        if (spans.isEmpty()) return text
        spans.sortBy { it.first }
        val sb = StringBuilder(text.length)
        var at = 0
        for ((start, end) in spans) {
            if (start < at) continue // overlap; already replaced
            sb.append(text, at, start).append(CODE_MARKER)
            at = end
        }
        sb.append(text, at, text.length)
        return sb.toString()
    }

    /** The merged label parts of §6.1 rule 3; a blank label is one empty part. */
    fun labelParts(label: String): List<String> = label.split(MERGE_SEPARATOR)

    /**
     * §6.7 unlabelled actionable hint: `id/fab_add` (or a bare `fab_add`) gives `fab add`.
     * Returns null when no usable simple name exists.
     */
    fun hintFromViewId(viewId: String?): String? {
        if (viewId.isNullOrBlank()) return null
        val simple = viewId.substringAfterLast('/').substringAfterLast(':')
        if (simple.isBlank()) return null
        return simple.replace('_', ' ')
    }

    /** §6.3 key input: trimmed, whitespace-collapsed, lowercased label. */
    fun normaliseLabel(label: String): String = label.trim().replace(WHITESPACE, " ").lowercase()

    /** §6.3 full-hash input: clock text normalised so a ticking clock does not change the hash. */
    fun normaliseClock(text: String): String =
        text.replace(TIME, "<t>").replace(DATE, "<d>")

    private fun isPhoneGap(text: String, from: Int, to: Int): Boolean {
        if (to <= from) return false
        if (to - from > 3) return false
        for (i in from until to) when (text[i]) {
            ' ', '-' -> {}
            else -> return false
        }
        return true
    }

    private val DIGIT_RUN = Regex("""\d+""")
    private val ALNUM_TOKEN = Regex("""(?<![A-Za-z0-9])[A-Za-z0-9]{4,8}(?![A-Za-z0-9])""")
    private val CUE = Regex("""(?i)(?:^|[^A-Za-zÄÖÜäöüß])(code|codes|otp|pin|pins|tan|tans|bestätigungscode)(?![A-Za-zÄÖÜäöüß])""")
    private val WHITESPACE = Regex("""\s+""")
    private val TIME = Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""")
    private val DATE = Regex("""\b\d{1,2}\.\d{1,2}\.(\d{4}|\d{2})?\b""")
}
