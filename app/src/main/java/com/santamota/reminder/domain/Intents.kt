package com.santamota.reminder.domain

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * What a user utterance resolved to. The engine consumes these and produces
 * [ReminderPlan]s and chat responses.
 *
 * `Ambiguous` is the bail-out: parser couldn't get high confidence, so the
 * engine either asks for clarification or kicks the input to the LLM.
 */
sealed interface Intent {

    data class Create(
        val title: String,
        val type: ReminderType,
        val timeSpec: TimeSpec,
        val recurrence: Recurrence? = null,
        val relativeTo: RelativeAnchor? = null,
        val leadUps: List<Duration> = emptyList(),
        val category: ReminderCategory = ReminderCategory.UNCATEGORIZED,
        val description: String? = null,
    ) : Intent

    data class Move(
        val targetMatch: ReminderMatch,
        val newTimeSpec: TimeSpec,
    ) : Intent

    data class Cancel(
        val targetMatch: ReminderMatch,
        val scope: CancelScope = CancelScope.THIS_OCCURRENCE,
    ) : Intent

    /**
     * "I submitted the assignment" / "done" — distinct from cancel because
     * for one-time it also deletes lead-up siblings and *acknowledges*.
     */
    data class MarkDone(
        val targetMatch: ReminderMatch,
    ) : Intent

    data class Delay(
        val targetMatch: ReminderMatch,
        val by: Duration,
    ) : Intent

    /**
     * Pure-chat: "hi", "thanks", "how are you" — no scheduling action.
     */
    data class Chat(val text: String) : Intent

    /**
     * The LLM (or rule parser) couldn't decide. If [clarifyQuestion] is set,
     * surface it directly to the user — the LLM has already drafted the
     * follow-up. Otherwise the engine shows a generic "could you rephrase" fallback.
     */
    data class Ambiguous(
        val rawText: String,
        val reason: String,
        val clarifyQuestion: String? = null,
    ) : Intent
}

enum class CancelScope { THIS_OCCURRENCE, ALL_OCCURRENCES, ENTIRE_GROUP }

/**
 * How we resolve "lunch" or "my medicine reminder" to actual reminder rows.
 * Engine fuzzy-matches by title / tags / category.
 */
data class ReminderMatch(
    val titleQuery: String? = null,
    val category: ReminderCategory? = null,
    val tag: String? = null,
)

sealed interface TimeSpec {
    /** Absolute calendar time. */
    data class Absolute(val at: ZonedDateTime) : TimeSpec

    /** Today/tomorrow + a clock time. */
    data class DateAndTime(val date: LocalDate, val time: LocalTime) : TimeSpec

    /** "In 30 min" / "in 3 days". */
    data class RelativeToNow(val offset: Duration) : TimeSpec
}

/**
 * "An hour before lunch" — anchored to another event the user already has
 * scheduled (or we'll prompt them to create).
 */
data class RelativeAnchor(
    val match: ReminderMatch,
    val offset: Duration, // negative = before anchor
)
