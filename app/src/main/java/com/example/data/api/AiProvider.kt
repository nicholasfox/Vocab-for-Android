package com.example.data.api

enum class AiProvider(
    val displayName: String,
    val defaultModel: String,
    val keyUrl: String,
    val recommendedModels: List<String>,
    val defaultBaseUrl: String = "",
    val supportsVision: Boolean = true
) {
    GEMINI(
        "Google Gemini",
        "gemini-3.6-flash",
        "https://aistudio.google.com/app/apikey",
        listOf(
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-flash-latest"
        ),
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta"
    ),
    OPENAI(
        "OpenAI",
        "gpt-5.6-luna",
        "https://platform.openai.com/api-keys",
        listOf("gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol", "gpt-5.6"),
        defaultBaseUrl = "https://api.openai.com/v1"
    ),
    ANTHROPIC(
        "Anthropic Claude",
        "claude-sonnet-4-6",
        "https://console.anthropic.com/settings/keys",
        listOf("claude-haiku-4-5-20251001", "claude-sonnet-4-6", "claude-opus-4-6"),
        defaultBaseUrl = "https://api.anthropic.com/v1"
    ),
    OPENROUTER(
        "OpenRouter",
        "openai/gpt-5.6-luna",
        "https://openrouter.ai/settings/keys",
        listOf(
            "openai/gpt-5.6-luna",
            "google/gemini-3.6-flash",
            "anthropic/claude-sonnet-4.6"
        ),
        defaultBaseUrl = "https://openrouter.ai/api/v1"
    ),
    DEEPSEEK(
        "DeepSeek",
        "deepseek-flash",
        "https://platform.deepseek.com/api_keys",
        listOf(
            "deepseek-flash",
            "deepseek-v4-pro",
            "deepseek-chat",
            "deepseek-reasoner"
        ),
        defaultBaseUrl = "https://api.deepseek.com/v1",
        supportsVision = false
    ),
    MIMO(
        "小米 MiMo",
        "mimo-v2.6-flash",
        "https://platform.xiaomimimo.com",
        listOf("mimo-v2.6-flash", "mimo-v2.6-pro"),
        defaultBaseUrl = "https://api.xiaomimimo.com/v1"
    ),
    AGNES(
        "Agnes AI",
        "agnes-2.5-flash",
        "https://platform.agnes-ai.com",
        listOf(
            "agnes-2.5-flash",
            "agnes-3.0-flash",
            "agnes-image-2.5-flash"
        ),
        defaultBaseUrl = "https://apihub.agnes-ai.com/v1",
        // agnes-3.0-flash and agnes-2.5-flash accept image input; the
        // agnes-image-* models are generation-only and filtered out below.
        supportsVision = true
    ),
    OPENAI_COMPATIBLE(
        "OpenAI 兼容（自定义）",
        "",
        "",
        emptyList(),
        defaultBaseUrl = "",
        supportsVision = true
    );

    /**
     * Image generation models (for example agnes-image-2.5-flash) only speak
     * the /images/generations endpoint and cannot answer chat or OCR requests.
     */
    fun isImageGenerationModel(model: String): Boolean =
        model.startsWith("agnes-image") ||
            model.startsWith("dall-e") ||
            model.startsWith("gpt-image")

    /** Whether this provider and model pair accepts images as analysis input. */
    fun canAnalyzeImages(model: String = defaultModel): Boolean =
        supportsVision && !isImageGenerationModel(model)

    companion object {
        fun from(value: String): AiProvider =
            entries.firstOrNull { it.name == value } ?: GEMINI
    }
}

data class PersonalAiConfig(
    val credentialId: String,
    val provider: AiProvider,
    val apiKey: String,
    val model: String,
    val baseUrl: String = provider.defaultBaseUrl
)

data class AiApiProfile(
    val credentialId: String,
    val provider: AiProvider,
    val model: String,
    val maskedKey: String
)
