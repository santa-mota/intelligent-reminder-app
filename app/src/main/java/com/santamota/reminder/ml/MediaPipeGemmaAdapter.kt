package com.santamota.reminder.ml

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.santamota.reminder.domain.CancelScope
import com.santamota.reminder.domain.Intent
import com.santamota.reminder.domain.Recurrence
import com.santamota.reminder.domain.RelativeAnchor
import com.santamota.reminder.domain.ReminderCategory
import com.santamota.reminder.domain.ReminderMatch
import com.santamota.reminder.domain.ReminderType
import com.santamota.reminder.domain.TimeSpec
import com.santamota.reminder.nlu.ChatContext
import com.santamota.reminder.nlu.LlmAdapter
import com.santamota.reminder.nlu.PromptTemplates
import com.santamota.reminder.nlu.ResponsePrompt
import com.santamota.reminder.nlu.defaultResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Loads the active on-device model (chosen by the user in the Models tab)
 * and runs intent extraction + chat response composition through it.
 *
 * The path of the model file comes from the `ModelRegistry` + active-model
 * preference. If the file is missing, [isReady] returns false and the engine
 * silently falls back to the rule parser.
 */
class MediaPipeGemmaAdapter(
    private val appContext: Context,
    private val resolveModelPath: () -> String? = {
        // Default lookup; overridden by DI in production to read the active-
        // model id from DataStore. See AppModule.
        defaultModelPath(appContext)
    },
    private val maxTokens: Int = 1024,
) : LlmAdapter, AutoCloseable {

    @Volatile private var engine: LlmInference? = null
    @Volatile private var loadedPath: String? = null
    private val initLock = Any()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun isReady(): Boolean =
        // Fast-path: already loaded. Otherwise hop to a background dispatcher
        // before calling LlmInference.createFromOptions(), which is a blocking
        // 5–10s op that absolutely cannot run on the caller's coroutine
        // dispatcher when that's Dispatchers.Main (ChatViewModel's case).
        engine != null || withContext(Dispatchers.Default) { tryInit() }

    override suspend fun resolveIntent(rawInput: String, context: ChatContext): Intent {
        val out = generate(PromptTemplates.intentPrompt(rawInput, context))
            ?: return Intent.Ambiguous(rawInput, "model unavailable")
        return LlmIntentDecoder.decodeFromRaw(out, rawInput, json)
    }

    /**
     * Confirmation, error, "done" — these are short, deterministic strings
     * derived from already-structured data. The LLM has no useful work to do
     * here and tends to either echo the prompt verbatim or invent preamble
     * ("You're asking for a clear, unambiguous action…"). Templates give a
     * consistent, fast reply. The intent-extraction call is where the LLM
     * earns its keep, not the response phrasing.
     */
    override suspend fun composeResponse(prompt: ResponsePrompt, context: ChatContext): String =
        defaultResponse(prompt)

    private suspend fun generate(prompt: String): String? = withContext(Dispatchers.Default) {
        val eng = engine ?: if (tryInit()) engine else null
        eng?.runCatching { generateResponse(prompt) }?.getOrNull()
    }

    /**
     * Reloads the engine when the user picks a different active model. Cheap
     * to call repeatedly — does nothing if the same file is already loaded.
     */
    fun reload() {
        synchronized(initLock) {
            engine?.runCatching { close() }
            engine = null
            loadedPath = null
        }
    }

    private fun tryInit(): Boolean = synchronized(initLock) {
        val targetPath = resolveModelPath() ?: return false
        if (engine != null && loadedPath == targetPath) return true
        // Different path requested — tear down and reload.
        engine?.runCatching { close() }
        engine = null
        loadedPath = null

        val file = File(targetPath)
        if (!file.exists() || file.length() == 0L) return false
        return try {
            val opts = LlmInferenceOptions.builder()
                .setModelPath(targetPath)
                .setMaxTokens(maxTokens)
                .build()
            engine = LlmInference.createFromOptions(appContext, opts)
            loadedPath = targetPath
            true
        } catch (t: Throwable) {
            android.util.Log.w("LlmAdapter", "Failed to load model at $targetPath: ${t.message}")
            false
        }
    }

    override fun close() {
        engine?.runCatching { close() }
        engine = null
        loadedPath = null
    }

    /** Strip the trailing `<|im_end|>` token + leading whitespace some models emit. */
    private fun String.cleanReply(): String =
        trim()
            .removeSuffix("<|im_end|>")
            .removeSuffix("<|endoftext|>")
            .trim()

    companion object {
        fun defaultModelPath(ctx: Context): String =
            "${ctx.filesDir}/models/qwen2.5-0.5b-instruct.task"
    }
}

