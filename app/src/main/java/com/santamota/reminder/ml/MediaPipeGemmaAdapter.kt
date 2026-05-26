package com.santamota.reminder.ml

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.santamota.reminder.domain.Intent
import com.santamota.reminder.nlu.ChatContext
import com.santamota.reminder.nlu.LlmAdapter
import com.santamota.reminder.nlu.PromptTemplates
import com.santamota.reminder.nlu.ResponsePrompt
import com.santamota.reminder.nlu.defaultResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Real on-device LLM via MediaPipe `LlmInference` (Gemma 3 1B int4).
 *
 * The `.task` model file is expected at [modelPath] — typically
 * `<filesDir>/models/gemma-3-1b-it-int4.task`. If absent, [isReady] returns
 * false and callers should keep using the rule parser + templated responses.
 *
 * Lifecycle: lazy init on first use, kept warm in memory. Caller should
 * [close] on app exit to release native handles.
 */
class MediaPipeGemmaAdapter(
    private val appContext: Context,
    private val modelPath: String =
        "${appContext.filesDir}/models/gemma-3-1b-it-int4.task",
    private val maxTokens: Int = 512,
    private val temperature: Float = 0.2f,
) : LlmAdapter, AutoCloseable {

    @Volatile private var engine: LlmInference? = null
    private val initLock = Any()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun isReady(): Boolean = engine != null || tryInit()

    override suspend fun resolveIntent(rawInput: String, context: ChatContext): Intent {
        val out = generate(PromptTemplates.intentPrompt(rawInput, context))
            ?: return Intent.Ambiguous(rawInput, "model unavailable")
        return parseJsonIntent(out, rawInput)
    }

    override suspend fun composeResponse(prompt: ResponsePrompt, context: ChatContext): String =
        generate(PromptTemplates.responsePrompt(prompt, context))
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: defaultResponse(prompt)

    private suspend fun generate(prompt: String): String? = withContext(Dispatchers.Default) {
        val eng = engine ?: if (tryInit()) engine else null
        eng?.generateResponse(prompt)
    }

    private fun tryInit(): Boolean = synchronized(initLock) {
        if (engine != null) return@synchronized true
        val file = File(modelPath)
        if (!file.exists() || file.length() == 0L) return@synchronized false
        return@synchronized try {
            val opts = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .build()
            // Per-call sampling params (temperature, topK) live on
            // LlmInferenceSession in the current MediaPipe API. Builder no
            // longer exposes setTopK; we accept the defaults for now.
            engine = LlmInference.createFromOptions(appContext, opts)
            true
        } catch (t: Throwable) {
            android.util.Log.w("Gemma", "Failed to load Gemma model: ${t.message}")
            false
        }
    }

    private fun parseJsonIntent(rawOut: String, rawInput: String): Intent {
        // Tolerate model preamble/postamble — locate first '{' and last '}'.
        val start = rawOut.indexOf('{')
        val end = rawOut.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) {
            return Intent.Ambiguous(rawInput, "model returned non-JSON")
        }
        return try {
            val obj = json.parseToJsonElement(rawOut.substring(start, end + 1)).jsonObject
            LlmIntentDecoder.decode(obj, rawInput)
        } catch (t: Throwable) {
            Intent.Ambiguous(rawInput, "json parse failed: ${t.message}")
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}

/**
 * Decodes the JSON object the LLM emits into one of our [Intent]s. Lives
 * outside the adapter so it's unit-testable without MediaPipe on the
 * classpath.
 */
internal object LlmIntentDecoder {

    fun decode(obj: JsonObject, rawInput: String): Intent {
        val kind = obj["intent"]?.jsonPrimitive?.contentOrNullSafe()?.uppercase()
        return when (kind) {
            "CREATE" -> decodeCreate(obj, rawInput)
            "MOVE" -> decodeMove(obj, rawInput)
            "CANCEL" -> decodeCancel(obj, rawInput)
            "DONE" -> decodeDone(obj, rawInput)
            "DELAY" -> decodeDelay(obj, rawInput)
            "CHAT" -> Intent.Chat(rawInput)
            else -> Intent.Ambiguous(rawInput, "model said: $kind")
        }
    }

    // Decoders intentionally minimal — the engine validates / normalises
    // before persisting. Anything missing falls to Ambiguous.

    private fun decodeCreate(obj: JsonObject, rawInput: String): Intent {
        val title = obj["title"]?.jsonPrimitive?.contentOrNullSafe()
            ?: return Intent.Ambiguous(rawInput, "create without title")
        // Real implementation builds TimeSpec / Recurrence here. Skipped in
        // this skeleton; the engine should already have routed through the
        // rule parser first, so this code path mostly returns Chat fallback.
        return Intent.Chat(title)
    }

    private fun decodeMove(obj: JsonObject, rawInput: String): Intent =
        Intent.Ambiguous(rawInput, "model move decoding not yet wired")
    private fun decodeCancel(obj: JsonObject, rawInput: String): Intent =
        Intent.Ambiguous(rawInput, "model cancel decoding not yet wired")
    private fun decodeDone(obj: JsonObject, rawInput: String): Intent =
        Intent.Ambiguous(rawInput, "model done decoding not yet wired")
    private fun decodeDelay(obj: JsonObject, rawInput: String): Intent =
        Intent.Ambiguous(rawInput, "model delay decoding not yet wired")

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        try { content.takeIf { it.isNotBlank() && it != "null" } } catch (_: Throwable) { null }
}
