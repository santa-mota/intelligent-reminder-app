package com.santamota.reminder.engine

import com.google.common.truth.Truth.assertThat
import com.santamota.reminder.domain.Reminder
import com.santamota.reminder.domain.ReminderCategory
import com.santamota.reminder.domain.ReminderType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class PreferenceLearnerTest {

    private class FakeStore : PreferenceLearner.Store {
        var stored = PreferenceProfile.EMPTY
        override suspend fun read() = stored
        override suspend fun write(profile: PreferenceProfile) {
            stored = profile
        }
    }

    private fun clock(at: String): Clock = Clock.fixed(Instant.parse(at), ZoneId.of("UTC"))

    private fun reminder(at: String, title: String, cat: ReminderCategory) = Reminder(
        title = title,
        type = ReminderType.NOTIFICATION,
        triggerAt = ZonedDateTime.parse("${at}").withZoneSameInstant(ZoneId.of("UTC")),
        category = cat,
        createdAt = ZonedDateTime.parse(at),
        updatedAt = ZonedDateTime.parse(at),
    )

    @Test
    fun `three confirmations of a cadence yield a suggestion`() = runTest {
        val store = FakeStore()
        val learner = PreferenceLearner(clock("2026-05-25T09:00:00Z"), store)
        learner.load()
        val cadence = CadenceProfile(leadDays = 1, everyHours = 3)
        repeat(3) { learner.confirmCadence(ReminderCategory.ASSIGNMENT, cadence) }
        val r = reminder("2026-05-30T17:00:00Z", "submit essay", ReminderCategory.ASSIGNMENT)
        val suggestion = learner.suggestFor(r)
        assertThat(suggestion).isNotNull()
        assertThat(suggestion!!.message).contains("every 3h")
    }

    @Test
    fun `recent rejection suppresses suggestion`() = runTest {
        val store = FakeStore()
        val learner = PreferenceLearner(clock("2026-05-25T09:00:00Z"), store)
        learner.load()
        repeat(3) { learner.confirmCadence(ReminderCategory.ASSIGNMENT,
            CadenceProfile(leadDays = 1, everyHours = 3)) }
        learner.rejectSuggestion(ReminderCategory.ASSIGNMENT, "every 3h")
        val r = reminder("2026-05-30T17:00:00Z", "submit", ReminderCategory.ASSIGNMENT)
        val suggestion = learner.suggestFor(r)
        assertThat(suggestion).isNull()
    }

    @Test
    fun `repeated one-offs suggest making recurring`() = runTest {
        val store = FakeStore()
        val baseClock = clock("2026-05-25T09:00:00Z")
        val learner = PreferenceLearner(baseClock, store)
        learner.load()
        val r = reminder("2026-05-25T13:00:00Z", "lunch", ReminderCategory.MEAL)
        repeat(3) { learner.observeCreate(r) }
        val suggestion = learner.suggestFor(r)
        assertThat(suggestion).isNotNull()
        assertThat(suggestion!!.makeRecurring).isTrue()
    }

    @Test
    fun `single occurrence does not suggest recurring`() = runTest {
        val store = FakeStore()
        val learner = PreferenceLearner(clock("2026-05-25T09:00:00Z"), store)
        learner.load()
        val r = reminder("2026-05-25T13:00:00Z", "lunch", ReminderCategory.MEAL)
        learner.observeCreate(r)
        assertThat(learner.suggestFor(r)).isNull()
    }
}
