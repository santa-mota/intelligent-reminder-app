package com.santamota.reminder.engine

import com.santamota.reminder.data.alarm.AlarmScheduler
import com.santamota.reminder.data.db.ChatDao
import com.santamota.reminder.data.db.ChatMessageEntity
import com.santamota.reminder.data.db.ReminderDao
import com.santamota.reminder.data.db.ReminderEntity
import com.santamota.reminder.domain.Reminder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory fakes used by engine tests. Match the DAO contracts but
 * persist to a HashMap.
 */
class FakeReminderDao : ReminderDao {

    private val store = linkedMapOf<String, ReminderEntity>()
    private val flow = MutableStateFlow<List<ReminderEntity>>(emptyList())

    override fun observeActive(): Flow<List<ReminderEntity>> = flow.asStateFlow()

    override suspend fun activeOnce(): List<ReminderEntity> =
        store.values.filter { it.status == "ACTIVE" }

    override suspend fun byId(id: String): ReminderEntity? = store[id]

    override suspend fun search(q: String): List<ReminderEntity> =
        store.values.filter {
            it.status == "ACTIVE" &&
                (it.title.lowercase().contains(q.lowercase()) ||
                    it.tags.lowercase().contains(q.lowercase()))
        }

    override suspend fun childrenOf(parentId: String): List<ReminderEntity> =
        store.values.filter { it.parentId == parentId }

    override suspend fun byGroup(groupId: String): List<ReminderEntity> =
        store.values.filter { it.groupId == groupId }

    override suspend fun upsert(entity: ReminderEntity) {
        store[entity.id] = entity
        publish()
    }

    override suspend fun upsertAll(entities: List<ReminderEntity>) {
        for (e in entities) store[e.id] = e
        publish()
    }

    override suspend fun update(entity: ReminderEntity) {
        store[entity.id] = entity
        publish()
    }

    override suspend fun setStatus(id: String, status: String, now: Long) {
        val cur = store[id] ?: return
        store[id] = cur.copy(status = status, updatedEpochMs = now)
        publish()
    }

    override suspend fun delete(id: String) {
        store.remove(id)
        publish()
    }

    override suspend fun replaceCluster(entities: List<ReminderEntity>) =
        upsertAll(entities)

    private fun publish() {
        flow.value = store.values.filter { it.status == "ACTIVE" }
            .sortedBy { it.triggerEpochMs }
    }
}

class FakeChatDao : ChatDao {
    private val store = mutableListOf<ChatMessageEntity>()
    private val flow = MutableStateFlow<List<ChatMessageEntity>>(emptyList())

    override fun observeLatest(limit: Int): Flow<List<ChatMessageEntity>> =
        flow.asStateFlow()

    override suspend fun insert(message: ChatMessageEntity): Long {
        val id = (store.maxOfOrNull { it.id } ?: 0L) + 1
        val withId = message.copy(id = id)
        store += withId
        flow.value = store.takeLast(100)
        return id
    }

    override suspend fun trimOldest(n: Int) {
        repeat(n.coerceAtMost(store.size)) { store.removeFirst() }
    }

    override suspend fun count(): Int = store.size

    fun snapshot(): List<ChatMessageEntity> = store.toList()
}

class FakeAlarmScheduler : AlarmScheduler {
    val scheduled = mutableMapOf<String, Reminder>()
    val cancelled = mutableSetOf<String>()
    override fun schedule(reminder: Reminder) {
        scheduled[reminder.id.value] = reminder
        cancelled -= reminder.id.value
    }
    override fun cancel(reminderId: String) {
        scheduled.remove(reminderId)
        cancelled += reminderId
    }
    override fun rescheduleAll(reminders: List<Reminder>) {
        for (r in reminders) schedule(r)
    }
}
