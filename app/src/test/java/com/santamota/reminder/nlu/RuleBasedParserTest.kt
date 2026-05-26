package com.santamota.reminder.nlu

import com.google.common.truth.Truth.assertThat
import com.santamota.reminder.domain.CancelScope
import com.santamota.reminder.domain.Intent
import com.santamota.reminder.domain.Recurrence
import com.santamota.reminder.domain.ReminderCategory
import com.santamota.reminder.domain.ReminderType
import com.santamota.reminder.domain.TimeSpec
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class RuleBasedParserTest {

    // Fixed clock: Mon 25 May 2026, 09:00 UTC.
    private val fixed: Clock = Clock.fixed(
        Instant.parse("2026-05-25T09:00:00Z"),
        ZoneId.of("UTC"),
    )
    private val parser = RuleBasedParser(fixed)

    // --- create ---------------------------------------------------------

    @Test
    fun `remind me at 3 PM today`() {
        val intent = parser.parse("remind me at 3 PM") as Intent.Create
        assertThat(intent.type).isEqualTo(ReminderType.NOTIFICATION)
        val spec = intent.timeSpec as TimeSpec.DateAndTime
        assertThat(spec.time).isEqualTo(LocalTime.of(15, 0))
        assertThat(spec.date.toString()).isEqualTo("2026-05-25")
    }

    @Test
    fun `at 7 in the morning rolls to tomorrow when past`() {
        // clock is 9:00, so "at 7" → tomorrow 07:00
        val intent = parser.parse("remind me at 7") as Intent.Create
        val spec = intent.timeSpec as TimeSpec.DateAndTime
        assertThat(spec.time).isEqualTo(LocalTime.of(7, 0))
        assertThat(spec.date.toString()).isEqualTo("2026-05-26")
    }

    @Test
    fun `relative time in 30 min`() {
        val intent = parser.parse("remind me in 30 min") as Intent.Create
        val spec = intent.timeSpec as TimeSpec.RelativeToNow
        assertThat(spec.offset).isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun `relative time in 3 days`() {
        val intent = parser.parse("remind me to submit assignment in 3 days") as Intent.Create
        assertThat(intent.title).contains("submit assignment")
        val spec = intent.timeSpec as TimeSpec.RelativeToNow
        assertThat(spec.offset).isEqualTo(Duration.ofDays(3))
        assertThat(intent.category).isEqualTo(ReminderCategory.ASSIGNMENT)
    }

    @Test
    fun `daily recurrence`() {
        val intent = parser.parse("remind me to eat lunch every day at 1pm") as Intent.Create
        assertThat(intent.recurrence?.pattern).isEqualTo(Recurrence.Pattern.DAILY)
        assertThat((intent.timeSpec as TimeSpec.DateAndTime).time)
            .isEqualTo(LocalTime.of(13, 0))
        assertThat(intent.category).isEqualTo(ReminderCategory.MEAL)
    }

    @Test
    fun `weekly recurrence on Monday`() {
        val intent = parser.parse("remind me every monday at 9 to do standup") as Intent.Create
        assertThat(intent.recurrence?.pattern).isEqualTo(Recurrence.Pattern.WEEKLY)
        assertThat(intent.recurrence?.daysOfWeek).containsExactly(DayOfWeek.MONDAY)
    }

    @Test
    fun `set alarm flags as ALARM type`() {
        val intent = parser.parse("set alarm for 7am") as Intent.Create
        assertThat(intent.type).isEqualTo(ReminderType.ALARM)
        val spec = intent.timeSpec as TimeSpec.DateAndTime
        assertThat(spec.time).isEqualTo(LocalTime.of(7, 0))
    }

    @Test
    fun `wake me up at 6 30 am`() {
        val intent = parser.parse("wake me up at 6:30am") as Intent.Create
        assertThat(intent.type).isEqualTo(ReminderType.ALARM)
        val spec = intent.timeSpec as TimeSpec.DateAndTime
        assertThat(spec.time).isEqualTo(LocalTime.of(6, 30))
    }

    @Test
    fun `relative anchor an hour before lunch`() {
        val intent = parser.parse("remind me an hour before lunch") as Intent.Create
        val anchor = intent.relativeTo!!
        assertThat(anchor.match.titleQuery).isEqualTo("lunch")
        assertThat(anchor.offset).isEqualTo(Duration.ofHours(-1))
    }

    @Test
    fun `15 minutes before meeting`() {
        val intent = parser.parse("remind me 15 min before meeting") as Intent.Create
        val anchor = intent.relativeTo!!
        assertThat(anchor.match.titleQuery).isEqualTo("meeting")
        assertThat(anchor.offset).isEqualTo(Duration.ofMinutes(-15))
    }

    // --- move -----------------------------------------------------------

    @Test
    fun `move lunch to 1 30 PM`() {
        val intent = parser.parse("move lunch to 1:30 PM") as Intent.Move
        assertThat(intent.targetMatch.titleQuery).isEqualTo("lunch")
        val spec = intent.newTimeSpec as TimeSpec.DateAndTime
        assertThat(spec.time).isEqualTo(LocalTime.of(13, 30))
    }

    @Test
    fun `change my meeting reminder to tomorrow at 4`() {
        val intent = parser.parse("change my meeting to tomorrow at 4") as Intent.Move
        assertThat(intent.targetMatch.titleQuery).contains("meeting")
    }

    // --- cancel ---------------------------------------------------------

    @Test
    fun `cancel lunch alarm`() {
        val intent = parser.parse("cancel lunch alarm") as Intent.Cancel
        assertThat(intent.targetMatch.titleQuery).isEqualTo("lunch")
        assertThat(intent.scope).isEqualTo(CancelScope.ALL_OCCURRENCES)
    }

    @Test
    fun `skip lunch for today`() {
        val intent = parser.parse("skip lunch for today") as Intent.Cancel
        assertThat(intent.scope).isEqualTo(CancelScope.THIS_OCCURRENCE)
    }

    // --- done -----------------------------------------------------------

    @Test
    fun `i submitted the assignment`() {
        val intent = parser.parse("i submitted the assignment") as Intent.MarkDone
        assertThat(intent.targetMatch.titleQuery).contains("assignment")
    }

    @Test
    fun `assignment is done`() {
        val intent = parser.parse("assignment is done") as Intent.MarkDone
        assertThat(intent.targetMatch.titleQuery).isEqualTo("assignment")
    }

    // --- delay ----------------------------------------------------------

    @Test
    fun `delay lunch by 30 min`() {
        val intent = parser.parse("delay lunch by 30 min") as Intent.Delay
        assertThat(intent.targetMatch.titleQuery).isEqualTo("lunch")
        assertThat(intent.by).isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun `snooze workout 1 hour`() {
        val intent = parser.parse("snooze workout 1 hour") as Intent.Delay
        assertThat(intent.by).isEqualTo(Duration.ofHours(1))
    }

    // --- ambiguity ------------------------------------------------------

    @Test
    fun `complete gibberish returns Ambiguous`() {
        val intent = parser.parse("alkjsdf lkjasdf")
        assertThat(intent).isInstanceOf(Intent.Ambiguous::class.java)
    }

    @Test
    fun `empty input returns Chat`() {
        val intent = parser.parse("")
        assertThat(intent).isInstanceOf(Intent.Chat::class.java)
    }

    @Test
    fun `greeting returns Chat`() {
        val intent = parser.parse("hi")
        assertThat(intent).isInstanceOf(Intent.Chat::class.java)
    }
}
