package com.santamota.reminder.nlu

import com.santamota.reminder.domain.CancelScope
import com.santamota.reminder.domain.Intent
import com.santamota.reminder.domain.Recurrence
import com.santamota.reminder.domain.RelativeAnchor
import com.santamota.reminder.domain.ReminderCategory
import com.santamota.reminder.domain.ReminderMatch
import com.santamota.reminder.domain.ReminderType
import com.santamota.reminder.domain.TimeSpec
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Deterministic, fast NLU. Resolves the common utterance shapes to a typed
 * [Intent] without going near the LLM. Anything ambiguous returns
 * [Intent.Ambiguous] so the LLM adapter can take a swing.
 *
 * Inject a [Clock] so tests are reproducible.
 */
class RuleBasedParser(
    private val clock: Clock,
    private val zone: ZoneId = clock.zone,
) {

    fun parse(rawInput: String): Intent {
        val text = rawInput.trim().lowercase()
        if (text.isEmpty()) return Intent.Chat(rawInput)

        // Order matters — Move/Cancel/Done/Delay must be checked before Create,
        // because "cancel my lunch alarm" contains the word "alarm" which
        // would otherwise look like a create.
        return parseDone(text)
            ?: parseCancel(text)
            ?: parseDelay(text)
            ?: parseMove(text)
            ?: parseCreate(text, rawInput)
            ?: detectChat(text)
            ?: Intent.Ambiguous(rawInput, "no rule matched")
    }

    // --- DONE -----------------------------------------------------------

    private fun parseDone(text: String): Intent.MarkDone? {
        // "done with X", "X is done", "I submitted X", "I finished X",
        // "completed X"
        val donePatterns = listOf(
            Regex("""^(?:i(?:'?ve)?\s+)?(?:just\s+)?(?:done|finished|completed|submitted)\s+(?:with\s+)?(?:my\s+|the\s+)?(.+)$"""),
            Regex("""^(.+)\s+is\s+done$"""),
            Regex("""^done$"""), // bare "done" — engine should ask which one
        )
        for (rx in donePatterns) {
            val m = rx.matchEntire(text) ?: continue
            val target = m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            return Intent.MarkDone(
                targetMatch = ReminderMatch(titleQuery = target),
            )
        }
        return null
    }

    // --- CANCEL ---------------------------------------------------------

    private fun parseCancel(text: String): Intent.Cancel? {
        val rx = Regex(
            """^(?:cancel|remove|delete|drop|skip)\s+(?:the\s+|my\s+)?(.+?)(?:\s+(?:reminder|alarm))?(?:\s+for\s+(today|tomorrow|this\s+week))?$"""
        )
        val m = rx.matchEntire(text) ?: return null
        val title = m.groupValues[1].trim()
        val scopeWord = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
        // "skip" → just this occurrence
        // "cancel"/"delete"/"remove" → all occurrences for recurring
        val scope = when {
            text.startsWith("skip") -> CancelScope.THIS_OCCURRENCE
            scopeWord != null -> CancelScope.THIS_OCCURRENCE
            else -> CancelScope.ALL_OCCURRENCES
        }
        return Intent.Cancel(
            targetMatch = ReminderMatch(titleQuery = title),
            scope = scope,
        )
    }

    // --- DELAY ----------------------------------------------------------

    private fun parseDelay(text: String): Intent.Delay? {
        // "delay X by 30 min", "snooze X 10 minutes", "X delayed by 2 days"
        val rxA = Regex("""^(?:delay|snooze|postpone)\s+(.+?)\s+(?:by\s+)?($DUR_RE)$""")
        val rxB = Regex("""^(.+?)\s+(?:is\s+)?delayed\s+(?:by\s+)?($DUR_RE)$""")
        val match = rxA.matchEntire(text) ?: rxB.matchEntire(text) ?: return null
        val title = match.groupValues[1].trim()
        val duration = parseDuration(match.groupValues[2]) ?: return null
        return Intent.Delay(
            targetMatch = ReminderMatch(titleQuery = title),
            by = duration,
        )
    }

    // --- MOVE -----------------------------------------------------------

    private fun parseMove(text: String): Intent.Move? {
        // "move X to 1:30", "change X to 9 PM", "reschedule X to tomorrow at 9",
        // "X at 1:30 now"
        val patterns = listOf(
            Regex("""^(?:move|change|reschedule|shift)\s+(.+?)\s+to\s+(.+)$"""),
            Regex("""^(?:i\s+want\s+|set\s+)?(.+?)\s+(?:to\s+|at\s+)(.+?)\s+now$"""),
        )
        for (rx in patterns) {
            val m = rx.matchEntire(text) ?: continue
            val title = m.groupValues[1].trim()
            val timeStr = m.groupValues[2].trim()
            val timeSpec = parseTimeSpec(timeStr) ?: continue
            return Intent.Move(
                targetMatch = ReminderMatch(titleQuery = title),
                newTimeSpec = timeSpec,
            )
        }
        return null
    }

    // --- CREATE ---------------------------------------------------------

    private fun parseCreate(text: String, rawInput: String): Intent.Create? {
        val isAlarm = text.startsWith("alarm") ||
            text.startsWith("wake me") ||
            Regex("""\bset\s+(?:an?\s+)?(?:daily|weekly|monthly\s+)?alarm\b""").containsMatchIn(text)

        // Extract recurrence FIRST so the verb-strip below doesn't eat
        // "daily" / "weekly" / "monthly" before we record it. "set a daily
        // alarm for lunch" should still be DAILY.
        val (recurrence, deRec) = stripRecurrence(text)

        // Verb-strip handles common natural phrasings: "remind me", "set an
        // alarm", "can you set a daily alarm" (after recurrence already
        // extracted), "could you remind me", "please notify me", etc.
        val stripped = deRec
            .replace(Regex("""^(?:please\s+|could\s+you\s+|can\s+you\s+|would\s+you\s+|i\s+(?:want|need|would\s+like)\s+(?:you\s+)?to\s+)+"""), "")
            .replace(Regex("""^(?:remind\s+me(?:\s+to|\s+about)?|alarm\s+for|alarm\s+at|set\s+(?:an?\s+|a\s+)?(?:alarm|reminder)(?:\s+(?:for|at|to))?|wake\s+me(?:\s+up)?\s*(?:at)?|notify\s+me(?:\s+to)?|tell\s+me\s+to|ping\s+me(?:\s+to|\s+about)?)\s+"""), "")
            .replace(Regex("""\?\s*$"""), "")  // trailing "?"
            .trim()

        if (stripped == deRec && !text.startsWith("at ") && !text.startsWith("in ") &&
            !text.startsWith("every ") && !text.startsWith("daily ")
        ) return null

        val withoutRec = stripped

        // Relative anchor takes precedence — extraction also yields a title.
        parseRelativeAnchorAndTitle(withoutRec)?.let { (anchor, title) ->
            val finalTitle = title.ifBlank { "reminder" }
            return Intent.Create(
                title = finalTitle,
                type = if (isAlarm) ReminderType.ALARM else ReminderType.NOTIFICATION,
                timeSpec = TimeSpec.RelativeToNow(Duration.ZERO),
                recurrence = recurrence,
                relativeTo = anchor,
                category = inferCategory(finalTitle),
            )
        }

        val (timeSpec, title) = extractTimeAndTitle(withoutRec) ?: return null
        val finalTitle = title.takeIf { it.isNotBlank() } ?: "reminder"
        return Intent.Create(
            title = finalTitle,
            type = if (isAlarm) ReminderType.ALARM else ReminderType.NOTIFICATION,
            timeSpec = timeSpec,
            recurrence = recurrence,
            relativeTo = null,
            category = inferCategory(finalTitle),
        )
    }

    // --- TIME PARSING ---------------------------------------------------

    /**
     * Parses a time fragment in isolation (used by [parseMove], where the
     * title and time have already been split). Tries, in order:
     * "in N units", "tomorrow at 9", bare clock time.
     */
    private fun parseTimeSpec(input: String): TimeSpec? {
        val text = input.trim().lowercase()

        Regex("""^in\s+($DUR_RE)$""").matchEntire(text)?.let { m ->
            return parseDuration(m.groupValues[1])?.let { TimeSpec.RelativeToNow(it) }
        }
        Regex("""^($DATE_RE)\s+at\s+($TIME_RE)$""").matchEntire(text)?.let { m ->
            val date = parseDate(m.groupValues[1]) ?: return@let
            val time = parseTime(m.groupValues[2]) ?: return@let
            return TimeSpec.DateAndTime(date, time)
        }
        Regex("""^($DATE_RE)$""").matchEntire(text)?.let { m ->
            val date = parseDate(m.groupValues[1]) ?: return@let
            return TimeSpec.DateAndTime(date, java.time.LocalTime.of(9, 0))
        }
        // Bare time → today, or tomorrow if past.
        parseTime(text)?.let { time ->
            val today = LocalDate.now(clock)
            val candidate = ZonedDateTime.of(today, time, zone)
            val date = if (candidate.isBefore(ZonedDateTime.now(clock))) {
                today.plusDays(1)
            } else today
            return TimeSpec.DateAndTime(date, time)
        }
        return null
    }

    /**
     * Extract a [TimeSpec] from a phrase. Returns null if nothing time-like
     * is present. Returned title is the input minus the recognised time
     * fragment (best-effort; the engine will tidy).
     */
    private fun extractTimeAndTitle(text: String): Pair<TimeSpec, String>? {
        // "in 30 min", "in 3 days", "in 2 hours"
        Regex("""(.*?)\bin\s+($DUR_RE)\b(.*)""").find(text)?.let { m ->
            val duration = parseDuration(m.groupValues[2]) ?: return@let
            val title = (m.groupValues[1] + " " + m.groupValues[3]).cleanTitle()
            return TimeSpec.RelativeToNow(duration) to title
        }

        // "tomorrow at 9", "today at 3 PM", "monday at 8am"
        val dateTimeRx = Regex("""(.*?)\b($DATE_RE)\s+at\s+($TIME_RE)\b(.*)""")
        dateTimeRx.find(text)?.let { m ->
            val date = parseDate(m.groupValues[2]) ?: return@let
            val time = parseTime(m.groupValues[3]) ?: return@let
            val title = (m.groupValues[1] + " " + m.groupValues[4]).cleanTitle()
            return TimeSpec.DateAndTime(date, time) to title
        }

        // bare "at 5pm" / "at 17:30" → today (or tomorrow if past)
        val timeOnlyRx = Regex("""(.*?)\bat\s+($TIME_RE)\b(.*)""")
        timeOnlyRx.find(text)?.let { m ->
            val time = parseTime(m.groupValues[2]) ?: return@let
            val today = LocalDate.now(clock)
            val candidate = ZonedDateTime.of(today, time, zone)
            val date = if (candidate.isBefore(ZonedDateTime.now(clock))) {
                today.plusDays(1)
            } else today
            val title = (m.groupValues[1] + " " + m.groupValues[3]).cleanTitle()
            return TimeSpec.DateAndTime(date, time) to title
        }

        // Last resort: the whole stripped text *is* a bare time spec like
        // "7am" or "6:30am" (used by "set alarm for 7am" after the verb is
        // stripped). Title becomes empty; engine falls back to "reminder".
        parseTime(text.trim())?.let { time ->
            val today = LocalDate.now(clock)
            val candidate = ZonedDateTime.of(today, time, zone)
            val date = if (candidate.isBefore(ZonedDateTime.now(clock))) {
                today.plusDays(1)
            } else today
            return TimeSpec.DateAndTime(date, time) to ""
        }

        return null
    }

    private fun parseDate(input: String): LocalDate? {
        val s = input.trim().lowercase()
        val today = LocalDate.now(clock)
        return when {
            s == "today" -> today
            s == "tomorrow" -> today.plusDays(1)
            s == "day after tomorrow" -> today.plusDays(2)
            s.startsWith("next ") -> {
                val dow = parseDayOfWeek(s.removePrefix("next ").trim()) ?: return null
                var d = today
                do { d = d.plusDays(1) } while (d.dayOfWeek != dow)
                d
            }
            s.startsWith("this ") -> parseDayOfWeek(s.removePrefix("this ").trim())
                ?.let { dow ->
                    var d = today
                    while (d.dayOfWeek != dow) d = d.plusDays(1)
                    d
                }
            else -> parseDayOfWeek(s)?.let { dow ->
                var d = today
                while (d.dayOfWeek != dow) d = d.plusDays(1)
                d
            }
        }
    }

    private fun parseTime(input: String): LocalTime? {
        val s = input.trim().lowercase().replace(" ", "")
        // 24h: 17:30 / 17.30
        Regex("""^(\d{1,2})[:.](\d{2})$""").matchEntire(s)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h in 0..23 && min in 0..59) return LocalTime.of(h, min)
        }
        // 12h with am/pm: 5pm, 5:30pm, 5 pm
        Regex("""^(\d{1,2})(?::(\d{2}))?(am|pm|a|p)$""").matchEntire(s)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = m.groupValues[3]
            if (h !in 1..12 || min !in 0..59) return null
            if (ap.startsWith("p") && h != 12) h += 12
            if (ap.startsWith("a") && h == 12) h = 0
            return LocalTime.of(h, min)
        }
        // bare integer: "at 7" — ambiguous but treat as morning by default
        Regex("""^(\d{1,2})$""").matchEntire(s)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h in 0..23) return LocalTime.of(h, 0)
        }
        return null
    }

    private fun parseDayOfWeek(s: String): DayOfWeek? = when (s) {
        "monday", "mon" -> DayOfWeek.MONDAY
        "tuesday", "tue", "tues" -> DayOfWeek.TUESDAY
        "wednesday", "wed" -> DayOfWeek.WEDNESDAY
        "thursday", "thu", "thurs" -> DayOfWeek.THURSDAY
        "friday", "fri" -> DayOfWeek.FRIDAY
        "saturday", "sat" -> DayOfWeek.SATURDAY
        "sunday", "sun" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun parseDuration(input: String): Duration? {
        val m = Regex("""^(?:an?\s+|one\s+)?(\d*)\s*($UNIT_RE)$""").matchEntire(input.trim())
            ?: return null
        val nStr = m.groupValues[1]
        val n = nStr.ifEmpty { "1" }.toIntOrNull() ?: return null
        val unit = m.groupValues[2]
        return when {
            unit.startsWith("sec") -> Duration.ofSeconds(n.toLong())
            unit.startsWith("min") -> Duration.ofMinutes(n.toLong())
            unit.startsWith("hour") || unit == "hr" || unit == "hrs" || unit == "h" ->
                Duration.ofHours(n.toLong())
            unit.startsWith("day") -> Duration.ofDays(n.toLong())
            unit.startsWith("week") -> Duration.ofDays(7L * n)
            else -> null
        }
    }

    // --- recurrence -----------------------------------------------------

    private fun stripRecurrence(text: String): Pair<Recurrence?, String> {
        // "every day", "daily" → DAILY
        if (Regex("""\b(every\s+day|daily)\b""").containsMatchIn(text)) {
            val cleaned = text.replace(Regex("""\b(every\s+day|daily)\b"""), "").trim()
            return Recurrence(Recurrence.Pattern.DAILY) to cleaned
        }
        // "every Monday" / "weekly on Friday"
        Regex("""\bevery\s+($DOW_RE)\b""").find(text)?.let { m ->
            val dow = parseDayOfWeek(m.groupValues[1])
            if (dow != null) {
                val cleaned = text.replace(m.value, "").trim()
                return Recurrence(
                    pattern = Recurrence.Pattern.WEEKLY,
                    daysOfWeek = setOf(dow),
                ) to cleaned
            }
        }
        if (Regex("""\bweekly\b""").containsMatchIn(text)) {
            return Recurrence(Recurrence.Pattern.WEEKLY) to
                text.replace(Regex("""\bweekly\b"""), "").trim()
        }
        if (Regex("""\bmonthly\b""").containsMatchIn(text)) {
            return Recurrence(Recurrence.Pattern.MONTHLY) to
                text.replace(Regex("""\bmonthly\b"""), "").trim()
        }
        return null to text
    }

    // --- relative anchor ("an hour before lunch") -----------------------

    /**
     * Extract a relative anchor *and* the corresponding action title from a
     * stripped utterance. Handles three common shapes:
     *   "an hour before lunch to take medicine"  → anchor=lunch, title=take medicine
     *   "take medicine an hour before lunch"     → anchor=lunch, title=take medicine
     *   "an hour before lunch"                   → anchor=lunch, title=lunch
     *
     * Anchor is 1-3 words by default; if "to <action>" follows we treat the
     * first word after before/after as the anchor (anchor is usually short
     * — "lunch", "meeting", "the call").
     */
    private fun parseRelativeAnchorAndTitle(text: String): Pair<RelativeAnchor, String>? {
        val rx = Regex(
            """^(.*?)\s*(?:($DUR_RE)\s+)?(before|after)\s+(?:my\s+|the\s+)?(\w+(?:\s+\w+){0,2}?)(?:\s+(?:reminder|alarm))?(?:\s+to\s+(.+))?$"""
        )
        val m = rx.matchEntire(text) ?: return null
        val prefix = m.groupValues[1].trim()
        val durStr = m.groupValues[2].ifBlank { "1 hour" }
        val direction = m.groupValues[3]
        val anchorName = m.groupValues[4].trim()
        val suffix = m.groupValues.getOrNull(5)?.trim().orEmpty()
        if (anchorName.isBlank()) return null
        val duration = parseDuration(durStr) ?: return null
        val signed = if (direction == "before") duration.negated() else duration
        val title = when {
            suffix.isNotBlank() -> suffix.cleanTitle()
            prefix.isNotBlank() -> prefix.cleanTitle()
            else -> anchorName // fallback: title same as anchor ("remind me before lunch")
        }
        return RelativeAnchor(
            match = ReminderMatch(titleQuery = anchorName),
            offset = signed,
        ) to title
    }

    // --- category inference --------------------------------------------

    private fun inferCategory(title: String): ReminderCategory {
        val t = title.lowercase()
        return when {
            t.containsAny("assignment", "homework", "submit", "deadline", "exam", "essay") ->
                ReminderCategory.ASSIGNMENT
            t.containsAny("lunch", "breakfast", "dinner", "eat", "meal", "snack") ->
                ReminderCategory.MEAL
            t.containsAny("medicine", "med", "pill", "tablet", "drug", "supplement", "dose") ->
                ReminderCategory.MEDICINE
            t.containsAny("meeting", "standup", "call", "sync", "1:1", "one-on-one") ->
                ReminderCategory.MEETING
            t.containsAny("workout", "exercise", "gym", "run", "yoga") ->
                ReminderCategory.EXERCISE
            t.containsAny("doctor", "dentist", "appointment", "salon") ->
                ReminderCategory.APPOINTMENT
            t.containsAny("wake", "morning", "alarm") ->
                ReminderCategory.WAKE
            t.containsAny("flight", "train", "trip", "travel", "leave for") ->
                ReminderCategory.TRAVEL
            t.containsAny("bill", "rent", "utilities", "pay") ->
                ReminderCategory.BILL
            else -> ReminderCategory.UNCATEGORIZED
        }
    }

    // --- small-talk -----------------------------------------------------

    private fun detectChat(text: String): Intent.Chat? {
        val chats = setOf("hi", "hello", "hey", "thanks", "thank you", "ok", "cool", "yes", "no")
        return if (text in chats) Intent.Chat(text) else null
    }

    // --- helpers --------------------------------------------------------

    private fun String.cleanTitle(): String =
        replace(Regex("""\s*(remind\s+me\s+(?:to|about)?|to|about|alarm|notify\s+me|tell\s+me\s+to)\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .removeSuffix(".")
            .removeSuffix(",")
            .trim()

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it) }

    companion object {
        // Regex building blocks. Kept here so all callers see the same vocab.
        const val DUR_RE = """(?:\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten)\s*(?:s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|wk|wks|week|weeks)"""
        const val UNIT_RE = """sec(?:s|onds?)?|min(?:s|utes?)?|h(?:rs?|ours?)?|day(?:s)?|w(?:ks?|eeks?)?"""
        const val TIME_RE = """\d{1,2}(?:[:.]\d{2})?\s*(?:am|pm|a|p)?"""
        const val DATE_RE = """today|tomorrow|day\s+after\s+tomorrow|(?:this|next)\s+\w+|monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thurs|fri|sat|sun"""
        const val DOW_RE = """mon(?:day)?|tue(?:s|sday)?|wed(?:nesday)?|thu(?:rs|rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?"""
    }
}
