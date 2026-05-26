package com.santamota.reminder.data.models

/**
 * Static registry of on-device LLM candidates the app can run.
 *
 * The set is curated for two reasons:
 *   - We need `.task` format files compatible with MediaPipe `LlmInference`.
 *   - We need *publicly-downloadable* URLs (no Kaggle/HuggingFace license
 *     wall). That requirement disqualifies all Gemma variants — every
 *     `litert-community/Gemma*` repo is gated. The four below are the
 *     practical set that exists today.
 *
 * Sizes are quoted as int8 quantization (the only public variants on
 * `litert-community/`). int4 would be ~half the size but isn't published
 * for these models.
 */
data class ModelSpec(
    val id: String,              // stable key, used as filesDir filename
    val displayName: String,
    val tagline: String,         // one-line tradeoff
    val approxSizeMb: Int,
    val params: String,          // "0.5B", "1.1B" — display only
    val downloadUrl: String,
) {
    val fileName: String get() = "$id.task"
}

object ModelRegistry {

    /** Picked as default for the average phone. Snappy, decent JSON output. */
    const val DEFAULT_MODEL_ID = "qwen2.5-0.5b-instruct"

    val all: List<ModelSpec> = listOf(
        ModelSpec(
            id = "smollm-135m-instruct",
            displayName = "SmolLM 135M Instruct",
            tagline = "Fastest. Light on context — best for trivial inputs only.",
            approxSizeMb = 159,
            params = "135M",
            downloadUrl =
                "https://huggingface.co/litert-community/SmolLM-135M-Instruct/" +
                    "resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
        ),
        ModelSpec(
            id = DEFAULT_MODEL_ID,
            displayName = "Qwen 2.5 0.5B Instruct",
            tagline = "Balanced default. Snappy on Pixel-class, good JSON extraction.",
            approxSizeMb = 521,
            params = "0.5B",
            downloadUrl =
                "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/" +
                    "resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        ),
        ModelSpec(
            id = "tinyllama-1.1b-chat",
            displayName = "TinyLlama 1.1B Chat",
            tagline = "Chattier voice. Older base — weaker at structured output.",
            approxSizeMb = 1095,
            params = "1.1B",
            downloadUrl =
                "https://huggingface.co/litert-community/TinyLlama-1.1B-Chat-v1.0/" +
                    "resolve/main/TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task",
        ),
        ModelSpec(
            id = "qwen2.5-1.5b-instruct",
            displayName = "Qwen 2.5 1.5B Instruct",
            tagline = "Highest quality under 2 GB. Slower prefill on phone.",
            approxSizeMb = 1523,
            params = "1.5B",
            downloadUrl =
                "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/" +
                    "resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        ),
    )

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }
}
