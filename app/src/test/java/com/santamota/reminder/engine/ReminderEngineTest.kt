package com.santamota.reminder.engine

import com.google.common.truth.Truth.assertThat
import com.santamota.reminder.domain.Intent
import com.santamota.reminder.nlu.FakeLlmAdapter
import com.santamota.reminder.nlu.RuleBasedParser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class ReminderEngineTest {

    private val clock: Clock = Clock.fixed(
        Instant.parse("2026-05-25T12:00:00Z"),
        ZoneId.of("UTC"),
    )

    private fun build(): Quad {
        val reminderDao = FakeReminderDao()
        val chatDao = FakeChatDao()
        val scheduler = FakeAlarmScheduler()
        val store = object : PreferenceLearner.Store {
            var s = PreferenceProfile.EMPTY
            override suspend fun read() = s
            override suspend fun write(profile: PreferenceProfile) { s = profile }
        }
        val learner = PreferenceLearner(clock, store)
        val parser = RuleBasedParser(clock)
        val llm = FakeLlmAdapter(canned = { Intent.Ambiguous(it, "fake") })
        val engine = ReminderEngine(
            clock, parser, llm, reminderDao, chatDao, scheduler, learner,
        )
        return Quad(engine, reminderDao, scheduler, chatDao)
    }

    private data class Quad(
        val engine: ReminderEngine,
        val dao: FakeReminderDao,
        val scheduler: FakeAlarmScheduler,
        val chatDao: FakeChatDao,
    )

    @Test
    fun `create reminder schedules an alarm and stores reminder`() = runTest {
        val (engine, dao, scheduler, chatDao) = build()
        val reply = engine.handle("remind me at 3 PM to take a break")
        assertThat(reply.text).isNotEmpty()
        assertThat(dao.activeOnce()).hasSize(1)
        val r = dao.activeOnce().first()
        assertThat(r.title).contains("take a break")
        assertThat(scheduler.scheduled).containsKey(r.id)
        // Chat messages: user + agent
        assertThat(chatDao.snapshot().map { it.role })
            .containsExactly("USER", "AGENT").inOrder()
    }

    @Test
    fun `move cascades to relative children`() = runTest {
        val (engine, dao, scheduler, _) = build()
        engine.handle("remind me every day at 1 PM about lunch")
        engine.handle("remind me an hour before lunch to take medicine")
        // (relative anchor — but parent must already exist in DB)

        assertThat(dao.activeOnce().map { it.title }.any { it.contains("medicine") }).isTrue()

        engine.handle("move lunch to 2 PM")

        val lunch = dao.search("lunch").first()
        val medicine = dao.search("medicine").first()
        // Medicine should be 1 hour before lunch.
        assertThat(medicine.triggerEpochMs)
            .isEqualTo(lunch.triggerEpochMs - 60 * 60 * 1000)
    }

    @Test
    fun `done on one-time reminder marks completed and cancels alarm`() = runTest {
        val (engine, dao, scheduler, _) = build()
        engine.handle("remind me in 3 days to submit assignment")
        val before = dao.activeOnce().first()
        engine.handle("i submitted the assignment")
        // Should be COMPLETED now, not ACTIVE.
        assertThat(dao.activeOnce().any { it.id == before.id }).isFalse()
        assertThat(scheduler.cancelled).contains(before.id)
    }

    @Test
    fun `delay shifts reminder by given duration`() = runTest {
        val (engine, dao, _) = build()
        engine.handle("remind me at 3 PM to take a break")
        val original = dao.search("break").first()
        engine.handle("delay break by 30 min")
        val after = dao.search("break").first()
        assertThat(after.triggerEpochMs - original.triggerEpochMs)
            .isEqualTo(30 * 60 * 1000)
    }

    @Test
    fun `cancel removes one-time reminder`() = runTest {
        val (engine, dao, scheduler, _) = build()
        engine.handle("remind me at 3 PM to take a break")
        val before = dao.activeOnce().first()
        engine.handle("cancel break")
        assertThat(dao.activeOnce().none { it.id == before.id }).isTrue()
        assertThat(scheduler.cancelled).contains(before.id)
    }

    @Test
    fun `done on recurring reminder skips this occurrence`() = runTest {
        val (engine, dao, scheduler, _) = build()
        engine.handle("remind me every day at 1 PM about lunch")
        val before = dao.activeOnce().first()
        engine.handle("lunch is done")
        // Should still be ACTIVE (recurring), with a later triggerEpochMs OR
        // an exception list including today.
        val after = dao.byId(before.id)
        assertThat(after).isNotNull()
        assertThat(after!!.status).isEqualTo("ACTIVE")
        assertThat(after.triggerEpochMs).isGreaterThan(before.triggerEpochMs)
    }
}
