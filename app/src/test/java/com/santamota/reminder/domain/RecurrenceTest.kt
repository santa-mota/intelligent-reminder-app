package com.santamota.reminder.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class RecurrenceTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val base = ZonedDateTime.of(2026, 1, 5, 9, 0, 0, 0, zone) // Mon
    private val createdAt = base.minusDays(7)

    private fun reminder(
        rec: Recurrence?,
        exceptions: Set<LocalDate> = emptySet(),
    ) = Reminder(
        title = "morning",
        type = ReminderType.NOTIFICATION,
        triggerAt = base,
        recurrence = rec,
        exceptions = exceptions,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun `one-time reminder returns its trigger if not past`() {
        val r = reminder(rec = null)
        assertThat(r.nextOccurrenceFrom(base.minusHours(1))).isEqualTo(base)
    }

    @Test
    fun `one-time reminder returns null if past`() {
        val r = reminder(rec = null)
        assertThat(r.nextOccurrenceFrom(base.plusHours(1))).isNull()
    }

    @Test
    fun `daily recurrence advances day by day`() {
        val r = reminder(Recurrence(Recurrence.Pattern.DAILY))
        assertThat(r.nextOccurrenceFrom(base.plusDays(1))).isEqualTo(base.plusDays(1))
        assertThat(r.nextOccurrenceFrom(base.plusDays(2).plusHours(3)))
            .isEqualTo(base.plusDays(3))
    }

    @Test
    fun `daily with interval 2 skips alternate days`() {
        val r = reminder(Recurrence(Recurrence.Pattern.DAILY, interval = 2))
        val occurrences = r.expandOccurrences(base, 3)
        assertThat(occurrences).containsExactly(
            base,
            base.plusDays(2),
            base.plusDays(4),
        ).inOrder()
    }

    @Test
    fun `weekly with specific days returns those days`() {
        val r = reminder(
            Recurrence(
                pattern = Recurrence.Pattern.WEEKLY,
                daysOfWeek = setOf(MONDAY, WEDNESDAY),
            )
        )
        val occurrences = r.expandOccurrences(base, 4)
        // base is Mon. Next should be Wed, then Mon, then Wed.
        assertThat(occurrences.map { it.dayOfWeek }).containsExactly(
            MONDAY, WEDNESDAY, MONDAY, WEDNESDAY,
        ).inOrder()
    }

    @Test
    fun `exceptions are skipped`() {
        val skipDate = base.plusDays(1).toLocalDate()
        val r = reminder(Recurrence(Recurrence.Pattern.DAILY), exceptions = setOf(skipDate))
        val next = r.nextOccurrenceFrom(base.plusHours(1))
        assertThat(next!!.toLocalDate()).isEqualTo(base.plusDays(2).toLocalDate())
    }

    @Test
    fun `endDate stops recurrence`() {
        val end = base.plusDays(3).toLocalDate()
        val r = reminder(Recurrence(Recurrence.Pattern.DAILY, endDate = end))
        val occurrences = r.expandOccurrences(base, 10)
        assertThat(occurrences).hasSize(4) // base + 3 days
        assertThat(occurrences.last().toLocalDate()).isEqualTo(end)
    }

    @Test
    fun `monthly recurrence keeps day of month`() {
        val r = reminder(Recurrence(Recurrence.Pattern.MONTHLY))
        val occurrences = r.expandOccurrences(base, 3)
        assertThat(occurrences.map { it.dayOfMonth }).containsExactly(5, 5, 5)
    }
}
