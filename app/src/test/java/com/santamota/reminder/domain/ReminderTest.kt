package com.santamota.reminder.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderTest {

    private val now = ZonedDateTime.of(2026, 5, 25, 9, 0, 0, 0, ZoneId.of("UTC"))

    @Test
    fun `blank title is rejected`() {
        assertThrows<IllegalArgumentException> {
            Reminder(
                title = "  ",
                type = ReminderType.ALARM,
                triggerAt = now,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    @Test
    fun `parentId without offset is rejected`() {
        assertThrows<IllegalArgumentException> {
            Reminder(
                title = "x",
                type = ReminderType.NOTIFICATION,
                triggerAt = now,
                parentId = ReminderId.random(),
                relativeOffset = null,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    @Test
    fun `offset without parent is rejected`() {
        assertThrows<IllegalArgumentException> {
            Reminder(
                title = "x",
                type = ReminderType.NOTIFICATION,
                triggerAt = now,
                parentId = null,
                relativeOffset = Duration.ofHours(1),
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    @Test
    fun `isRelative reflects parent presence`() {
        val standalone = Reminder(
            title = "x", type = ReminderType.ALARM, triggerAt = now,
            createdAt = now, updatedAt = now,
        )
        assertThat(standalone.isRelative).isFalse()

        val withParent = standalone.copy(
            parentId = ReminderId.random(),
            relativeOffset = Duration.ofHours(-1),
        )
        assertThat(withParent.isRelative).isTrue()
    }
}
