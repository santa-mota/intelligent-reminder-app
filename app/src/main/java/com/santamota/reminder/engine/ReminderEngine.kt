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
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime

/**
 * What the engine needs from the user's next message before it can finish
 * the current task. Single-slot — at most one outstanding clarification.
 */
sealed interface PendingClarification {
    /**
     * User asked for "X before lunch" but no lunch reminder exists. We
     * keep the original intent around and apply it once the user tells us
     * when lunch is.
     */
    data class NeedAnchorSchedule(
        val originalIntent: Intent.Create,
        val anchorTitle: String,
    ) : PendingClarification

    /**
     * Multiple reminders matched the user's query. Show them numbered;
     * resume the original action once they pick one.
     */
    data class DisambiguateMatch(
        val originalIntent: Intent,
        val candidates: List<Reminder>,
    ) : PendingClarification
}

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

    @Volatile private var pending: PendingClarification? = null

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

        // Multi-turn: if we asked something, try to apply the answer first.
        pending?.let { p ->
            val resolved = tryResolvePending(userText, p, ctx)
            if (resolved != null) {
                pending = null
                logAgent(resolved.text, "Continuation")
                return resolved
            }
            // The user changed topic; drop the pending question silently.
            pending = null
        }

        // LLM-first: when the model is loaded, the LLM is the primary parser.
        // The rule parser is the degraded fallback for:
        //   1. The model file isn't present / failed to load
        //   2. Inference exceeded LLM_TIMEOUT_MS (slow hardware, stuck model)
        //   3. The LLM returned Ambiguous with no rule-side recourse
        //
        // The timeout matters more than it looks. On a Pixel 8 Pro, Qwen 2.5
        // 0.5B int8 produces a structured response in ~2s. On the qemu
        // emulator with no host GPU, the same call can take 5+ minutes —
        // unbounded "thinking..." is worse UX than a fast rule-based fallback.
        val intent: Intent = if (llm.isReady()) {
            val llmIntent = withTimeoutOrNull(LLM_TIMEOUT_MS) { llm.resolveIntent(userText, ctx) }
            when {
                llmIntent == null -> {
                    // Timed out — degrade to rules so the user gets *some*
                    // answer instead of staring at "thinking..." forever.
                    android.util.Log.w("ReminderEngine", "LLM timeout — falling back to rule parser")
                    parser.parse(userText)
                }
                llmIntent is Intent.Ambiguous -> {
                    val rule = parser.parse(userText)
                    if (rule !is Intent.Ambiguous) rule else llmIntent
                }
                else -> llmIntent
            }
        } else {
            parser.parse(userText)
        }

        val reply = dispatch(intent, ctx)
        logAgent(reply.text, intent.javaClass.simpleName)
        return reply
    }

    private suspend fun logAgent(text: String, intentKind: String) {
        chatDao.insert(ChatMessageEntity(
            role = "AGENT",
            text = text,
            createdEpochMs = clock.millis(),
            intentKind = intentKind,
        ))
    }

    private suspend fun tryResolvePending(
        userText: String,
        p: PendingClarification,
        ctx: ChatContext,
    ): EngineReply? = when (p) {
        is PendingClarification.NeedAnchorSchedule -> resolveAnchor(userText, p, ctx)
        is PendingClarification.DisambiguateMatch -> resolveDisambiguation(userText, p, ctx)
    }

    private suspend fun resolveAnchor(
        userText: String,
        p: PendingClarification.NeedAnchorSchedule,
        ctx: ChatContext,
    ): EngineReply? {
        // User should reply with a time spec for the anchor (e.g. "at 1 PM"
        // or "every day at 1 PM"). We parse it as a Create for the anchor.
        val parsed = parser.parse("remind me about ${p.anchorTitle} $userText")
        val anchorIntent = parsed as? Intent.Create ?: return null
        val anchorReply = handleCreate(anchorIntent.copy(title = p.anchorTitle), ctx)
        if (anchorReply.plan == null) return null
        // Now retry the original relative intent against the new anchor.
        val followUp = handleCreate(p.originalIntent, ctx)
        val combined = "${anchorReply.text}\n\n${followUp.text}"
        return EngineReply(text = combined, plan = followUp.plan, suggestion = followUp.suggestion)
    }

    private suspend fun resolveDisambiguation(
        userText: String,
        p: PendingClarification.DisambiguateMatch,
        ctx: ChatContext,
    ): EngineReply? {
        val pick = pickFromText(userText, p.candidates) ?: return null
        return executeAgainst(p.originalIntent, pick, ctx)
    }

    /** "1", "2", "the first one", "first", or an exact title match. */
    private fun pickFromText(text: String, candidates: List<Reminder>): Reminder? {
        val cleaned = text.trim().lowercase()
        cleaned.toIntOrNull()?.let { n ->
            if (n in 1..candidates.size) return candidates[n - 1]
        }
        val ordinals = mapOf(
            "first" to 0, "1st" to 0, "one" to 0,
            "second" to 1, "2nd" to 1, "two" to 1,
            "third" to 2, "3rd" to 2, "three" to 2,
        )
        ordinals.entries.firstOrNull { cleaned.contains(it.key) }
            ?.let { return candidates[it.value] }
        return candidates.firstOrNull {
            it.title.equals(cleaned, ignoreCase = true) ||
                cleaned.contains(it.title.lowercase())
        }
    }

    private suspend fun executeAgainst(
        intent: Intent,
        target: Reminder,
        ctx: ChatContext,
    ): EngineReply = when (intent) {
        is Intent.Move -> handleMoveOn(target, intent.newTimeSpec, ctx)
        is Intent.Cancel -> handleCancelOn(target, intent.scope, ctx)
        is Intent.MarkDone -> handleDoneOn(target, ctx)
        is Intent.Delay -> handleDelayOn(target, intent.by, ctx)
        else -> EngineReply(text = "Sorry, I lost track of what I was doing.")
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
        is Intent.Ambiguous -> {
            val llmAvailable = llm.isReady()
            val text = intent.clarifyQuestion
                ?: if (!llmAvailable) {
                    // The rule parser is the only thing running — be honest
                    // about why behavior feels dumb, and point at the fix.
                    "I couldn't parse that on the simple path. Open the **Models** tab and pick an on-device LLM for smarter understanding."
                } else {
                    "I didn't quite catch that. Could you rephrase? (e.g. \"remind me at 5pm to take vitamins\")"
                }
            EngineReply(
                text = text,
                needsClarification = intent.clarifyQuestion != null,
            )
        }
    }

    private suspend fun handleCreate(intent: Intent.Create, ctx: ChatContext): EngineReply {
        val now = ZonedDateTime.now(clock)
        // Resolve relativeTo: find parent reminder, if any.
        val parent: Reminder? = intent.relativeTo?.let { rel ->
            when (val f = findReminder(rel.match)) {
                is FindResult.One -> f.r
                is FindResult.Many -> {
                    pending = PendingClarification.DisambiguateMatch(intent, f.rs)
                    return ambiguityReply(rel.match.titleQuery, f.rs)
                }
                FindResult.None -> {
                    val anchorTitle = rel.match.titleQuery ?: "the event"
                    pending = PendingClarification.NeedAnchorSchedule(intent, anchorTitle)
                    return EngineReply(
                        text = "I don't have a \"$anchorTitle\" reminder yet. When is it? (e.g. \"every day at 1 PM\")",
                        needsClarification = true,
                    )
                }
                FindResult.QueryTooShort -> return EngineReply(
                    text = "Can you tell me which reminder to anchor against? \"${rel.match.titleQuery}\" is too short to find a unique match.",
                )
            }
        }

        val triggerAt = when {
            parent != null && intent.relativeTo != null ->
                parent.triggerAt.plus(intent.relativeTo!!.offset)
            else -> intent.timeSpec.resolveTo(now)
        }

        val main = Reminder(
            title = intent.title,
            description = intent.description,
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
        val summary = describePlan(plan, parent = parent)
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

    private suspend fun handleMove(intent: Intent.Move, ctx: ChatContext): EngineReply =
        gateAndDispatch(intent, intent.targetMatch) { handleMoveOn(it, intent.newTimeSpec, ctx) }

    private suspend fun handleMoveOn(
        target: Reminder, spec: TimeSpec, ctx: ChatContext,
    ): EngineReply {
        val newTime = spec.resolveTo(ZonedDateTime.now(clock))
        val active = activeReminders()
        val updated = DependencyGraph(active).cascadeMove(target, newTime)
        persist(updated)
        scheduler.rescheduleAll(updated)
        val movedTitle = target.title
        val newTimeStr = humanWhen(target.copy(triggerAt = newTime))
        val summary = if (updated.size == 1) {
            "Moved $movedTitle — now $newTimeStr."
        } else {
            "Moved $movedTitle — now $newTimeStr. ${updated.size - 1} dependent reminder(s) shifted with it."
        }
        return EngineReply(text = llm.composeResponse(ResponsePrompt.Done(summary), ctx))
    }

    private suspend fun handleCancel(intent: Intent.Cancel, ctx: ChatContext): EngineReply =
        gateAndDispatch(intent, intent.targetMatch) { handleCancelOn(it, intent.scope, ctx) }

    private suspend fun handleCancelOn(
        target: Reminder, scope: CancelScope, ctx: ChatContext,
    ): EngineReply {
        return when (scope) {
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

    private suspend fun handleDone(intent: Intent.MarkDone, ctx: ChatContext): EngineReply =
        gateAndDispatch(intent, intent.targetMatch) { handleDoneOn(it, ctx) }

    private suspend fun handleDoneOn(target: Reminder, ctx: ChatContext): EngineReply {
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

    private suspend fun handleDelay(intent: Intent.Delay, ctx: ChatContext): EngineReply =
        gateAndDispatch(intent, intent.targetMatch) { handleDelayOn(it, intent.by, ctx) }

    private suspend fun handleDelayOn(
        target: Reminder, by: Duration, ctx: ChatContext,
    ): EngineReply {
        val newTime = target.triggerAt.plus(by)
        val active = activeReminders()
        val updated = DependencyGraph(active).cascadeMove(target, newTime)
        persist(updated)
        scheduler.rescheduleAll(updated)
        val newTimeStr = humanWhen(target.copy(triggerAt = newTime))
        return EngineReply(
            text = "Delayed ${target.title} by ${humanDuration(by)} — now $newTimeStr.",
        )
    }

    // --- helpers --------------------------------------------------------

    /**
     * Match the user's query against active reminders.
     *
     * Guardrails:
     *  - Refuses queries shorter than 3 chars (would match too aggressively).
     *  - If the search returns multiple rows but exactly one is an exact
     *    title match (case-insensitive), picks that one. Else returns Many.
     *  - Returns None when nothing matches.
     */
    private suspend fun findReminder(match: ReminderMatch): FindResult {
        val q = match.titleQuery?.trim().orEmpty()
        if (q.isBlank()) return FindResult.None
        if (q.length < MIN_QUERY_LEN) return FindResult.QueryTooShort
        val rows = reminderDao.search(q).map { it.toDomain() }
        return when (rows.size) {
            0 -> FindResult.None
            1 -> FindResult.One(rows.first())
            else -> {
                val exact = rows.filter { it.title.equals(q, ignoreCase = true) }
                when (exact.size) {
                    1 -> FindResult.One(exact.first())
                    else -> FindResult.Many(rows)
                }
            }
        }
    }

    /**
     * Glue used by handleMove / handleCancel / handleDone / handleDelay.
     * Resolves the target with [findReminder]; on Many, sets pending
     * disambiguation and emits a numbered choice prompt; on None or
     * QueryTooShort, emits a friendly explanation. Otherwise hands the
     * matched reminder to [action].
     */
    private suspend fun gateAndDispatch(
        intent: Intent,
        match: ReminderMatch,
        action: suspend (Reminder) -> EngineReply,
    ): EngineReply = when (val r = findReminder(match)) {
        is FindResult.One -> action(r.r)
        is FindResult.Many -> {
            pending = PendingClarification.DisambiguateMatch(intent, r.rs)
            ambiguityReply(match.titleQuery, r.rs)
        }
        FindResult.None -> notFound(match.titleQuery)
        FindResult.QueryTooShort -> EngineReply(
            text = "\"${match.titleQuery}\" is too short — could you tell me the full reminder title?",
        )
    }

    private fun ambiguityReply(query: String?, candidates: List<Reminder>): EngineReply {
        val numbered = candidates.mapIndexed { i, r ->
            "${i + 1}. ${r.title} (${humanWhen(r)})"
        }.joinToString("\n")
        return EngineReply(
            text = "I found ${candidates.size} reminders matching \"${query ?: ""}\":\n$numbered\n\nWhich one? (reply with the number)",
            needsClarification = true,
        )
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

    private fun describePlan(plan: ReminderPlan, parent: Reminder? = null): String {
        val main = plan.main
        val when_ = humanWhen(main)
        val relative = if (parent != null && main.relativeOffset != null) {
            val abs = main.relativeOffset!!.abs()
            val direction = if (main.relativeOffset!!.isNegative) "before" else "after"
            " (${humanDuration(abs)} $direction ${parent.title})"
        } else ""
        val leads = if (plan.leadUps.isEmpty()) "" else
            ", plus ${plan.leadUps.size} lead-up reminder(s)"
        // For ALARM-type reminders we hand off to the system Clock app via
        // AlarmClock.ACTION_SET_ALARM. Telling the user where it lives makes
        // it discoverable and explains why it's not in our Reminders tab.
        val destination = if (main.type == com.santamota.reminder.domain.ReminderType.ALARM) {
            " I've set this as a system alarm — check your phone's Clock app."
        } else ""
        return "${main.title.replaceFirstChar { it.uppercase() }} — $when_$relative$leads.$destination"
    }

    /**
     * Speaks the schedule like a person would:
     *   one-time on a future day → "tomorrow at 3:00 PM" or "Tue May 26 at 3:00 PM"
     *   one-time later today      → "today at 3:00 PM"
     *   daily                     → "every day at 1:00 PM"
     *   weekly                    → "every Monday at 9:00 AM"
     *   monthly                   → "on the 15th at 9:00 AM"
     */
    private fun humanWhen(r: Reminder): String {
        val time = r.triggerAt.toLocalTime()
        val timeStr = humanClock(time)
        val rec = r.recurrence
        return when {
            rec == null -> {
                val date = r.triggerAt.toLocalDate()
                val today = java.time.LocalDate.now(clock)
                when {
                    date == today -> "today at $timeStr"
                    date == today.plusDays(1) -> "tomorrow at $timeStr"
                    else -> "${date.format(SHORT_DATE_FMT)} at $timeStr"
                }
            }
            rec.pattern == Recurrence.Pattern.DAILY ->
                if (rec.interval == 1) "every day at $timeStr"
                else "every ${rec.interval} days at $timeStr"
            rec.pattern == Recurrence.Pattern.WEEKLY -> {
                val days = rec.daysOfWeek.takeIf { it.isNotEmpty() }
                    ?: setOf(r.triggerAt.dayOfWeek)
                val dayStr = days
                    .sortedBy { it.value }
                    .joinToString(", ") {
                        it.name.lowercase().replaceFirstChar { c -> c.uppercase() }
                    }
                "every $dayStr at $timeStr"
            }
            rec.pattern == Recurrence.Pattern.MONTHLY ->
                "on the ${ordinal(r.triggerAt.dayOfMonth)} of every month at $timeStr"
            rec.pattern == Recurrence.Pattern.YEARLY ->
                "every year on ${r.triggerAt.format(SHORT_DATE_FMT)} at $timeStr"
            else -> "at $timeStr"
        }
    }

    private fun humanClock(t: java.time.LocalTime): String =
        t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.getDefault()))

    private fun ordinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    private fun humanTime(t: ZonedDateTime): String =
        "${humanClock(t.toLocalTime())} on ${t.toLocalDate().format(SHORT_DATE_FMT)}"

    private val SHORT_DATE_FMT = java.time.format.DateTimeFormatter.ofPattern(
        "EEE MMM d", java.util.Locale.getDefault(),
    )

    private fun humanDuration(d: java.time.Duration): String {
        val h = d.toHours()
        val m = d.toMinutes() % 60
        return buildString {
            if (h > 0) append("${h}h ")
            if (m > 0 || h == 0L) append("${m}m")
        }.trim()
    }

    private companion object {
        // Anything shorter feels too risky to fuzzy-match — "do" matching
        // "doctor" would let users accidentally cancel unrelated reminders.
        const val MIN_QUERY_LEN = 3

        // Bounded wait for LLM intent extraction. Generous for real hardware
        // (a Pixel 8 Pro is ~2s) but firm enough to keep the UI responsive
        // when the emulator or a stuck model would otherwise hang.
        const val LLM_TIMEOUT_MS = 25_000L
    }
}

/**
 * Three-way result from [ReminderEngine.findReminder]. Lets callers
 * disambiguate / explain instead of silently picking the first match.
 */
sealed interface FindResult {
    data class One(val r: Reminder) : FindResult
    data class Many(val rs: List<Reminder>) : FindResult
    data object None : FindResult
    data object QueryTooShort : FindResult
}

data class EngineReply(
    val text: String,
    val needsClarification: Boolean = false,
    val plan: ReminderPlan? = null,
    val suggestion: Suggestion? = null,
)
