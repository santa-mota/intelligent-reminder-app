package com.santamota.reminder.engine

import com.santamota.reminder.data.alarm.AlarmScheduler
import com.santamota.reminder.data.db.ChatDao
import com.santamota.reminder.data.db.ChatMessageEntity
import com.santamota.reminder.data.db.ReminderDao
import com.santamota.reminder.data.db.toDomain
import com.santamota.reminder.data.db.toEntity
import com.santamota.reminder.domain.CancelScope
import com.santamota.reminder.domain.DependencyGraph
import com.santamota.reminder.domain.Intent
import com.santamota.reminder.domain.nextOccurrenceFrom
import com.santamota.reminder.domain.Recurrence
import com.santamota.reminder.domain.Reminder
import com.santamota.reminder.domain.ReminderCategory
import com.santamota.reminder.domain.ReminderMatch
import com.santamota.reminder.domain.ReminderPlan
import com.santamota.reminder.domain.ReminderStatus
import com.santamota.reminder.domain.ReminderType
import com.santamota.reminder.domain.TimeSpec
import com.santamota.reminder.nlu.ChatContext
import com.santamota.reminder.nlu.ChatTurn
import com.santamota.reminder.nlu.LlmAdapter
import com.santamota.reminder.nlu.ResponsePrompt
import com.santamota.reminder.nlu.RuleBasedParser
import kotlinx.coroutines.flow.firstOrNull
import java.time.Clock
import java.time.ZonedDateTime

/**
 * Orchestrator: takes a user utterance, parses → plans → persists →
 * schedules → composes a chat reply.
 *
 * All long-running calls are suspend so the chat ViewModel can `viewModelScope.launch`
 * without blocking the UI.
 */
