package com.santamota.reminder.data.models

/**
 * Static registry of on-device LLM candidates the app can run.
 *
 * **Why only two entries.** Earlier versions of this list included SmolLM
 * 135M and TinyLlama 1.1B. Both were dropped after real-device testing:
 *
 *   - SmolLM 135M is too small to reliably follow our JSON schema. It
 *     produced invalid UTF-8 output tokens (logged by MediaPipe as
 *     `LlmResponseContext.responses contains invalid UTF-8 data`), and the
 *     intent decoder couldn't find a JSON object to parse. The app then
 *     silently fell back to the rule parser, which felt like the model
 *     wasn't being used at all.
 *   - TinyLlama 1.1B is an older base with weaker instruction following.
 *     It mostly worked but was inconsistent on structured output, which
 *     is the whole point of this app.
 *
 * Both options are still in commit history if we want to surface them again
 * behind an "advanced" toggle. For now: only models we trust to do the job.
 *
 * Constraints carried over from v1:
 *   - `.task` format compatible with MediaPipe `LlmInference`
 *   - Publicly downloadable URL (Gemma variants are all gated behind a
 *     license-acceptance wall, so unusable in a sideloaded app)
 *
 * Sizes quoted as q8 quantization (the only public variants on
 * `litert-community/`); int4 would halve the size but isn't published.
 */
data class ModelSpec(
    val id: String,              // stable key, used as filesDir filename
    val displayName: String,
    val tagline: String,         // one-line tradeoff
    val approxSizeMb: Int,
    val params: String,          // "0.5B", "1.5B" — display only
    val downloadUrl: String,
) {
    val fileName: String get() = "$id.task"
}

object ModelRegistry {

    /** Picked as default for the average phone. Snappy, valid JSON output. */
    const val DEFAULT_MODEL_ID = "qwen2.5-0.5b-instruct"

    val all: List<ModelSpec> = listOf(
        ModelSpec(
            id = DEFAULT_MODEL_ID,
            displayName = "Qwen 2.5 0.5B Instruct",
            tagline = "Recommended. Fast on Pixel-class, follows the JSON schema reliably.",
            approxSizeMb = 521,
            params = "0.5B",
            downloadUrl =
                "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/" +
                    "resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        ),
        ModelSpec(
            id = "qwen2.5-1.5b-instruct",
            displayName = "Qwen 2.5 1.5B Instruct",
            tagline = "Higher quality on edge cases. ~3× the prefill time on phone.",
            approxSizeMb = 1523,
            params = "1.5B",
            downloadUrl =
                "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/" +
                    "resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        ),
    )

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }
}
