package com.santamota.reminder.engine

import com.santamota.reminder.domain.ReminderCategory
import kotlinx.serialization.Serializable

/**
 * Per-user persisted preferences. Stored as JSON in DataStore. Plain data —
 * all logic lives in [PreferenceLearner].
 */
@Serializable
data class PreferenceProfile(
    val categoryCadences: Map<String, CadenceProfile> = emptyMap(),
    val defaultLeadTimeSeconds: Map<String, Long> = emptyMap(),
    val rejectedSuggestions: List<RejectedSuggestion> = emptyList(),
    val recurringCandidates: Map<String, RecurringCandidate> = emptyMap(),
    val quietHours: QuietHours? = null,
) {
    companion object {
        val EMPTY = PreferenceProfile()
    }
}

@Serializable
data class CadenceProfile(
    val leadDays: Int,                 // start lead-ups this many days before deadline
    val everyHours: Int,               // and ping every N hours
    val confirmCount: Int = 0,         // user confirmed this cadence this many times
    val lastUsedEpochMs: Long = 0L,
)

@Serializable
data class RejectedSuggestion(
    val category: String,              // ReminderCategory.name
    val suggestion: String,
    val whenEpochMs: Long,
)

/**
 * Tracks how often the user has manually created one-off reminders for the
 * same context, so we can offer to make it recurring after a threshold.
 */
@Serializable
data class RecurringCandidate(
    val signature: String,             // canonical "lunch@13:00" style key
    val occurrences: List<Long>,       // epoch ms of past creations
)

@Serializable
data class QuietHours(
    val startHour: Int,                // 0-23
    val endHour: Int,                  // 0-23 (wraps midnight if end < start)
)

/**
 * Suggestion the engine offers based on past behaviour. The chat layer
 * surfaces this as a confirm/dismiss chip.
 */
data class Suggestion(
    val category: ReminderCategory,
    val cadence: CadenceProfile?,
    val makeRecurring: Boolean = false,
    val message: String,
)