class ReminderEngine(
    private val clock: Clock,
    private val parser: RuleBasedParser,
    private val llm: LlmAdapter,
    private val reminderDao: ReminderDao,
    private val chatDao: ChatDao,
    private val scheduler: AlarmScheduler,
    private val learner: PreferenceLearner,
) {

    /**
     * Handle a user message. Persists both the user turn and the agent
     * response to the chat log; returns the agent reply (UI can stream it).
     */
    suspend fun handle(userText: String): EngineReply {
        chatDao.insert(ChatMessageEntity(
            role = "USER", text = userText, createdEpochMs = clock.millis(),
        ))

        val ctx = ChatContext(
            recentTurns = recentTurns(),
            activeReminderTitles = reminderDao.activeOnce().map { it.title }.take(10),
            userTimezone = clock.zone.id,
        )

        val intent = when (val rule = parser.parse(userText)) {
            is Intent.Ambiguous -> if (llm.isReady()) llm.resolveIntent(userText, ctx) else rule
            else -> rule
        }

        val reply = dispatch(intent, ctx)

        chatDao.insert(ChatMessageEntity(
            role = "AGENT",
            text = reply.text,
            createdEpochMs = clock.millis(),
            intentKind = intent.javaClass.simpleName,
        ))
        return reply
    }

    // --- intent dispatch -----------------------------------------------

    private suspend fun dispatch(intent: Intent, ctx: ChatContext): EngineReply = when (intent) {
        is Intent.Create -> handleCreate(intent, ctx)
        is Intent.Move -> handleMove(intent, ctx)
        is Intent.Cancel -> handleCancel(intent, ctx)
        is Intent.MarkDone -> handleDone(intent, ctx)
        is Intent.Delay -> handleDelay(intent, ctx)
        is Intent.Chat -> EngineReply(
            text = llm.composeResponse(
                ResponsePrompt.Clarification("Acknowledge briefly: ${intent.text}"),
                ctx,
            ),
        )
        is Intent.Ambiguous -> EngineReply(
            text = "I didn't quite catch that. Could you rephrase? (e.g. \"remind me at 5pm to take vitamins\")",
        )
    }

    private suspend fun handleCreate(intent: Intent.Create, ctx: ChatContext): EngineReply {
        val now = ZonedDateTime.now(clock)
        // Resolve relativeTo: find parent reminder, if any.
        val parent = intent.relativeTo?.let { rel ->
            findOne(rel.match)
                ?: return EngineReply(
                    text = "I couldn't find a \"${rel.match.titleQuery}\" reminder to anchor against. When is it?",
                    needsClarification = true,
                )
        }

        val triggerAt = when {
            parent != null && intent.relativeTo != null ->
                parent.triggerAt.plus(intent.relativeTo!!.offset)
            else -> intent.timeSpec.resolveTo(now)
        }

        val main = Reminder(
            title = intent.title,
            description = null,
            type = intent.type,
            triggerAt = triggerAt,
            recurrence = intent.recurrence ?: parent?.recurrence,
            parentId = parent?.id,
            relativeOffset = intent.relativeTo?.offset,
            groupId = parent?.groupId,
            category = intent.category,
            createdAt = now,
            updatedAt = now,
        )

        // Plan lead-ups (the "every 3 hours starting day before" behaviour).
        val plan = ReminderPlan(
            main = main,
            leadUps = buildLeadUps(main, intent.leadUps, now),
            rationale = "Created from: ${intent.title}",
        )

        persist(plan.allReminders)
        scheduler.rescheduleAll(plan.allReminders)
        learner.observeCreate(main)

        val suggestion = learner.suggestFor(main)
        val summary = describePlan(plan)
        val reply = llm.composeResponse(ResponsePrompt.Confirmation(summary), ctx)
        return EngineReply(text = reply, suggestion = suggestion, plan = plan)
    }

    private fun buildLeadUps(
        main: Reminder,
        explicitOffsets: List<java.time.Duration>,
        now: ZonedDateTime,
    ): List<Reminder> {
        if (explicitOffsets.isEmpty()) return emptyList()
        return explicitOffsets.map { offset ->
            Reminder(
                title = "Reminder: ${main.title}",
                type = ReminderType.NOTIFICATION,
                triggerAt = main.triggerAt.plus(offset),
                recurrence = main.recurrence,
                parentId = main.id,
                relativeOffset = offset,
                groupId = main.groupId ?: com.santamota.reminder.domain.GroupId.random(),
                category = main.category,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    private suspend fun handleMove(intent: Intent.Move, ctx: ChatContext): EngineReply {
        val target = findOne(intent.targetMatch) ?: return notFound(intent.targetMatch.titleQuery)
        val newTime = intent.newTimeSpec.resolveTo(ZonedDateTime.now(clock))
        val active = activeReminders()
        val updated = DependencyGraph(active).cascadeMove(target, newTime)
        persist(updated)
        scheduler.rescheduleAll(updated)
        val summary = if (updated.size == 1) {
            "Moved ${target.title} to ${humanTime(newTime)}."
        } else {
            "Moved ${target.title} to ${humanTime(newTime)} — ${updated.size - 1} dependent reminder(s) shifted with it."
        }
        return EngineReply(text = llm.composeResponse(ResponsePrompt.Done(summary), ctx))
    }

    private suspend fun handleCancel(intent: Intent.Cancel, ctx: ChatContext): EngineReply {
        val target = findOne(intent.targetMatch) ?: return notFound(intent.targetMatch.titleQuery)
        return when (intent.scope) {
            CancelScope.THIS_OCCURRENCE -> {
                if (target.recurrence != null) {
                    val updated = target.copy(
                        exceptions = target.exceptions + target.triggerAt.toLocalDate(),
                    )
                    val nextOcc = updated.nextOccurrenceFrom(
                        ZonedDateTime.now(clock).plusSeconds(1)
                    )
                    val withNewTrigger = nextOcc?.let { updated.copy(triggerAt = it) } ?: updated
                    persist(listOf(withNewTrigger))
                    scheduler.schedule(withNewTrigger)
                    EngineReply(text = "Skipped today's ${target.title}. Next one is ${nextOcc?.let { humanTime(it) } ?: "the end of the recurrence"}.")
                } else {
                    deleteWithDescendants(target)
                    EngineReply(text = "Cancelled ${target.title}.")
                }
            }
            CancelScope.ALL_OCCURRENCES, CancelScope.ENTIRE_GROUP -> {
                deleteWithDescendants(target)
                EngineReply(text = "Cancelled ${target.title} and ${target.recurrence?.let { "all future occurrences" } ?: "any dependent reminders"}.")
            }
        }
    }

    private suspend fun handleDone(intent: Intent.MarkDone, ctx: ChatContext): EngineReply {
        val target = findOne(intent.targetMatch) ?: return notFound(intent.targetMatch.titleQuery)
        return if (target.recurrence != null) {
            // Recurring: skip this occurrence; tell user the next one.
            val updated = target.copy(
                exceptions = target.exceptions + target.triggerAt.toLocalDate(),
            )
            val nextOcc = updated.nextOccurrenceFrom(
                ZonedDateTime.now(clock).plusSeconds(1)
            )
            val withNewTrigger = nextOcc?.let { updated.copy(triggerAt = it) } ?: updated
            persist(listOf(withNewTrigger))
            scheduler.schedule(withNewTrigger)
            EngineReply(
                text = "Nice — done with ${target.title}. " + (nextOcc?.let {
                    "You'll be reminded again at ${humanTime(it)}."
                } ?: "No more reminders scheduled."),
            )
        } else {
            // One-time: delete + cascade dependents.
            val deps = activeReminders()
                .let { DependencyGraph(it) }
                .cascadeDelete(target)
            (listOf(target) + deps).forEach {
                reminderDao.setStatus(it.id.value, ReminderStatus.COMPLETED.name, clock.millis())
                scheduler.cancel(it.id.value)
            }
            val tail = if (deps.isEmpty()) "" else " Cleared ${deps.size} related reminder(s)."
            EngineReply(text = "Nice — marking ${target.title} done.$tail")
        }
    }

    private suspend fun handleDelay(intent: Intent.Delay, ctx: ChatContext): EngineReply {
        val target = findOne(intent.targetMatch) ?: return notFound(intent.targetMatch.titleQuery)
        val newTime = target.triggerAt.plus(intent.by)
        val active = activeReminders()
        val updated = DependencyGraph(active).cascadeMove(target, newTime)
        persist(updated)
        scheduler.rescheduleAll(updated)
        return EngineReply(
            text = "Delayed ${target.title} by ${humanDuration(intent.by)} — now at ${humanTime(newTime)}.",
        )
    }

    // --- helpers --------------------------------------------------------

    private suspend fun findOne(match: ReminderMatch): Reminder? {
        val q = match.titleQuery?.trim().orEmpty()
        if (q.isBlank()) return null
        val rows = reminderDao.search(q)
        return rows.firstOrNull()?.toDomain()
    }

    private suspend fun activeReminders(): List<Reminder> =
        reminderDao.activeOnce().map { it.toDomain() }

    private suspend fun persist(reminders: List<Reminder>) {
        reminderDao.replaceCluster(reminders.map { it.toEntity() })
    }

    private suspend fun deleteWithDescendants(target: Reminder) {
        val deps = DependencyGraph(activeReminders()).cascadeDelete(target)
        (listOf(target) + deps).forEach {
            reminderDao.setStatus(it.id.value, ReminderStatus.DELETED.name, clock.millis())
            scheduler.cancel(it.id.value)
        }
    }

    private suspend fun recentTurns(limit: Int = 6): List<ChatTurn> {
        // Latest [limit] messages, chronological order. Bounded so we don't
        // blow the LLM's context window.
        val rows = chatDao.observeLatest(limit).firstOrNull() ?: return emptyList()
        return rows.reversed().mapNotNull {
            val role = runCatching { ChatTurn.Role.valueOf(it.role) }.getOrNull() ?: return@mapNotNull null
            ChatTurn(role = role, text = it.text)
        }
    }

    private fun notFound(q: String?): EngineReply =
        EngineReply(text = "I couldn't find a reminder matching \"${q ?: "that"}\".")

    private fun TimeSpec.resolveTo(now: ZonedDateTime): ZonedDateTime = when (this) {
        is TimeSpec.Absolute -> at
        is TimeSpec.DateAndTime -> ZonedDateTime.of(date, time, now.zone)
        is TimeSpec.RelativeToNow -> now.plus(offset)
    }

    private fun describePlan(plan: ReminderPlan): String {
        val main = plan.main
        val base = "Scheduled ${main.title} at ${humanTime(main.triggerAt)}"
        val rec = main.recurrence?.let { " (${it.pattern.name.lowercase()})" } ?: ""
        val leads = if (plan.leadUps.isEmpty()) "" else
            ", with ${plan.leadUps.size} lead-up reminder(s)"
        return "$base$rec$leads."
    }

    private fun humanTime(t: ZonedDateTime): String =
        t.toLocalDateTime().toString().replace('T', ' ')

    private fun humanDuration(d: java.time.Duration): String {
        val h = d.toHours()
        val m = d.toMinutes() % 60
        return buildString {
            if (h > 0) append("${h}h ")
            if (m > 0 || h == 0L) append("${m}m")
        }.trim()
    }
}

data class EngineReply(
    val text: String,
    val needsClarification: Boolean = false,
    val plan: ReminderPlan? = null,
    val suggestion: Suggestion? = null,
)
