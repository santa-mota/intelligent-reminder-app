package com.santamota.reminder.engine

import com.santamota.reminder.domain.Reminder
import com.santamota.reminder.domain.ReminderCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Maintains [PreferenceProfile] state and emits [Suggestion]s based on past
 * user choices.
 *
 * Persistence is delegated to [Store] (DataStore in production, in-memory in
 * tests).
 */
class PreferenceLearner(
    private val clock: Clock,
    private val store: Store,
) {

    interface Store {
        suspend fun read(): PreferenceProfile
        suspend fun write(profile: PreferenceProfile)
    }

    private val _profile = MutableStateFlow(PreferenceProfile.EMPTY)
    val profile: Flow<PreferenceProfile> = _profile.asStateFlow()

    suspend fun load() {
        _profile.value = store.read()
    }

    /**
     * Called whenever a reminder is created. Updates [RecurringCandidate]
     * for one-off reminders to track repeated patterns.
     */
    suspend fun observeCreate(reminder: Reminder) {
        if (reminder.isRecurring) return // already recurring; nothing to learn
        val sig = recurringSignature(reminder)
        update {
            val existing = it.recurringCandidates[sig]
            val nowMs = clock.millis()
            val updated = RecurringCandidate(
                signature = sig,
                occurrences = (existing?.occurrences.orEmpty() + nowMs).takeLast(5),
            )
            it.copy(recurringCandidates = it.recurringCandidates + (sig to updated))
        }
    }

    /**
     * Called when the user confirms a cadence suggestion (or sets one
     * explicitly). Bumps confidence counter.
     */
    suspend fun confirmCadence(category: ReminderCategory, cadence: CadenceProfile) {
        update {
            val key = category.name
            val existing = it.categoryCadences[key]
            val merged = cadence.copy(
                confirmCount = (existing?.confirmCount ?: 0) + 1,
                lastUsedEpochMs = clock.millis(),
            )
            it.copy(categoryCadences = it.categoryCadences + (key to merged))
        }
    }

    suspend fun rejectSuggestion(category: ReminderCategory, suggestion: String) {
        update {
            it.copy(
                rejectedSuggestions = (it.rejectedSuggestions + RejectedSuggestion(
                    category = category.name,
                    suggestion = suggestion,
                    whenEpochMs = clock.millis(),
                )).takeLast(50),
            )
        }
    }

    /**
     * Given a freshly-parsed reminder, returns a suggestion the engine should
     * present, or null if nothing to suggest.
     */
    suspend fun suggestFor(reminder: Reminder): Suggestion? {
        val profile = _profile.value
        // 1. If a high-confidence cadence exists for this category and the
        //    reminder doesn't yet have lead-ups, suggest applying it.
        profile.categoryCadences[reminder.category.name]
            ?.takeIf { it.confirmCount >= 3 }
            ?.let { cadence ->
                val rejectedRecently = profile.rejectedSuggestions.any {
                    it.category == reminder.category.name &&
                        clock.millis() - it.whenEpochMs < THIRTY_DAYS_MS
                }
                if (rejectedRecently) return@let
                return Suggestion(
                    category = reminder.category,
                    cadence = cadence,
                    message = "Previously you asked me to remind you every ${cadence.everyHours}h starting ${cadence.leadDays}d before. Same here?",
                )
            }
        // 2. If user has created similar one-offs 3+ times recently, suggest
        //    making it recurring.
        val sig = recurringSignature(reminder)
        val candidate = profile.recurringCandidates[sig]
        if (candidate != null && candidate.occurrences.size >= 3 &&
            withinCadence(candidate.occurrences, Duration.ofDays(2))
        ) {
            return Suggestion(
                category = reminder.category,
                cadence = null,
                makeRecurring = true,
                message = "I've noticed you set this ${candidate.occurrences.size} times around the same time — want me to make it daily?",
            )
        }
        return null
    }

    private fun withinCadence(occurrences: List<Long>, window: Duration): Boolean {
        val sorted = occurrences.sorted()
        val gaps = sorted.zipWithNext { a, b -> b - a }
        return gaps.isNotEmpty() && gaps.all { it <= window.toMillis() * 1.5 }
    }

    private fun recurringSignature(r: Reminder): String {
        val time: LocalTime = r.triggerAt.toLocalTime().withSecond(0).withNano(0)
        return "${r.category.name}|${r.title.lowercase().take(30)}|${time}"
    }

    private suspend fun update(transform: (PreferenceProfile) -> PreferenceProfile) {
        val updated = transform(_profile.value)
        _profile.value = updated
        store.write(updated)
    }

    private companion object {
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