/**
 * Decodes the JSON object the LLM emits into our typed [Intent]. Lives
 * outside the adapter so it's unit-testable without MediaPipe on the
 * classpath.
 *
 * The decoder is intentionally permissive: missing fields default sensibly,
 * malformed values fall back to AMBIGUOUS rather than crashing the engine.
 */
internal object LlmIntentDecoder {

    fun decodeFromRaw(rawOut: String, rawInput: String, json: Json): Intent {
        // Tolerate model preamble/postamble — locate first '{' and last '}'.
        val start = rawOut.indexOf('{')
        val end = rawOut.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) {
            return Intent.Ambiguous(rawInput, "model returned non-JSON")
        }
        return try {
            val obj = json.parseToJsonElement(rawOut.substring(start, end + 1)).jsonObject
            decode(obj, rawInput)
        } catch (t: Throwable) {
            Intent.Ambiguous(rawInput, "json parse failed: ${t.message}")
        }
    }

    fun decode(obj: JsonObject, rawInput: String): Intent {
        val kind = obj["intent"]?.str()?.uppercase()
        return when (kind) {
            "CREATE" -> decodeCreate(obj, rawInput)
            "MOVE" -> decodeMove(obj, rawInput)
            "CANCEL" -> decodeCancel(obj, rawInput)
            "DONE" -> decodeDone(obj, rawInput)
            "DELAY" -> decodeDelay(obj, rawInput)
            "CHAT" -> Intent.Chat(rawInput)
            "AMBIGUOUS" -> Intent.Ambiguous(
                rawText = rawInput,
                reason = "model said ambiguous",
                clarifyQuestion = obj["clarify_question"]?.str(),
            )
            else -> Intent.Ambiguous(rawInput, "unknown intent: $kind")
        }
    }

    private fun decodeCreate(obj: JsonObject, rawInput: String): Intent {
        val title = obj["title"]?.str()?.trim()?.takeIf { it.isNotBlank() }
            ?: return Intent.Ambiguous(rawInput, "create without title")
        val description = obj["description"]?.str()?.trim()?.takeIf { it.isNotBlank() }
        val type = obj["type"]?.str()?.let {
            runCatching { ReminderType.valueOf(it.uppercase()) }.getOrNull()
        } ?: ReminderType.NOTIFICATION
        val category = obj["category"]?.str()?.let {
            runCatching { ReminderCategory.valueOf(it.uppercase()) }.getOrNull()
        } ?: ReminderCategory.UNCATEGORIZED
        val recurrence = obj["recurrence"]?.takeIfObject()?.let(::decodeRecurrence)
        val relativeTo = obj["relative_to"]?.takeIfObject()?.let(::decodeRelativeAnchor)
        val timeSpec = obj["time_spec"]?.takeIfObject()?.let(::decodeTimeSpec)
        // CREATE needs either a time_spec OR a relative_to anchor to be actionable.
        if (timeSpec == null && relativeTo == null) {
            return Intent.Ambiguous(
                rawText = rawInput,
                reason = "create without time or anchor",
                clarifyQuestion = "When would you like me to remind you about $title?",
            )
        }
        return Intent.Create(
            title = title,
            type = type,
            timeSpec = timeSpec ?: TimeSpec.RelativeToNow(Duration.ZERO),
            recurrence = recurrence,
            relativeTo = relativeTo,
            category = category,
            description = description,
        )
    }

    private fun decodeMove(obj: JsonObject, rawInput: String): Intent {
        val query = obj["match_query"]?.str() ?: return Intent.Ambiguous(rawInput, "move without target")
        val spec = obj["new_time_spec"]?.takeIfObject()?.let(::decodeTimeSpec)
            ?: return Intent.Ambiguous(rawInput, "move without new time")
        return Intent.Move(
            targetMatch = ReminderMatch(titleQuery = query),
            newTimeSpec = spec,
        )
    }

    private fun decodeCancel(obj: JsonObject, rawInput: String): Intent {
        val query = obj["match_query"]?.str() ?: return Intent.Ambiguous(rawInput, "cancel without target")
        val scope = obj["cancel_scope"]?.str()?.let {
            runCatching { CancelScope.valueOf(it.uppercase()) }.getOrNull()
        } ?: CancelScope.ALL_OCCURRENCES
        return Intent.Cancel(
            targetMatch = ReminderMatch(titleQuery = query),
            scope = scope,
        )
    }

    private fun decodeDone(obj: JsonObject, rawInput: String): Intent {
        val query = obj["match_query"]?.str() ?: return Intent.Ambiguous(rawInput, "done without target")
        return Intent.MarkDone(targetMatch = ReminderMatch(titleQuery = query))
    }

    private fun decodeDelay(obj: JsonObject, rawInput: String): Intent {
        val query = obj["match_query"]?.str() ?: return Intent.Ambiguous(rawInput, "delay without target")
        val bySec = obj["by_seconds"]?.long()
            ?: return Intent.Ambiguous(rawInput, "delay without duration")
        return Intent.Delay(
            targetMatch = ReminderMatch(titleQuery = query),
            by = Duration.ofSeconds(bySec),
        )
    }

    private fun decodeTimeSpec(obj: JsonObject): TimeSpec? {
        val kind = obj["kind"]?.str()?.uppercase() ?: return null
        return when (kind) {
            "AT" -> {
                val iso = obj["iso_at"]?.str() ?: return null
                runCatching {
                    val zone = ZoneId.systemDefault()
                    val ldt = LocalDateTime.parse(iso)
                    TimeSpec.Absolute(ldt.atZone(zone))
                }.getOrNull()
            }
            "IN" -> {
                val sec = obj["iso_offset_seconds"]?.long() ?: return null
                TimeSpec.RelativeToNow(Duration.ofSeconds(sec))
            }
            "DATE_TIME" -> {
                val date = obj["date"]?.str()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: return null
                val timeStr = obj["time_24h"]?.str() ?: return null
                val time = runCatching {
                    val parts = timeStr.split(":")
                    LocalTime.of(parts[0].toInt(), parts.getOrNull(1)?.toInt() ?: 0)
                }.getOrNull() ?: return null
                TimeSpec.DateAndTime(date, time)
            }
            else -> null
        }
    }

    private fun decodeRecurrence(obj: JsonObject): Recurrence? {
        val pattern = obj["pattern"]?.str()?.let {
            runCatching { Recurrence.Pattern.valueOf(it.uppercase()) }.getOrNull()
        } ?: return null
        val days = obj["days"]?.let { el ->
            runCatching {
                el.jsonObject  // not an object, throws; caught below
            }.getOrElse {
                // Expected: an array. kotlinx.serialization JsonArray.
                val arr = (el as? kotlinx.serialization.json.JsonArray) ?: return@let emptySet<DayOfWeek>()
                arr.mapNotNull { item ->
                    item.jsonPrimitive.contentOrNull()?.let { name ->
                        runCatching {
                            DayOfWeek.valueOf(name.uppercase().take(3).let { abbrev ->
                                // Accept "MON", "MONDAY", "mon", "monday"
                                when (abbrev) {
                                    "MON" -> "MONDAY"; "TUE" -> "TUESDAY"
                                    "WED" -> "WEDNESDAY"; "THU" -> "THURSDAY"
                                    "FRI" -> "FRIDAY"; "SAT" -> "SATURDAY"
                                    "SUN" -> "SUNDAY"
                                    else -> name.uppercase()
                                }
                            })
                        }.getOrNull()
                    }
                }.toSet()
            }
        }.let { it as? Set<DayOfWeek> } ?: emptySet()
        // Guard against DAILY+days (model sometimes emits days=[] explicitly for DAILY).
        val safeDays = if (pattern == Recurrence.Pattern.DAILY) emptySet() else days
        return Recurrence(pattern = pattern, daysOfWeek = safeDays)
    }

    private fun decodeRelativeAnchor(obj: JsonObject): RelativeAnchor? {
        val anchor = obj["anchor_title"]?.str() ?: return null
        val offset = obj["offset_seconds"]?.long() ?: return null
        return RelativeAnchor(
            match = ReminderMatch(titleQuery = anchor),
            offset = Duration.ofSeconds(offset),
        )
    }

    private fun JsonPrimitive.contentOrNull(): String? =
        try { content.takeIf { it.isNotBlank() && it != "null" } } catch (_: Throwable) { null }

    private fun kotlinx.serialization.json.JsonElement.str(): String? =
        (this as? JsonPrimitive)?.contentOrNull()

    private fun kotlinx.serialization.json.JsonElement.long(): Long? =
        (this as? JsonPrimitive)?.let { runCatching { it.content.toDouble().toLong() }.getOrNull() }

    private fun kotlinx.serialization.json.JsonElement.takeIfObject(): JsonObject? =
        this as? JsonObject
}
