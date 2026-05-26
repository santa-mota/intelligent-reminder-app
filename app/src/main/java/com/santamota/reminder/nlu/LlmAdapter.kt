package com.santamota.reminder.nlu

import com.santamota.reminder.domain.Intent

/**
 * Falls back to an LLM when [RuleBasedParser] returns Ambiguous, or when we
 * need a conversational chat reply.
 *
 * The interface is intentionally tiny so it's trivial to swap implementations
 * (MediaPipe Gemma, ONNX Phi, llama.cpp, …) and trivial to fake in tests.
 */
interface LlmAdapter {

    /** Whether the model is loaded and ready. */
    suspend fun isReady(): Boolean

    /**
     * Best-effort intent resolution from a raw utterance. Uses few-shot
     * structured-output prompting (see [PromptTemplates]). Returns
     * [Intent.Ambiguous] if the model can't decide either.
     */
    suspend fun resolveIntent(rawInput: String, context: ChatContext): Intent

    /**
     * Compose a natural-language response from a structured plan + intent.
     * Falls back to a templated string if the model isn't ready.
     */
    suspend fun composeResponse(prompt: ResponsePrompt, context: ChatContext): String
}

/**
 * Recent chat history + lightweight state the LLM needs for coherent replies.
 * Kept small to fit the model's context window.
 */
data class ChatContext(
    val recentTurns: List<ChatTurn> = emptyList(),
    val activeReminderTitles: List<String> = emptyList(),
    val userTimezone: String = "UTC",
)

data class ChatTurn(
    val role: Role,
    val text: String,
) {
    enum class Role { USER, AGENT }
}

sealed interface ResponsePrompt {
    data class Confirmation(val summary: String) : ResponsePrompt
    data class Suggestion(val proposal: String) : ResponsePrompt
    data class Clarification(val question: String) : ResponsePrompt
    data class Error(val reason: String) : ResponsePrompt
    data class Done(val whatHappened: String) : ResponsePrompt
}

/**
 * In-tree fake that returns predictable values. Useful for unit tests + as a
 * "degraded mode" if the real model can't load on device.
 */
class FakeLlmAdapter(
    var ready: Boolean = true,
    var canned: (String) -> Intent = { Intent.Ambiguous(it, "fake adapter") },
    var responder: (ResponsePrompt) -> String = ::defaultResponse,
) : LlmAdapter {
    override suspend fun isReady(): Boolean = ready
    override suspend fun resolveIntent(rawInput: String, context: ChatContext): Intent =
        canned(rawInput)
    override suspend fun composeResponse(prompt: ResponsePrompt, context: ChatContext): String =
        responder(prompt)
}

internal fun defaultResponse(prompt: ResponsePrompt): String = when (prompt) {
    is ResponsePrompt.Confirmation -> "Got it. ${prompt.summary}"
    is ResponsePrompt.Suggestion -> "${prompt.proposal} Want me to go ahead?"
    is ResponsePrompt.Clarification -> prompt.question
    is ResponsePrompt.Error -> "Something didn't work: ${prompt.reason}"
    is ResponsePrompt.Done -> prompt.whatHappened
}
