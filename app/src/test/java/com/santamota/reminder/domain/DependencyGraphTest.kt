package com.santamota.reminder.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class DependencyGraphTest {

    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 5, 25, 12, 0, 0, 0, zone)

    private fun rem(
        title: String,
        triggerAt: ZonedDateTime,
        parent: Reminder? = null,
        offset: Duration? = null,
        group: GroupId? = null,
    ): Reminder = Reminder(
        title = title,
        type = ReminderType.NOTIFICATION,
        triggerAt = triggerAt,
        parentId = parent?.id,
        relativeOffset = offset,
        groupId = group ?: parent?.groupId,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `cascadeMove updates parent and children by their offsets`() {
        val lunch = rem("lunch", now)
        val medicine = rem("medicine", now.minusHours(1),
            parent = lunch, offset = Duration.ofHours(-1))
        val wash = rem("wash hands", now.minusMinutes(15),
            parent = lunch, offset = Duration.ofMinutes(-15))

        val graph = DependencyGraph(listOf(lunch, medicine, wash))
        val newLunchTime = now.plusMinutes(90) // 1:30 PM

        val updated = graph.cascadeMove(lunch, newLunchTime).associateBy { it.id }

        assertThat(updated[lunch.id]!!.triggerAt).isEqualTo(newLunchTime)
        assertThat(updated[medicine.id]!!.triggerAt)
            .isEqualTo(newLunchTime.minusHours(1))
        assertThat(updated[wash.id]!!.triggerAt)
            .isEqualTo(newLunchTime.minusMinutes(15))
    }

    @Test
    fun `cascadeMove handles multi-level descendants`() {
        val lunch = rem("lunch", now)
        val medicine = rem("medicine", now.minusHours(1),
            parent = lunch, offset = Duration.ofHours(-1))
        // someone might want a reminder "5 min before medicine" — chained
        val prep = rem("prep medicine", now.minusHours(1).minusMinutes(5),
            parent = medicine, offset = Duration.ofMinutes(-5))

        val graph = DependencyGraph(listOf(lunch, medicine, prep))
        val newLunchTime = now.plusHours(2)

        val updated = graph.cascadeMove(lunch, newLunchTime).associateBy { it.id }

        assertThat(updated[medicine.id]!!.triggerAt)
            .isEqualTo(newLunchTime.minusHours(1))
        assertThat(updated[prep.id]!!.triggerAt)
            .isEqualTo(newLunchTime.minusHours(1).minusMinutes(5))
    }

    @Test
    fun `cascadeMove leaves unrelated reminders alone`() {
        val lunch = rem("lunch", now)
        val medicine = rem("medicine", now.minusHours(1),
            parent = lunch, offset = Duration.ofHours(-1))
        val unrelated = rem("workout", now.plusHours(5))

        val graph = DependencyGraph(listOf(lunch, medicine, unrelated))
        val updated = graph.cascadeMove(lunch, now.plusMinutes(30))
            .map { it.id }

        assertThat(updated).contains(lunch.id)
        assertThat(updated).contains(medicine.id)
        assertThat(updated).doesNotContain(unrelated.id)
    }

    @Test
    fun `cascadeDelete returns all descendants in BFS order`() {
        val lunch = rem("lunch", now)
        val medicine = rem("medicine", now.minusHours(1),
            parent = lunch, offset = Duration.ofHours(-1))
        val prep = rem("prep", now.minusHours(1).minusMinutes(5),
            parent = medicine, offset = Duration.ofMinutes(-5))
        val wash = rem("wash", now.minusMinutes(10),
            parent = lunch, offset = Duration.ofMinutes(-10))

        val graph = DependencyGraph(listOf(lunch, medicine, prep, wash))
        val toDelete = graph.cascadeDelete(lunch).map { it.title }

        assertThat(toDelete).containsExactly("medicine", "wash", "prep").inOrder()
    }

    @Test
    fun `cascade handles dependency cycle safely (no infinite loop)`() {
        // Synthetic cycle — shouldn't be creatable via normal API but the
        // graph must not lock up if data is corrupt.
        val a = rem("a", now)
        val b = rem("b", now, parent = a, offset = Duration.ofMinutes(-30))
        val aCorrupted = a.copy(
            parentId = b.id,
            relativeOffset = Duration.ofMinutes(-30),
        )
        val graph = DependencyGraph(listOf(aCorrupted, b))
        assertThat(graph.allDescendantsOf(aCorrupted.id).size).isAtMost(2)
    }

    @Test
    fun `buildCluster wires parent and children with shared group`() {
        val main = Reminder(
            title = "lunch",
            type = ReminderType.ALARM,
            triggerAt = now,
            recurrence = Recurrence(Recurrence.Pattern.DAILY),
            category = ReminderCategory.MEAL,
            createdAt = now,
            updatedAt = now,
        )
        val cluster = buildCluster(
            main = main,
            children = listOf(
                "medicine" to Duration.ofHours(-1),
                "wash hands" to Duration.ofMinutes(-15),
            ),
            now = now,
        )

        assertThat(cluster).hasSize(3)
        val (parent, med, wash) = cluster
        assertThat(parent.groupId).isNotNull()
        assertThat(med.groupId).isEqualTo(parent.groupId)
        assertThat(wash.groupId).isEqualTo(parent.groupId)
        assertThat(med.parentId).isEqualTo(parent.id)
        assertThat(med.recurrence).isEqualTo(parent.recurrence)
        assertThat(med.triggerAt).isEqualTo(now.minusHours(1))
    }
}
