package com.santamota.reminder.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Given a recurring reminder and a "from" point, returns the next time it
 * should fire on or after [from]. Returns null if the recurrence has ended.
 *
 * Pure function — no side effects. Easy to test against frozen clocks.
 */
fun Reminder.nextOccurrenceFrom(from: ZonedDateTime): ZonedDateTime? {
    val rec = recurrence ?: return triggerAt.takeIf { !it.isBefore(from) }
    var candidate = triggerAt
    // Walk forward until we find a candidate >= from that is not in exceptions.
    var safety = 0
    while (true) {
        if (++safety > 10_000) return null // pathological case
        if (candidate.isBefore(from) || candidate.toLocalDate() in exceptions) {
            candidate = candidate.advanceBy(rec) ?: return null
            continue
        }
        if (rec.endDate != null && candidate.toLocalDate().isAfter(rec.endDate)) {
            return null
        }
        return candidate
    }
}

private fun ZonedDateTime.advanceBy(rec: Recurrence): ZonedDateTime? {
    val next = when (rec.pattern) {
        Recurrence.Pattern.DAILY -> plusDays(rec.interval.toLong())
        Recurrence.Pattern.WEEKLY -> advanceWeekly(rec.daysOfWeek, rec.interval)
        Recurrence.Pattern.MONTHLY -> plusMonths(rec.interval.toLong())
        Recurrence.Pattern.YEARLY -> plusYears(rec.interval.toLong())
    }
    if (rec.endDate != null && next.toLocalDate().isAfter(rec.endDate)) return null
    return next
}

private fun ZonedDateTime.advanceWeekly(
    daysOfWeek: Set<DayOfWeek>,
    interval: Int,
): ZonedDateTime {
    if (daysOfWeek.isEmpty()) {
        return plusWeeks(interval.toLong())
    }
    // Find next day-of-week in the configured set, skipping ahead intervals.
    val current = toLocalDate()
    val sortedDays = daysOfWeek.sortedBy { it.value }
    val nextDay = sortedDays.firstOrNull { it.value > current.dayOfWeek.value }
        ?: sortedDays.first()
    return if (nextDay.value > current.dayOfWeek.value) {
        with(TemporalAdjusters.next(nextDay))
    } else {
        // wrap to next interval week
        plusWeeks(interval.toLong()).with(TemporalAdjusters.previousOrSame(nextDay))
    }
}

/**
 * Expands a recurrence into the first [count] occurrences from [from]. Useful
 * for previewing in chat ("the next 3 reminders are…").
 */
fun Reminder.expandOccurrences(from: ZonedDateTime, count: Int = 5): List<ZonedDateTime> {
    val result = mutableListOf<ZonedDateTime>()
    var cursor = from
    while (result.size < count) {
        val next = nextOccurrenceFrom(cursor) ?: break
        result += next
        cursor = next.plusSeconds(1)
    }
    return result
}

/**
 * Whether a given calendar date falls within the recurrence pattern. Used by
 * the rule parser to validate "next Monday" style phrases.
 */
fun Recurrence.matches(date: LocalDate, anchor: LocalDate): Boolean {
    if (endDate != null && date.isAfter(endDate)) return false
    return when (pattern) {
        Recurrence.Pattern.DAILY -> true
        Recurrence.Pattern.WEEKLY ->
            daysOfWeek.isEmpty() && date.dayOfWeek == anchor.dayOfWeek ||
                date.dayOfWeek in daysOfWeek
        Recurrence.Pattern.MONTHLY -> date.dayOfMonth == anchor.dayOfMonth
        Recurrence.Pattern.YEARLY ->
            date.dayOfMonth == anchor.dayOfMonth && date.month == anchor.month
    }
}
