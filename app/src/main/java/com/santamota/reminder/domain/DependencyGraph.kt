package com.santamota.reminder.domain

import java.time.Duration
import java.time.ZonedDateTime

/**
 * In-memory view of "which reminders depend on which" — used to cascade
 * moves and deletions. Built fresh from the active reminder set each time
 * the engine needs to reason about a change; cheap because the set is
 * bounded by phone use (hundreds at most).
 */
class DependencyGraph(reminders: List<Reminder>) {

    private val byId: Map<ReminderId, Reminder> =
        reminders.associateBy { it.id }

    private val childrenByParent: Map<ReminderId, List<Reminder>> =
        reminders
            .filter { it.parentId != null }
            .groupBy { it.parentId!! }

    private val membersByGroup: Map<GroupId, List<Reminder>> =
        reminders
            .filter { it.groupId != null }
            .groupBy { it.groupId!! }

    fun get(id: ReminderId): Reminder? = byId[id]

    fun directChildrenOf(parentId: ReminderId): List<Reminder> =
        childrenByParent[parentId].orEmpty()

    /**
     * All descendants in BFS order. Catches "medicine before lunch" and
     * anything that hangs off the medicine reminder, etc.
     */
    fun allDescendantsOf(parentId: ReminderId): List<Reminder> {
        val out = mutableListOf<Reminder>()
        val seen = mutableSetOf(parentId)
        val queue = ArrayDeque<ReminderId>().apply { add(parentId) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (child in directChildrenOf(cur)) {
                if (child.id in seen) continue  // cycle guard
                seen += child.id
                out += child
                queue.add(child.id)
            }
        }
        return out
    }

    fun groupMembers(groupId: GroupId): List<Reminder> =
        membersByGroup[groupId].orEmpty()

    /**
     * Returns the reminder set that results from changing [target]'s trigger
     * time to [newTriggerAt], with all dependent children recomputed.
     *
     * Does not mutate inputs. Caller persists the result.
     */
    fun cascadeMove(target: Reminder, newTriggerAt: ZonedDateTime): List<Reminder> {
        val updated = mutableMapOf<ReminderId, Reminder>(target.id to target.copy(
            triggerAt = newTriggerAt,
            updatedAt = newTriggerAt,
        ))
        for (descendant in allDescendantsOf(target.id)) {
            val offset = descendant.relativeOffset
                ?: continue // safety: should not happen for descendants
            val parent = updated[descendant.parentId!!]
                ?: byId[descendant.parentId]
                ?: continue
            updated[descendant.id] = descendant.copy(
                triggerAt = parent.triggerAt.plus(offset),
                updatedAt = newTriggerAt,
            )
        }
        return updated.values.toList()
    }

    /**
     * What gets touched if we mark [target] DELETED. Returns the descendant
     * list so the engine can ask the user before fanning out.
     */
    fun cascadeDelete(target: Reminder): List<Reminder> {
        return allDescendantsOf(target.id)
    }
}

/**
 * Build a cluster of reminders for "lunch every day, medicine 1h before
 * lunch" — main + children, all sharing a [GroupId].
 */
fun buildCluster(
    main: Reminder,
    children: List<Pair<String, Duration>>, // (title, offset relative to main)
    now: ZonedDateTime,
    category: ReminderCategory = main.category,
): List<Reminder> {
    val groupId = main.groupId ?: GroupId.random()
    val mainWithGroup = main.copy(groupId = groupId)
    val childReminders = children.map { (title, offset) ->
        Reminder(
            title = title,
            type = ReminderType.NOTIFICATION,
            triggerAt = mainWithGroup.triggerAt.plus(offset),
            recurrence = mainWithGroup.recurrence,
            parentId = mainWithGroup.id,
            relativeOffset = offset,
            groupId = groupId,
            category = category,
            createdAt = now,
            updatedAt = now,
        )
    }
    return listOf(mainWithGroup) + childReminders
}
