package com.example.data.api

import com.example.data.dictionary.DictionaryEntry
import com.example.data.importer.ExternalCardCandidate
import com.example.util.OcrCardCandidate
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object DirectAiService {
    private const val SYSTEM_PROMPT =
        "You are a careful English lexicographer for Simplified Chinese learners. " +
            "Return JSON only. Never invent a word or silently substitute another word."

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    data class AiUsageSnapshot(
        val label: String,
        val isLimited: Boolean = false
    )

    class AiQuotaException(message: String) : IllegalStateException(message)
    class AiHttpException(val code:Int,message:String):IllegalStateException(message)

    private val _usage = MutableStateFlow<Map<AiProvider, AiUsageSnapshot>>(emptyMap())
    val usage: StateFlow<Map<AiProvider, AiUsageSnapshot>> = _usage.asStateFlow()

    data class ImagePayload(
        val bytes: ByteArray,
        val mimeType: String
    )

    suspend fun fetchWordDetails(
        config: PersonalAiConfig,
        word: String,
        customInstructions: String = AiPromptDefaults.WORD_DETAILS
    ): DictionaryEntry? {
        val normalized = word.trim().take(100)
        if (normalized.isBlank()) return null
        val prompt = """
            ${customInstructions.trim().take(4_000)}

            Look up the exact English word "$normalized".
            Return one JSON object with exactly these string fields:
            word, phonetic, partOfSpeech, definition, exampleSentence, exampleTranslation.
            definition and exampleTranslation must be Simplified Chinese.
            exampleSentence must be a natural English sentence.
        """.trimIndent()
        val root = generateJson(config, prompt) as? JSONObject ?: return null
        val returnedWord = root.optString("word").trim()
        val definition = root.optString("definition").trim()
        val example = root.optString("exampleSentence").trim()
        val partOfSpeech = root.optString("partOfSpeech").trim()
        val translation = root.optString("exampleTranslation").trim()
        val phonetic = root.optString("phonetic").trim()
        if (
            !returnedWord.equals(normalized, ignoreCase = true) ||
            phonetic.isBlank() ||
            definition.isBlank() ||
            partOfSpeech.isBlank() ||
            example.isBlank() ||
            translation.isBlank()
        ) {
            return null
        }
        return DictionaryEntry(
            word = returnedWord,
            phonetic = phonetic,
            partOfSpeech = partOfSpeech,
            definition = definition,
            exampleSentence = example,
            exampleTranslation = translation,
            category = "AI 补充"
        )
    }

    suspend fun formatImportedCards(
        config: PersonalAiConfig,
        candidates: List<ExternalCardCandidate>,
        customInstructions: String = AiPromptDefaults.WORD_DETAILS
    ): Map<Int, DictionaryEntry> {
        val results = linkedMapOf<Int, DictionaryEntry>()
        candidates.withIndex().chunked(10).forEach batch@ { chunk ->
            val sourceCards = JSONArray().apply {
                chunk.forEach { indexed ->
                    put(
                        JSONObject()
                            .put("inputIndex", indexed.index)
                            .put("front", indexed.value.word.trim().take(500))
                            .put("back", indexed.value.definition.trim().take(3_000))
                    )
                }
            }
            val prompt = """
                ${customInstructions.trim().take(4_000)}

                Convert the following imported flashcards into this app's structured vocabulary format.
                The Quizlet front is the exact word and the Quizlet back is the authoritative source.
                Preserve the exact front word and all useful meanings from the back.
                Do not add, remove, merge, reorder, translate, or substitute input cards.
                If the back contains an English example and Simplified Chinese translation, extract them.
                If an example is missing, create one natural English example and its faithful Simplified Chinese translation.

                Return exactly one JSON object:
                {"cards":[{"inputIndex":0,"word":"","phonetic":"","partOfSpeech":"","definition":"","exampleSentence":"","exampleTranslation":""}]}

                Requirements:
                - one output for every inputIndex
                - word must exactly match its front
                - partOfSpeech and definition must not be blank
                - definition and exampleTranslation must be Simplified Chinese
                - exampleSentence must be English
                - do not include markdown or commentary

                INPUT_CARDS:
                $sourceCards
            """.trimIndent()
            val root = try {
                generateJson(config, prompt) as? JSONObject
            } catch (error: AiQuotaException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return@batch
            val items = root.optJSONArray("cards") ?: return@batch
            for (itemIndex in 0 until items.length()) {
                val item = items.optJSONObject(itemIndex) ?: continue
                val inputIndex = item.optInt("inputIndex", -1)
                val original = candidates.getOrNull(inputIndex) ?: continue
                val word = item.optString("word").trim()
                val partOfSpeech = item.optString("partOfSpeech").trim()
                val definition = item.optString("definition").trim()
                val exampleSentence = item.optString("exampleSentence").trim()
                val exampleTranslation = item.optString("exampleTranslation").trim()
                val hasChineseDefinition = definition.any { it.code in 0x3400..0x9FFF }
                val hasEnglishExample = exampleSentence.any { it in 'A'..'Z' || it in 'a'..'z' }
                val hasChineseTranslation = exampleTranslation.any { it.code in 0x3400..0x9FFF }
                if (
                    !word.equals(original.word.trim(), ignoreCase = false) ||
                    partOfSpeech.isBlank() ||
                    definition.isBlank() ||
                    exampleSentence.isBlank() ||
                    exampleTranslation.isBlank() ||
                    !hasChineseDefinition ||
                    !hasEnglishExample ||
                    !hasChineseTranslation
                ) {
                    continue
                }
                results[inputIndex] = DictionaryEntry(
                    word = word,
                    phonetic = item.optString("phonetic").trim(),
                    partOfSpeech = partOfSpeech,
                    definition = definition,
                    exampleSentence = exampleSentence,
                    exampleTranslation = exampleTranslation,
                    category = "AI 导入整理"
                )
            }
        }
        return results
    }

    suspend fun analyzeText(config: PersonalAiConfig, text: String): List<OcrCardCandidate> {
        val normalized = text.trim().take(20_000)
        if (normalized.isBlank()) return emptyList()
        val prompt = """
            Extract at most 80 useful English vocabulary words from the text below.
            Return a JSON array. Every element must contain string fields:
            word, phonetic, partOfSpeech, definition, exampleSentence.
            Use Simplified Chinese for definition and retain only real English words.

            TEXT:
            $normalized
        """.trimIndent()
        return (generateJson(config, prompt) as? JSONArray).toCandidates()
    }

    suspend fun analyzeImages(
        config: PersonalAiConfig,
        images: List<ImagePayload>,
        customInstructions: String = AiPromptDefaults.IMAGE_VOCABULARY_EXTRACTION,
        scopeInstructions:String=com.example.util.ScanInstructions.build()
    ): List<OcrCardCandidate> {
        val accepted = images
            .take(10)
            .filter {
                it.bytes.isNotEmpty() &&
                    it.bytes.size <= 8 * 1024 * 1024 &&
                    it.mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif")
            }
        require(accepted.isNotEmpty()) { "没有可传送给 AI 的图片" }

        val prompt = """
            ${customInstructions.trim().take(4_000)}

            本次范围指令优先：$scopeInstructions
            依照图片提供的原始顺序输出 cards。若图片没有可识别的英文单词，返回 {"cards":[]}。
        """.trimIndent()
        val root = generateJson(config, prompt, accepted) as? JSONObject
            ?: error("AI 没有返回正确的 JSON 对象")
        return root.optJSONArray("cards")
            .toCandidates()
            .filter { candidate ->
                candidate.word.matches(Regex("^[A-Za-z][A-Za-z' -]{1,99}$"))
            }
            .distinctBy { it.word.trim().lowercase() }
            .take(200)
    }

    suspend fun enrich(config: PersonalAiConfig, candidates: List<OcrCardCandidate>): List<OcrCardCandidate> {
        if (candidates.isEmpty()) return candidates
        val sourceCards = candidates
            .filter { it.word.isNotBlank() }
            .distinctBy { it.word.trim().lowercase() }
            .take(200)
        val enriched = linkedMapOf<String, OcrCardCandidate>()
        sourceCards.chunked(12).forEach { cardBatch ->
            val inputCards = JSONArray().apply {
                cardBatch.forEach { card ->
                    put(JSONObject()
                        .put("word", card.word.trim())
                        .put("phonetic", card.phonetic)
                        .put("partOfSpeech", card.partOfSpeech)
                        .put("definition", card.definition)
                        .put("exampleSentence", card.exampleSentence)
                        .put("exampleTranslation", card.exampleTranslation))
                }
            }
            val prompt = """
                Complete only the missing fields in these English flashcards:
                $inputCards

                Return exactly one JSON object:
                {"cards":[{"word":"","phonetic":"","partOfSpeech":"","definition":"","exampleSentence":"","exampleTranslation":""}]}

                Requirements:
                - Return at most one card for each input word and no other words.
                - Preserve the exact input word and every non-empty field verbatim.
                - definition and exampleTranslation use Simplified Chinese.
                - If exampleSentence is already non-empty and exampleTranslation is empty, translate that exact English sentence faithfully. Never invent a different Chinese sentence and never replace the English sentence.
                - Only create a new natural exampleSentence when the input exampleSentence is empty; its exampleTranslation must translate that newly created sentence exactly.
                - phonetic is required and must use reliable American IPA notation wrapped in /.../.
                - Never substitute another word.
                - Do not include Markdown or commentary.
            """.trimIndent()
            val payload = generateJson(config, prompt)
            val items = when (payload) {
                is JSONObject -> payload.optJSONArray("cards")
                is JSONArray -> payload
                else -> null
            }
            val requested = cardBatch.mapTo(hashSetOf()) { it.word.trim() }
            items.toCandidates()
                .filter { it.word in requested }
                .forEach { enriched[it.word.lowercase()] = it }
        }
        return candidates.map { original ->
            val item = enriched[original.word.trim().lowercase()] ?: return@map original
            mergeAiCompletion(original.copy(definition = original.definition.takeUnless { it.contains("待补充") }.orEmpty()), item)
        }
    }

    suspend fun listModels(config: PersonalAiConfig): List<String> = withContext(Dispatchers.IO) {
        if (config.provider == AiProvider.OPENAI_COMPATIBLE) {
            require(apiBase(config).isNotBlank()) { "请先填写 API Base URL" }
        }
        val request = when (config.provider) {
            AiProvider.GEMINI -> Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=${config.apiKey}")
                .get().build()
            AiProvider.OPENAI -> Request.Builder()
                .url("https://api.openai.com/v1/models")
                .header("Authorization", "Bearer ${config.apiKey}").get().build()
            AiProvider.ANTHROPIC -> Request.Builder()
                .url("https://api.anthropic.com/v1/models")
                .header("x-api-key", config.apiKey)
                .header("anthropic-version", "2023-06-01").get().build()
            AiProvider.OPENROUTER -> Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .header("Authorization", "Bearer ${config.apiKey}").get().build()
            AiProvider.DEEPSEEK, AiProvider.MIMO, AiProvider.AGNES,
            AiProvider.OPENAI_COMPATIBLE -> Request.Builder()
                .url("${apiBase(config)}/models")
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("api-key", config.apiKey)
                .get().build()
        }
        execute(request, config.provider).let { body ->
            val root = JSONObject(body)
            when (config.provider) {
                AiProvider.GEMINI -> root.optJSONArray("models").strings("name")
                    .map { it.removePrefix("models/") }
                else -> root.optJSONArray("data").strings("id")
            }
                .filterNot { it.contains("embedding", true) || it.contains("image", true) }
                .distinct()
                .sorted()
                .take(100)
        }
    }

    suspend fun test(config: PersonalAiConfig): Result<Unit> = runCatching {
        check(fetchWordDetails(config, "apple") != null) {
            "API 已回应，但选定模型没有返回合格的词典数据"
        }
        if (config.provider.supportsVision) {
            analyzeImages(
                config = config,
                images = listOf(
                    ImagePayload(
                        bytes = Base64.decode(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
                            Base64.DEFAULT
                        ),
                        mimeType = "image/png"
                    )
                ),
                customInstructions = "这是多模态连接测试。图片若没有英文单词，必须返回 {\"cards\":[]}。"
            )
        }
    }

    suspend fun refreshUsage(config: PersonalAiConfig): AiUsageSnapshot =
        withContext(Dispatchers.IO) {
            if (config.provider != AiProvider.OPENROUTER) {
                return@withContext setUsage(
                    config.provider,
                    AiUsageSnapshot(
                        when (config.provider) {
                            AiProvider.GEMINI -> "Google 未提供 Key 剩余总额；请至 AI Studio 查看"
                            AiProvider.OPENAI -> "OpenAI 未提供 Key 剩余总额；请至用量页查看"
                            AiProvider.ANTHROPIC -> "Anthropic 未提供 Key 剩余总额；请至 Console 查看"
                            AiProvider.OPENROUTER -> error("unreachable")
                            else -> "${config.provider.displayName} 未提供 Key 余额查询；请至控制台查看"
                        }
                    )
                )
            }
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/credits")
                .header("Authorization", "Bearer ${config.apiKey}")
                .get()
                .build()
            runCatching {
                val root = JSONObject(execute(request, config.provider))
                val data = root.getJSONObject("data")
                val remaining = (
                    data.optDouble("total_credits", 0.0) -
                        data.optDouble("total_usage", 0.0)
                    ).coerceAtLeast(0.0)
                setUsage(
                    config.provider,
                    AiUsageSnapshot(
                        label = "剩余 US$ %.4f".format(remaining),
                        isLimited = remaining <= 0.0
                    )
                )
            }.getOrElse {
                setUsage(
                    config.provider,
                    AiUsageSnapshot("此 Key 无法直接查询余额；请至 OpenRouter 查看")
                )
            }
        }

    suspend fun generateAssistantJson(
        config: PersonalAiConfig,
        prompt: String,
        images: List<ImagePayload> = emptyList()
    ): JSONObject {
        require(prompt.isNotBlank()) { "AI 助手指令不能空白" }
        val boundedPrompt = if (prompt.length <= 60_000) {
            prompt
        } else {
            buildString(60_000) {
                append(prompt.take(38_000))
                append("\n\n［中间过长的 App 卡片内容已省略］\n\n")
                append(prompt.takeLast(21_900))
            }
        }
        return generateJson(config, boundedPrompt, images.take(5)) as? JSONObject
            ?: error("AI 助手没有返回正确的 JSON 对象")
    }

    private suspend fun generateJson(
        config: PersonalAiConfig,
        userPrompt: String,
        images: List<ImagePayload> = emptyList()
    ): Any? =
        withContext(Dispatchers.IO) {
            val response = when (config.provider) {
                AiProvider.GEMINI -> gemini(config, userPrompt, images)
                AiProvider.OPENAI -> openAi(config, userPrompt, images)
                AiProvider.ANTHROPIC -> anthropic(config, userPrompt, images)
                AiProvider.OPENROUTER -> openRouter(config, userPrompt, images)
                AiProvider.DEEPSEEK, AiProvider.MIMO, AiProvider.AGNES,
                AiProvider.OPENAI_COMPATIBLE ->
                    openAiCompatible(config, userPrompt, images)
            }
            parseJsonPayload(response)
        }

    private suspend fun gemini(
        config: PersonalAiConfig,
        prompt: String,
        images: List<ImagePayload>
    ): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        images.forEach { image ->
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", image.mimeType)
                        .put("data", image.base64())
                )
            )
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
            ))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("temperature", 0.2)
                    .put("maxOutputTokens", 4096)
            )
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent")
            .header("x-goog-api-key",config.apiKey)
            .post(jsonBody(body))
            .build()
        val root = JSONObject(execute(request, config.provider))
        return root.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

    private suspend fun openAi(
        config: PersonalAiConfig,
        prompt: String,
        images: List<ImagePayload>
    ): String {
        val userContent = JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", prompt))
        images.forEach { image ->
            userContent.put(
                JSONObject()
                    .put("type", "input_image")
                    .put("detail", "high")
                    .put("image_url", image.dataUrl())
            )
        }
        val body = JSONObject()
            .put("model", config.model)
            .put("store", false)
            .put("max_output_tokens", 4096)
            .put("input", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(JSONObject().put("role", "user").put("content", userContent)))
        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(jsonBody(body)).build()
        val root = JSONObject(execute(request, config.provider))
        root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = root.getJSONArray("output")
        for (i in 0 until output.length()) {
            val content = output.getJSONObject(i).optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                content.getJSONObject(j).optString("text").takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        error("OpenAI 未返回文字")
    }

    private suspend fun anthropic(
        config: PersonalAiConfig,
        prompt: String,
        images: List<ImagePayload>
    ): String {
        val content = JSONArray()
        images.forEach { image ->
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", image.mimeType)
                            .put("data", image.base64())
                    )
            )
        }
        content.put(JSONObject().put("type", "text").put("text", prompt))
        val body = JSONObject()
            .put("model", config.model)
            .put("max_tokens", 4096)
            .put("system", SYSTEM_PROMPT)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", content)
            ))
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(jsonBody(body)).build()
        return JSONObject(execute(request, config.provider)).getJSONArray("content")
            .getJSONObject(0).getString("text")
    }

    private suspend fun openRouter(
        config: PersonalAiConfig,
        prompt: String,
        images: List<ImagePayload>
    ): String {
        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
        images.forEach { image ->
            userContent.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", image.dataUrl()))
            )
        }
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(JSONObject().put("role", "user").put("content", userContent)))
            .put("temperature", 0.2)
            .put("max_tokens", 4096)
            .put("response_format", JSONObject().put("type", "json_object"))
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("X-Title", "Vocab")
            .post(jsonBody(body)).build()
        return JSONObject(execute(request, config.provider)).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }

    private fun apiBase(config: PersonalAiConfig): String =
        config.baseUrl.ifBlank { config.provider.defaultBaseUrl }.trimEnd('/')

    /**
     * OpenAI Chat Completions compatible path used by DeepSeek, Xiaomi MiMo,
     * Agnes AI and any user-supplied endpoint.
     */
    private suspend fun openAiCompatible(
        config: PersonalAiConfig,
        prompt: String,
        images: List<ImagePayload>
    ): String {
        require(config.model.isNotBlank()) { "请先设置模型 ID" }
        val base = apiBase(config)
        require(base.isNotBlank()) { "请先填写 API Base URL" }
        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
        if (images.isNotEmpty()) {
            require(config.provider.supportsVision) {
                "${config.provider.displayName} 的 ${config.model} 不支持图片输入；请改用文字查询或换用支持视觉的模型"
            }
            images.forEach { image ->
                userContent.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", image.dataUrl()))
                )
            }
        }
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(JSONObject().put("role", "user").put("content", userContent)))
            .put("temperature", 0.2)
            .put("max_tokens", 4096)
        val request = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            // MiMo authenticates with the api-key header; harmless elsewhere.
            .header("api-key", config.apiKey)
            .header("X-Title", "Vocab")
            .post(jsonBody(body)).build()
        val root = JSONObject(execute(request, config.provider))
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: error("${config.provider.displayName} 返回内容缺少 choices")
        val text = when (val content = message.opt("content")) {
            is String -> content
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    content.optJSONObject(index)
                        ?.optString("text")
                        ?.takeIf(String::isNotBlank)
                        ?.let { append(it) }
                }
            }
            else -> ""
        }
        return text.takeIf(String::isNotBlank)
            ?: error("${config.provider.displayName} 没有返回文字内容")
    }

    private suspend fun execute(
        request: Request,
        provider: AiProvider
    ): String = suspendCancellableCoroutine { continuation ->
        val call=client.newCall(request)
        continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:okhttp3.Callback{
            override fun onFailure(call:okhttp3.Call,error:java.io.IOException){if(continuation.isActive)continuation.resumeWith(Result.failure(error))}
            override fun onResponse(call:okhttp3.Call,response:okhttp3.Response){
                val result=runCatching{response.use{parseResponse(it,provider)}}
                if(continuation.isActive)continuation.resumeWith(result)
            }
        })
    }
    private fun parseResponse(response:okhttp3.Response,provider:AiProvider):String {
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val detail = runCatching {
                val error = JSONObject(body).opt("error")
                when (error) {
                    is JSONObject -> error.optString("message")
                    else -> error?.toString().orEmpty()
                }
            }.getOrDefault("")
            val message = detail.ifBlank { "AI API 连接失败（HTTP ${response.code}）" }
            if (response.code == 402 || response.code == 429) {
                setUsage(
                    provider,
                    AiUsageSnapshot("已达额度或速率限制，将切换下一个 API", true)
                )
                throw AiQuotaException(message)
            }
            throw AiHttpException(response.code,message)
        }
        updateRateLimitUsage(provider, response.headers)
        if (body.isBlank()) {
            error("AI API 返回空白内容，请确认所选模型可用并且帐户仍有额度")
        }
        return body
    }

    private fun updateRateLimitUsage(provider: AiProvider, headers: okhttp3.Headers) {
        val label = when (provider) {
            AiProvider.OPENAI -> {
                val requests = headers["x-ratelimit-remaining-requests"]
                val tokens = headers["x-ratelimit-remaining-tokens"]
                if (requests != null || tokens != null) {
                    "本时段剩余：${requests ?: "—"} 次请求 · ${tokens ?: "—"} tokens"
                } else null
            }
            AiProvider.ANTHROPIC -> {
                val requests = headers["anthropic-ratelimit-requests-remaining"]
                val input = headers["anthropic-ratelimit-input-tokens-remaining"]
                val output = headers["anthropic-ratelimit-output-tokens-remaining"]
                if (requests != null || input != null || output != null) {
                    "本时段剩余：${requests ?: "—"} 次 · 输入 ${input ?: "—"} · 输出 ${output ?: "—"}"
                } else null
            }
            else -> null
        }
        if (label != null) setUsage(provider, AiUsageSnapshot(label))
    }

    private fun setUsage(
        provider: AiProvider,
        snapshot: AiUsageSnapshot
    ): AiUsageSnapshot {
        _usage.value = _usage.value + (provider to snapshot)
        return snapshot
    }

    private fun jsonBody(value: JSONObject) =
        value.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun ImagePayload.base64(): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun ImagePayload.dataUrl(): String =
        "data:$mimeType;base64,${base64()}"

    private fun parseJsonPayload(raw: String): Any? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val objectStart = cleaned.indexOf('{')
        val arrayStart = cleaned.indexOf('[')
        val start = when {
            arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart) -> arrayStart
            objectStart >= 0 -> objectStart
            else -> throw IllegalArgumentException("AI 没有返回 JSON 格式数据")
        }
        val completeJson = findCompleteJson(cleaned, start)
            ?: throw IllegalArgumentException(
                "AI 返回内容不完整，可能超过模型输出上限；请缩小批次或更换模型后再试"
            )
        return try {
            if (completeJson.first() == '[') JSONArray(completeJson)
            else JSONObject(completeJson)
        } catch (_: JSONException) {
            throw IllegalArgumentException("AI 返回的 JSON 格式错误，未应用任何数据")
        }
    }

    internal fun findCompleteJson(text: String, start: Int): String? {
        val opening = text.getOrNull(start) ?: return null
        val closing = if (opening == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                opening -> depth++
                closing -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun JSONArray?.strings(key: String): List<String> = buildList {
        val array = this@strings ?: return@buildList
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.optString(key)?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun JSONArray?.toCandidates(): List<OcrCardCandidate> = buildList {
        val array = this@toCandidates ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val word = item.optString("word").trim()
            if (word.isBlank()) continue
            add(OcrCardCandidate(
                word = word,
                phonetic = item.optString("phonetic").trim(),
                // Missing AI data stays missing. Defaulting every unknown word to
                // noun would present guessed information as verified content.
                partOfSpeech = item.optString("partOfSpeech").trim(),
                definition = item.optString("definition").trim(),
                exampleSentence = item.optString("exampleSentence").trim(),
                exampleTranslation = item.optString("exampleTranslation").trim(),
                isSelected = true
            ))
        }
    }
}
