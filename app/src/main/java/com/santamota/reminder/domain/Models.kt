package com.santamota.reminder.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * A reminder is anything the engine has scheduled — an alarm at a specific
 * time, or a notification fired ahead of an anchor event.
 *
 * The same data class covers all cases. The fields that distinguish them:
 *
 *   - [type]            ALARM (lockscreen takeover) vs NOTIFICATION (heads-up)
 *   - [recurrence]      null = one-time; otherwise repeats per pattern
 *   - [parentId]        non-null = "this is N before [parent]"; engine
 *                       recomputes [triggerAt] when parent moves
 *   - [relativeOffset]  paired with [parentId]; negative = before parent
 *   - [groupId]         loosely groups dependent reminders so they can be
 *                       cancelled / moved as a unit
 */
data class Reminder(
    val id: ReminderId = ReminderId.random(),
    val title: String,
    val description: String? = null,
    val type: ReminderType,
    val triggerAt: ZonedDateTime,
    val recurrence: Recurrence? = null,
    val parentId: ReminderId? = null,
    val relativeOffset: Duration? = null,
    val groupId: GroupId? = null,
    val status: ReminderStatus = ReminderStatus.ACTIVE,
    val exceptions: Set<LocalDate> = emptySet(),
    val category: ReminderCategory = ReminderCategory.UNCATEGORIZED,
    val tags: Set<String> = emptySet(),
    val createdAt: ZonedDateTime,
    val updatedAt: ZonedDateTime,
) {
    init {
        require(title.isNotBlank()) { "title cannot be blank" }
        require((parentId == null) == (relativeOffset == null)) {
            "parentId and relativeOffset must both be set or both be null"
        }
    }

    val isRelative: Boolean get() = parentId != null
    val isRecurring: Boolean get() = recurrence != null
}

@JvmInline
value class ReminderId(val value: String) {
    companion object {
        fun random(): ReminderId = ReminderId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class GroupId(val value: String) {
    companion object {
        fun random(): GroupId = GroupId(UUID.randomUUID().toString())
    }
}

enum class ReminderType { ALARM, NOTIFICATION }

enum class ReminderStatus { ACTIVE, COMPLETED, SKIPPED, DELETED }

/**
 * Recurrence pattern. Stored as a flat shape so it round-trips trivially
 * through JSON / Room.
 */
data class Recurrence(
    val pattern: Pattern,
    val interval: Int = 1,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val endDate: LocalDate? = null,
) {
    enum class Pattern { DAILY, WEEKLY, MONTHLY, YEARLY }

    init {
        require(interval >= 1) { "interval must be >= 1" }
        if (pattern == Pattern.WEEKLY) {
            // empty days means "same day of week as first occurrence"
        }
        require(pattern != Pattern.DAILY || daysOfWeek.isEmpty()) {
            "DAILY with daysOfWeek is ambiguous — use WEEKLY"
        }
    }
}

/**
 * Categorisation drives preference learning. Adding a new category is safe;
 * UNCATEGORIZED is the fallback so the engine never blocks.
 */
enum class ReminderCategory {
    ASSIGNMENT,   // deadline-driven schoolwork, tickets, submissions
    MEAL,         // breakfast/lunch/dinner
    MEDICINE,     // medication, supplements
    MEETING,      // calendar-like
    EXERCISE,     // workout, yoga, run
    APPOINTMENT,  // doctor, salon, service
    WAKE,         // morning alarm
    TRAVEL,       // flight, train, leave for X
    BILL,         // pay rent, utilities
    ERRAND,       // pick up groceries, etc.
    HABIT,        // drink water, journal
    UNCATEGORIZED,
}

/**
 * The shape the engine emits in response to a parsed user intent.
 *
 * One natural-language utterance can yield multiple reminders (e.g. "remind
 * me to submit assignment in 3 days, and ping me every 3 hours the last
 * day") — main reminder + lead-up reminders, all part of the same group.
 */
data class ReminderPlan(
    val main: Reminder,
    val leadUps: List<Reminder> = emptyList(),
    val rationale: String,
    val suggestion: PlanSuggestion? = null,
) {
    val allReminders: List<Reminder> get() = listOf(main) + leadUps
}

/**
 * A non-blocking suggestion the engine surfaces in chat (e.g. "based on past
 * behaviour, want me to make this recurring?"). The user can accept or
 * dismiss; both paths feed [PreferenceLearner].
 */
data class PlanSuggestion(
    val kind: Kind,
    val message: String,
    val payload: String,
) {
    enum class Kind {
        MAKE_RECURRING,
        APPLY_PAST_CADENCE,
        SET_LEAD_UPS,
        ADJUST_QUIET_HOURS,
    }
}
