package com.santamota.reminder.nlu

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Prompts the on-device LLM (default: Qwen 2.5 0.5B Instruct) consumes.
 *
 * The single non-obvious design choice: we wrap with Qwen's chat template
 * (`<|im_start|> ... <|im_end|>`) because the litert-community `.task` files
 * are packaged expecting the caller to apply the template. MediaPipe's
 * `LlmInference.generateResponse(prompt)` does NOT apply chat templating
 * itself for these community task files. Without the template, Qwen 2.5
 * tends to ignore the system instruction and produce chatty prose.
 *
 * The few-shot examples lean toward *rich extraction*: titles minus filler
 * words, context preserved as `description`, and `clarify_question` populated
 * whenever the input is too vague to act on. That's the whole point of the
 * LLM-first refactor — to extract *more* than a regex ever could, not just
 * the same fields.
 */
object PromptTemplates {

    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * Builds the full chat-templated prompt for intent extraction.
     *
     * Output contract: a single JSON object matching the schema documented
     * in the system message. The model has been instructed to emit nothing
     * else; [com.santamota.reminder.ml.LlmIntentDecoder] tolerates leading
     * preamble by locating the first `{` and last `}`.
     */
    fun intentPrompt(userText: String, context: ChatContext): String {
        val nowStr = LocalDateTime.now().format(isoFormatter)
        val activeList = context.activeReminderTitles.take(8)
            .joinToString(", ").ifBlank { "(none yet)" }
        val history = context.recentTurns.takeLast(4).joinToString("\n") {
            val who = if (it.role == ChatTurn.Role.USER) "User" else "Assistant"
            "$who: ${it.text.lineSummary()}"
        }.ifBlank { "(no prior turns)" }

        val system = buildString {
            appendLine("You are the intent-extraction module of a reminders app.")
            appendLine("Your only job is to turn one user message into ONE JSON object describing what they want.")
            appendLine("Reply with the JSON object alone. No markdown, no code fences, no prose.")
            appendLine()
            appendLine("Schema:")
            appendLine("{")
            appendLine("""  "intent": "CREATE" | "MOVE" | "CANCEL" | "DONE" | "DELAY" | "CHAT" | "AMBIGUOUS",""")
            appendLine("""  "title": string,                       // short, action-form, no filler words. required for CREATE.""")
            appendLine("""  "description": string | null,          // any context the user gave (mood, motivation, who, why). Preserve it verbatim if useful.""")
            appendLine("""  "type": "ALARM" | "NOTIFICATION",      // ALARM = wakes the phone with full screen + sound; NOTIFICATION = heads-up. Default NOTIFICATION.""")
            appendLine("""  "time_spec": {""")
            appendLine("""    "kind": "AT" | "IN" | "DATE_TIME",""")
            appendLine("""    "iso_at": "YYYY-MM-DDTHH:MM" | null, // for AT""")
            appendLine("""    "iso_offset_seconds": number | null, // for IN""")
            appendLine("""    "date": "YYYY-MM-DD" | null,         // for DATE_TIME""")
            appendLine("""    "time_24h": "HH:MM" | null           // for DATE_TIME""")
            appendLine("""  } | null,""")
            appendLine("""  "recurrence": { "pattern": "DAILY"|"WEEKLY"|"MONTHLY"|"YEARLY", "days": [string] } | null,""")
            appendLine("""  "relative_to": { "anchor_title": string, "offset_seconds": number } | null,""")
            appendLine("""  "category": "ASSIGNMENT"|"MEAL"|"MEDICINE"|"MEETING"|"EXERCISE"|"APPOINTMENT"|"WAKE"|"TRAVEL"|"BILL"|"ERRAND"|"HABIT"|"UNCATEGORIZED",""")
            appendLine("""  "match_query": string | null,          // for MOVE/CANCEL/DONE/DELAY: which existing reminder to act on""")
            appendLine("""  "new_time_spec": { ... } | null,       // for MOVE: the new time""")
            appendLine("""  "by_seconds": number | null,           // for DELAY: how much later""")
            appendLine("""  "cancel_scope": "THIS_OCCURRENCE"|"ALL_OCCURRENCES"|"ENTIRE_GROUP" | null,""")
            appendLine("""  "clarify_question": string | null      // set on AMBIGUOUS if you can phrase a good follow-up""")
            appendLine("}")
            appendLine()
            appendLine("Rules:")
            appendLine("- Now is $nowStr. Resolve 'today', 'tomorrow', 'next monday' against this.")
            appendLine("- 'wake me' / 'alarm' → type=ALARM. Otherwise NOTIFICATION.")
            appendLine("- 'every X' → set recurrence. Daily/weekly/monthly/yearly.")
            appendLine("- 'N before/after <event>' → relative_to + offset_seconds (negative = before).")
            appendLine("- For DONE: extract the reminder name into match_query. Skip 'done with', 'I finished', 'I submitted' filler.")
            appendLine("- For CANCEL: 'skip X today' → cancel_scope=THIS_OCCURRENCE. 'cancel/delete X' → ALL_OCCURRENCES.")
            appendLine("- If user says 'I'm anxious' / 'this is important' / 'don't let me miss this' → still NOTIFICATION but capture that in description.")
            appendLine("- If the message is small-talk (hi, thanks) → intent=CHAT, no other fields needed.")
            appendLine("- If you genuinely can't tell what they want or it's missing required info, return intent=AMBIGUOUS with a polite clarify_question.")
            appendLine()
            appendLine("Active reminders (for context): $activeList")
            appendLine("Recent turns:")
            appendLine(history)
            appendLine()
            // Few-shot examples — kept tight so total prompt stays <800 tokens.
            // Each example exercises a different intent + a different field
            // shape; adding more hurts latency on the 135M tier and helps
            // little on the larger ones.
            appendLine("Examples:")
            appendLine("""user: wake me at 7am""")
            appendLine("""json: {"intent":"CREATE","title":"wake up","type":"ALARM","time_spec":{"kind":"DATE_TIME","date":"${todayStr()}","time_24h":"07:00"},"category":"WAKE"}""")
            appendLine("""user: take medicine an hour before lunch""")
            appendLine("""json: {"intent":"CREATE","title":"take medicine","type":"NOTIFICATION","relative_to":{"anchor_title":"lunch","offset_seconds":-3600},"category":"MEDICINE"}""")
            appendLine("""user: I'm anxious about the dentist tomorrow at 3""")
            appendLine("""json: {"intent":"CREATE","title":"dentist","description":"User is anxious","type":"NOTIFICATION","time_spec":{"kind":"DATE_TIME","date":"${tomorrowStr()}","time_24h":"15:00"},"category":"APPOINTMENT"}""")
            appendLine("""user: move lunch to 1:30""")
            appendLine("""json: {"intent":"MOVE","match_query":"lunch","new_time_spec":{"kind":"DATE_TIME","time_24h":"13:30"}}""")
            appendLine("""user: I finished my essay""")
            appendLine("""json: {"intent":"DONE","match_query":"essay"}""")
            appendLine("""user: skip yoga today""")
            appendLine("""json: {"intent":"CANCEL","match_query":"yoga","cancel_scope":"THIS_OCCURRENCE"}""")
            appendLine("""user: do the thing""")
            appendLine("""json: {"intent":"AMBIGUOUS","clarify_question":"Which reminder do you mean, and what do you want me to do with it?"}""")
        }

        return qwenTemplate(system = system.trim(), user = userText)
    }

