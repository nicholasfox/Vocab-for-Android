package com.example.data.dictionary

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** On-demand lookup of the public Cambridge English–Simplified Chinese entry page. */
object CambridgeDictionaryLookupService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun lookup(rawWord: String): DictionaryEntry = withContext(Dispatchers.IO) {
        val word = rawWord.trim().lowercase()
        require(word.matches(Regex("[a-z][a-z' -]{0,79}"))) { "剑桥辞典仅支持英文单词或短语" }
        val slug = URLEncoder.encode(word, "UTF-8").replace("+", "%20")
        val request = Request.Builder()
            .url("https://dictionary.cambridge.org/dictionary/english-chinese-simplified/$slug")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Vocab/2.8.0")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                if (response.code == 404) "剑桥辞典找不到这个单词" else "剑桥辞典暂时无法查询（${response.code}）"
            }
            parse(word, response.body?.string().orEmpty())
        }
    }

    internal fun parse(requestedWord: String, html: String): DictionaryEntry {
        fun first(vararg patterns: String): String = patterns.firstNotNullOfOrNull { pattern ->
            Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.getOrNull(1)?.let(::clean)?.takeIf(String::isNotBlank)
        }.orEmpty()
        fun all(pattern: String, limit: Int = 6): List<String> =
            Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(html).mapNotNull { clean(it.groupValues.getOrNull(1).orEmpty()).takeIf(String::isNotBlank) }
                .distinct().take(limit).toList()

        val headword = first("class=\"[^\"]*\\bhw\\b[^\"]*\"[^>]*>(.*?)</")
        val phonetic = first("class=\"[^\"]*\\bdipa\\b[^\"]*\"[^>]*>(.*?)</", "class=\"[^\"]*\\bipa\\b[^\"]*\"[^>]*>(.*?)</")
        val parts = all("class=\"[^\"]*\\bdpos\\b[^\"]*\"[^>]*>(.*?)</", 4)
        val translations = all("class=\"[^\"]*\\bdtrans\\b[^\"]*\"[^>]*>(.*?)</", 8)
        val englishDefinitions = all("class=\"[^\"]*\\bdef\\b[^\"]*\"[^>]*>(.*?)</", 5)
        val example = first("class=\"[^\"]*\\beg\\b[^\"]*\"[^>]*>(.*?)</")
        val definition = translations.ifEmpty { englishDefinitions }.joinToString("；")
        check(definition.isNotBlank()) { "剑桥辞典没有可显示的解释" }
        return DictionaryEntry(
            word = headword.ifBlank { requestedWord },
            phonetic = phonetic.takeIf(String::isNotBlank)?.let { "/${it.trim('/')} /".replace("/ ", "/") }.orEmpty(),
            partOfSpeech = parts.joinToString("/"),
            definition = definition,
            exampleSentence = example,
            exampleTranslation = "",
            category = "Cambridge Dictionary"
        )
    }

    private fun clean(value: String): String = Html.fromHtml(
        value.replace(Regex("<[^>]+>"), " "), Html.FROM_HTML_MODE_LEGACY
    ).toString().replace(Regex("\\s+"), " ").trim()
}
