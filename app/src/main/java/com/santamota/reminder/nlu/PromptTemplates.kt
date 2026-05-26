package com.santamota.reminder.nlu

/**
 * Single source of truth for the prompts we send to the on-device LLM.
 *
 * Two prompts: intent resolution (structured JSON) and chat response
 * composition (free text, short).
 */
object PromptTemplates {

    /**
     * Few-shot, JSON-output intent prompt. We instruct the model to emit a
     * single JSON object so we can parse with kotlinx.serialization. Keeping
     * the schema flat and the examples tight is more important than coverage
     * — the rule parser already handles the common cases.
     */
    fun intentPrompt(userText: String, context: ChatContext): String = buildString {
        appendLine("You are a reminder-app assistant. Your job is to read a user message and emit ONE JSON object describing the intent. No prose, no markdown, no code fences, JSON only.")
        appendLine()
        appendLine("Schema:")
        appendLine("""{ "intent": "CREATE"|"MOVE"|"CANCEL"|"DONE"|"DELAY"|"CHAT"|"AMBIGUOUS",""")
        appendLine("""  "title": string?, "type": "ALARM"|"NOTIFICATION"?,""")
        appendLine("""  "time_spec": { "kind": "AT"|"IN", "iso_at": string?, "iso_offset_seconds": number? }?,""")
        appendLine("""  "recurrence": { "pattern": "DAILY"|"WEEKLY"|"MONTHLY", "days": [string]? }?,""")
        appendLine("""  "relative_to": { "title": string, "offset_seconds": number }?,""")
        appendLine("""  "category": string?, "match_query": string?, "by_seconds": number? }""")
        appendLine()
        appendLine("Now: ${context.userTimezone}")
        if (context.activeReminderTitles.isNotEmpty()) {
            appendLine("Active reminders: ${context.activeReminderTitles.joinToString(", ")}")
        }
        appendLine()
        appendLine("Examples:")
        appendLine("""user: "remind me about dad's birthday in two weeks"""")
        appendLine("""json: {"intent":"CREATE","title":"dad's birthday","type":"NOTIFICATION","time_spec":{"kind":"IN","iso_offset_seconds":1209600},"category":"UNCATEGORIZED"}""")
        appendLine()
        appendLine("""user: "actually push lunch back 30 min"""")
        appendLine("""json: {"intent":"DELAY","match_query":"lunch","by_seconds":1800}""")
        appendLine()
        appendLine("""user: "skip yoga tomorrow"""")
        appendLine("""json: {"intent":"CANCEL","match_query":"yoga"}""")
        appendLine()
        for (turn in context.recentTurns.takeLast(3)) {
            val tag = if (turn.role == ChatTurn.Role.USER) "user" else "agent"
            appendLine("""$tag: "${turn.text.replace("\"", "'")}"""")
        }
        appendLine("""user: "$userText"""")
        append("json:")
    }

    /** Short, conversational reply. Keep under ~30 tokens to feel snappy. */
    fun responsePrompt(prompt: ResponsePrompt, context: ChatContext): String = buildString {
        appendLine("You are a friendly reminder assistant. Reply in 1-2 sentences, no emoji, no markdown.")
        appendLine()
        when (prompt) {
            is ResponsePrompt.Confirmation ->
                appendLine("Confirm this action plainly: ${prompt.summary}")
            is ResponsePrompt.Suggestion ->
                appendLine("Suggest gently: ${prompt.proposal}")
            is ResponsePrompt.Clarification ->
                appendLine("Ask this clarification: ${prompt.question}")
            is ResponsePrompt.Error ->
                appendLine("Apologise briefly: ${prompt.reason}")
            is ResponsePrompt.Done ->
                appendLine("Acknowledge the user's done action: ${prompt.whatHappened}")
        }
        appendLine()
        appendLine("Reply:")
    }
}
