package com.example.data.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class ImportCapability {
    DIRECT_TEXT,
    ANKI_PACKAGE,
    PUBLIC_SHARE_PAGE,
    EXPORT_AND_PASTE,
    OFFICIAL_SEARCH_ONLY
}

data class ExternalVocabularySource(
    val id: String,
    val name: String,
    val hosts: List<String>,
    val searchUrl: String,
    val capability: ImportCapability,
    val guidance: String
) {
    fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        return searchUrl.replace("{query}", encoded)
    }
}

data class ExternalCardCandidate(
    val word: String,
    val definition: String,
    val exampleSentence: String = "",
    val phonetic: String = "",
    val partOfSpeech: String = "",
    val exampleTranslation: String = "",
    val sourceName: String = "",
    val selected: Boolean = true,
    val aiFormatted: Boolean = false
)

data class ParsedExternalDeck(
    val title: String,
    val sourceName: String,
    val cards: List<ExternalCardCandidate>
)

data class ExternalSearchResult(
    val title: String,
    val url: String,
    val description: String = "",
    val sourceName: String
)

data class AiExternalFormattingResult(
    val cards: List<ExternalCardCandidate>,
    val formattedCount: Int,
    val fallbackCount: Int,
    val message: String = ""
)

object ExternalDeckImporter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val sources = listOf(
        ExternalVocabularySource(
            "anki", "Anki / AnkiWeb", listOf("ankiweb.net"),
            "https://ankiweb.net/shared/decks?search={query}",
            ImportCapability.ANKI_PACKAGE,
            "可搜索公开牌组；从官方页面下载 .apkg 后可直接导入。AnkiWeb 下载需要浏览器签章，分享页不能由第三方 App 偷抓。"
        ),
        ExternalVocabularySource(
            "quizlet", "Quizlet", listOf("quizlet.com"),
            "https://quizlet.com/search?query={query}&type=sets",
            ImportCapability.PUBLIC_SHARE_PAGE,
            "可贴上公开学习集网址尝试读取。若 Quizlet 要求人机验证，App 会如实显示，请改用平台导出文字。"
        ),
        ExternalVocabularySource(
            "kahoot", "Kahoot!", listOf("kahoot.com", "create.kahoot.it", "kahoot.it"),
            "https://create.kahoot.it/discover?query={query}",
            ImportCapability.PUBLIC_SHARE_PAGE,
            "可搜索公开 Kahoot；只有公开页实际提供题目与正确答案时才会导入。"
        ),
        source("wordup", "WORD UP", listOf("wordup.com.tw"), "https://www.google.com/search?q=site%3Awordup.com.tw+{query}"),
        source("lingvist", "Lingvist", listOf("lingvist.com", "lingvist.io"), "https://lingvist.com/search/?q={query}"),
        source("vocabulazy", "Vocabulazy", listOf("vocabulazy.com"), "https://www.google.com/search?q=site%3Avocabulazy.com+{query}"),
        source("super_word_king", "超级单词王", emptyList(), "https://www.google.com/search?q=%E8%B6%85%E7%B4%9A%E5%96%AE%E5%AD%97%E7%8E%8B+{query}"),
        source("e4f", "E4F 字根字首字尾字典", listOf("e4f.com.tw"), "https://www.google.com/search?q=site%3Ae4f.com.tw+{query}"),
        source("english_word_king", "英文单词王", emptyList(), "https://www.google.com/search?q=%E8%8B%B1%E6%96%87%E5%96%AE%E5%AD%97%E7%8E%8B+{query}"),
        source("toast_toeic", "英文知识王－吐司多益英文", emptyList(), "https://www.google.com/search?q=%E5%90%90%E5%8F%B8%E5%A4%9A%E7%9B%8A%E8%8B%B1%E6%96%87+{query}"),
        source("word_chakra", "单词查克拉", emptyList(), "https://www.google.com/search?q=%E5%96%AE%E5%AD%97%E6%9F%A5%E5%85%8B%E6%8B%89+{query}"),
        source("cool_words", "英文单词酷", emptyList(), "https://www.google.com/search?q=%E8%8B%B1%E6%96%87%E5%96%AE%E5%AD%97%E9%85%B7+{query}"),
        source("word_tree", "单词树", emptyList(), "https://www.google.com/search?q=%E5%96%AE%E8%A9%9E%E6%A8%B9+{query}"),
        source("duolingo", "Duolingo 多邻国", listOf("duolingo.com"), "https://www.google.com/search?q=site%3Aduolingo.com+{query}"),
        source("voicetube", "VoiceTube", listOf("voicetube.com"), "https://tw.voicetube.com/search?query={query}"),
        source("death_words", "死神单词", emptyList(), "https://www.google.com/search?q=%E6%AD%BB%E7%A5%9E%E5%96%AE%E5%AD%97+{query}"),
        source("chacha", "查查单词", emptyList(), "https://www.google.com/search?q=%E6%9F%A5%E6%9F%A5%E5%96%AE%E5%AD%97+{query}"),
        ExternalVocabularySource(
            "brainscape", "Brainscape", listOf("brainscape.com"),
            "https://www.brainscape.com/subjects/{query}",
            ImportCapability.PUBLIC_SHARE_PAGE,
            "可贴上公开 Flashcards 卡片页网址，从网页公开的题目与答案数据导入。"
        ),
        ExternalVocabularySource(
            "knowt", "Knowt", listOf("knowt.com"),
            "https://knowt.com/search?search={query}",
            ImportCapability.PUBLIC_SHARE_PAGE,
            "可贴上公开 Flashcards 分享网址，从页面公开数据读取单词与解释。"
        ),
        ExternalVocabularySource(
            "cram", "Cram", listOf("cram.com"),
            "https://www.cram.com/search?query={query}",
            ImportCapability.PUBLIC_SHARE_PAGE,
            "可贴上公开 Flashcards 分享网址，从页面实际显示的 Front／Back 导入。"
        ),
        ExternalVocabularySource(
            "studysmarter", "StudySmarter", listOf("studysmarter.co.uk", "studysmarter.de"),
            "https://www.studysmarter.co.uk/search/?query={query}",
            ImportCapability.PUBLIC_SHARE_PAGE,
            "可搜索并读取公开 Flashcards 页；需要登录或没有公开正反面的内容不会导入。"
        ),
        source("memrise", "Memrise", listOf("memrise.com"), "https://www.google.com/search?q=site%3Amemrise.com+{query}"),
        source("remnote", "RemNote", listOf("remnote.com"), "https://www.google.com/search?q=site%3Aremnote.com+{query}"),
        source("mochi", "Mochi Cards", listOf("mochi.cards"), "https://www.google.com/search?q=site%3Amochi.cards+{query}")
    )

    private fun source(id: String, name: String, hosts: List<String>, searchUrl: String) =
        ExternalVocabularySource(
            id, name, hosts, searchUrl, ImportCapability.OFFICIAL_SEARCH_ONLY,
            "此平台目前没有公开且稳定的第三方牌组 API。可打开官方搜索，若平台提供 CSV／文字导出，再回到本页导入。"
        )

    fun detectSource(url: String): ExternalVocabularySource? {
        val host = runCatching { Uri.parse(url.trim()).host?.lowercase().orEmpty() }.getOrDefault("")
        return sources.firstOrNull { source ->
            source.hosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }
        }
    }

    fun isDirectTextUrl(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return clean.endsWith(".csv") || clean.endsWith(".tsv") ||
            clean.endsWith(".txt") || clean.endsWith(".json")
    }

    fun isDirectAnkiPackageUrl(url: String): Boolean =
        url.substringBefore('?').lowercase().endsWith(".apkg")

    suspend fun fetchDirectText(url: String): String = withContext(Dispatchers.IO) {
        val uri = requireSafeHttpsUrl(url)
        val request = Request.Builder()
            .url(uri.toString())
            .header("User-Agent", "Vocab/1.1")
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "下载失败（HTTP ${response.code}）" }
            val body = response.body ?: error("下载内容为空")
            require((body.contentLength().takeIf { it >= 0 } ?: 0) <= 5_000_000) { "文件超过 5 MB" }
            body.string().take(5_000_000)
        }
    }

    suspend fun fetchSharedDeck(context: Context, url: String): ParsedExternalDeck {
        requireSafeHttpsUrl(url)
        val detected = detectSource(url)
        if (detected?.id == "quizlet") {
            return QuizletWebViewImporter.fetch(context, url)
        }
        if (detected != null && detected.hosts.isNotEmpty()) {
            return PublicDeckWebViewImporter.fetch(context, detected, url)
        }
        return withContext(Dispatchers.IO) {
        val endpoint = "${BuildConfig.BACKEND_BASE_URL.trimEnd('/')}/api/v1/import/url"
        val payload = JSONObject().put("url", url.trim())
        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(responseText).optString("error")
                }.getOrNull().orEmpty().ifBlank {
                    "分享页读取失败（HTTP ${response.code}）"
                }
                error(message)
            }
            val data = JSONObject(responseText).optJSONObject("data")
                ?: error("分享页服务没有返回数据")
            val sourceName = data.optString("sourceName").trim().ifBlank { "公开分享页" }
            val title = data.optString("title").trim().ifBlank { sourceName }
            val items = data.optJSONArray("cards") ?: JSONArray()
            val cards = buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val word = cleanCell(item.optString("word"))
                    val definition = cleanCell(item.optString("definition"))
                    val example = cleanCell(item.optString("exampleSentence"))
                    if (word.isNotBlank() && definition.isNotBlank()) {
                        add(
                            ExternalCardCandidate(
                                word = word,
                                definition = definition,
                                exampleSentence = example,
                                sourceName = sourceName
                            )
                        )
                    }
                }
            }.distinctBy { "${it.word.lowercase()}\u0000${it.definition.lowercase()}" }
            require(cards.isNotEmpty()) { "分享页里没有可导入的单词数据" }
            ParsedExternalDeck(title, sourceName, cards.take(5_000))
        }
    }
    }

    suspend fun searchPublicDecks(
        context: Context,
        source: ExternalVocabularySource,
        query: String
    ): List<ExternalSearchResult> {
        require(query.isNotBlank()) { "请输入搜索关键字" }
        return PublicDeckWebViewImporter.search(context, source, query.trim())
    }

    suspend fun parseUri(context: Context, uri: Uri): ParsedExternalDeck = withContext(Dispatchers.IO) {
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            .orEmpty()
        if (displayName.lowercase().endsWith(".apkg")) {
            return@withContext AnkiPackageParser.parse(context, uri)
        }
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("无法读取文件")
        parseText(text, displayName.ifBlank { "导入文件" })
    }

    fun parseText(text: String, title: String = "贴上的单词集"): ParsedExternalDeck {
        val trimmed = text.trim().removePrefix("\uFEFF")
        require(trimmed.isNotBlank()) { "没有可导入的内容" }
        val cards = if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            parseJson(trimmed)
        } else {
            parseDelimited(trimmed)
        }
        require(cards.isNotEmpty()) { "找不到「单词＋解释」数据，请确认每行至少有两栏" }
        return ParsedExternalDeck(title, "文字／CSV", cards.take(5_000))
    }

    private fun parseJson(text: String): List<ExternalCardCandidate> {
        val root = if (text.startsWith("[")) JSONArray(text) else {
            val objectRoot = JSONObject(text)
            objectRoot.optJSONArray("cards")
                ?: objectRoot.optJSONArray("terms")
                ?: objectRoot.optJSONArray("items")
                ?: JSONArray().put(objectRoot)
        }
        return buildList {
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                val word = firstValue(item, "word", "term", "front", "question")
                val definition = firstValue(item, "definition", "meaning", "back", "answer")
                val example = firstValue(item, "exampleSentence", "example", "sentence")
                if (word.isNotBlank() && definition.isNotBlank()) {
                    add(ExternalCardCandidate(cleanCell(word), cleanCell(definition), cleanCell(example)))
                }
            }
        }
    }

    private fun firstValue(json: JSONObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }.orEmpty()

    private fun parseDelimited(text: String): List<ExternalCardCandidate> {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        if (lines.isEmpty()) return emptyList()
        val delimiter = listOf('\t', ';', ',')
            .maxByOrNull { delimiter -> lines.take(10).count { it.contains(delimiter) } }
            ?.takeIf { candidate -> lines.take(10).any { it.contains(candidate) } }
        return lines.mapNotNull { line ->
            val cells = if (delimiter == null) {
                line.split(Regex("\\s{2,}|\\s+-\\s+"), limit = 3)
            } else {
                splitCsvLine(line, delimiter)
            }.map(::cleanCell)
            if (cells.size < 2 || cells[0].isBlank() || cells[1].isBlank()) null
            else ExternalCardCandidate(
                word = cells[0],
                definition = cells[1],
                exampleSentence = cells.getOrNull(2).orEmpty()
            )
        }.dropHeaderIfNeeded()
    }

    private fun List<ExternalCardCandidate>.dropHeaderIfNeeded(): List<ExternalCardCandidate> {
        val first = firstOrNull() ?: return this
        val headerWords = setOf("word", "term", "front", "question", "单词", "题目")
        return if (first.word.trim().lowercase() in headerWords) drop(1) else this
    }

    private fun splitCsvLine(line: String, delimiter: Char): List<String> {
        val values = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> {
                    values += cell.toString()
                    cell.clear()
                }
                else -> cell.append(char)
            }
            index++
        }
        values += cell.toString()
        return values
    }

    internal fun cleanCell(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('"')

    private fun requireSafeHttpsUrl(value: String): Uri {
        val uri = Uri.parse(value.trim())
        require(uri.scheme.equals("https", ignoreCase = true)) { "只允许 HTTPS 网址" }
        val host = uri.host?.lowercase().orEmpty()
        require(host.isNotBlank()) { "网址格式错误" }
        require(
            host != "localhost" &&
                host != "127.0.0.1" &&
                host != "::1" &&
                !host.endsWith(".local") &&
                !Regex("""^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)""").containsMatchIn(host)
        ) { "不允许连接到本机或私人网络网址" }
        return uri
    }
}
