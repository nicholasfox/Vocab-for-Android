package com.example.ui.screens
import com.example.ui.components.ModernListRow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import com.example.ui.components.ModernButton as Button
import com.example.ui.components.ModernCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.entity.Deck
import com.example.data.entity.Flashcard

private data class QualityFinding(
    val title: String,
    val detail: String,
    val card: Flashcard? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyQualityScreen(
    cards: List<Flashcard>,
    decks: List<Deck>,
    onEditCard: (Flashcard) -> Unit,
    onRunAiAudit: () -> Unit,
    aiRunning: Boolean = false,
    aiStatus: String? = null,
    aiReport: String? = null,
    onBack: () -> Unit
) {
    val deckNames = remember(decks) { decks.associate { it.id to it.name } }
    val findings = remember(cards, deckNames) { buildQualityFindings(cards, deckNames) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("单词数据质量", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(top = 6.dp).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("已检查 ${cards.size} 张单词卡", fontWeight = FontWeight.Bold)
                        Text(
                            if (findings.isEmpty()) "没有发现可由本机规则确认的问题。"
                            else "找到 ${findings.size} 个需要确认的项目。",
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Button(onClick = onRunAiAudit, modifier = Modifier.fillMaxWidth(), enabled = cards.isNotEmpty() && !aiRunning) {
                            if (aiRunning) CircularProgressIndicator(modifier = Modifier.padding(3.dp))
                            else Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Text(if (aiRunning) "检查中…" else "用 AI 检查解释与疑似拼字")
                        }
                    }
                }
            }
            item {
                Text(
                    "本机会检查重复、字段缺漏与词性格式；中文解释正确性和拼字建议必须由 AI 分析并由你确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (aiRunning || !aiReport.isNullOrBlank()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("AI 检查结果", fontWeight = FontWeight.Bold)
                            if (aiRunning) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator()
                                    Text(aiStatus ?: "AI 正在检查单词数据…")
                                }
                            } else Text(aiReport.orEmpty())
                        }
                    }
                }
            }
            if (findings.isEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("目前数据完整", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                items(findings) { finding ->
                    ModernListRow(finding.title, finding.detail, Icons.Default.ErrorOutline, { finding.card?.let(onEditCard) }, trailing = { finding.card?.let { card -> IconButton(onClick = { onEditCard(card) }) { Icon(Icons.Default.Edit, "编辑 ${card.word}") } } })
                }
            }
        }
    }
}

private fun buildQualityFindings(cards: List<Flashcard>, deckNames: Map<Long, String>): List<QualityFinding> {
    val findings = mutableListOf<QualityFinding>()
    cards.groupBy { it.normalizedWord }.filter { (word, group) -> word.isNotBlank() && group.size > 1 }
        .forEach { (_, group) ->
            findings += QualityFinding(
                title = "重复单词：${group.first().word}",
                detail = group.joinToString("、") { deckNames[it.deckId] ?: "未知文件夹" },
                card = group.first()
            )
        }
    val acceptedPos = Regex("(?i)^(n|v|vt|vi|adj|adv|prep|pron|conj|interj|det|aux|num|phr|abbr)\\.?([/、, ]+(n|v|vt|vi|adj|adv|prep|pron|conj|interj|det|aux|num|phr|abbr)\\.?)*(\\s*\\[[^]]+])?$")
    cards.forEach { card ->
        val missing = buildList {
            if (card.phonetic.isBlank()) add("音标")
            if (card.partOfSpeech.isBlank()) add("词性")
            if (card.definition.isBlank()) add("中文解释")
            if (card.exampleSentence.isBlank()) add("英文例句")
            if (card.exampleTranslation.isBlank()) add("例句翻译")
        }
        if (missing.isNotEmpty()) {
            findings += QualityFinding("数据不完整：${card.word}", "缺少${missing.joinToString("、")}", card)
        } else if (!acceptedPos.matches(card.partOfSpeech.trim())) {
            findings += QualityFinding("词性格式需确认：${card.word}", "目前内容：${card.partOfSpeech}", card)
        }
    }
    return findings
}