    /**
     * Brief conversational reply. The engine still uses it for free-form
     * "Got it" / "Cancelled" / "Acknowledge user's chat" turns where the
     * exact wording isn't safety-critical.
     */
    fun responsePrompt(prompt: ResponsePrompt, context: ChatContext): String {
        val system = """
            You are a friendly, brief reminder assistant. Reply in 1-2 sentences.
            No emoji, no markdown. Sound natural, not robotic.
        """.trimIndent()

        val user = when (prompt) {
            is ResponsePrompt.Confirmation -> "Confirm this action plainly: ${prompt.summary}"
            is ResponsePrompt.Suggestion -> "Suggest gently: ${prompt.proposal}"
            is ResponsePrompt.Clarification -> "Ask this clarification: ${prompt.question}"
            is ResponsePrompt.Error -> "Apologise briefly: ${prompt.reason}"
            is ResponsePrompt.Done -> "Acknowledge: ${prompt.whatHappened}"
        }
        return qwenTemplate(system = system, user = user)
    }

    /**
     * Qwen 2.5 / SmolLM / TinyLlama all accept the ChatML template, so we
     * standardize on this format across model variants. MediaPipe community
     * `.task` files don't auto-apply templates — caller must.
     */
    private fun qwenTemplate(system: String, user: String): String = buildString {
        appendLine("<|im_start|>system")
        appendLine(system)
        appendLine("<|im_end|>")
        appendLine("<|im_start|>user")
        appendLine(user)
        appendLine("<|im_end|>")
        appendLine("<|im_start|>assistant")
    }

    private fun todayStr(): String = java.time.LocalDate.now().toString()
    private fun tomorrowStr(): String = java.time.LocalDate.now().plusDays(1).toString()

    private fun String.lineSummary(maxLen: Int = 80): String =
        replace("\n", " ").take(maxLen).let { if (length > maxLen) "$it…" else it }
}
