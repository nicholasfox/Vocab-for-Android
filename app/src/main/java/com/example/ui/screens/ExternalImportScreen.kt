package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import com.example.ui.components.ModernButton as Button
import com.example.ui.components.ModernCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import com.example.ui.components.ModernOutlinedButton as OutlinedButton
import com.example.ui.components.ModernTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.entity.Deck
import com.example.data.importer.AiExternalFormattingResult
import com.example.data.importer.ExternalCardCandidate
import com.example.data.importer.ExternalDeckImporter
import com.example.data.importer.ExternalVocabularySource
import com.example.data.importer.ExternalSearchResult
import com.example.data.importer.ImportCapability
import com.example.ui.components.ImportDestinationSelector
import kotlinx.coroutines.launch

private enum class ExternalImportMode(val label: String) {
    URL("分享网址"),
    SEARCH("搜索平台"),
    PASTE("贴上文字"),
    FILE("导入文件")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalImportScreen(
    decks: List<Deck>,
    initialDeckId: Long? = null,
    onImportCards: (List<ExternalCardCandidate>, Long, (Int) -> Unit) -> Unit,
    aiAvailable: Boolean = false,
    onFormatCardsWithAi: suspend (List<ExternalCardCandidate>) -> AiExternalFormattingResult = {
        AiExternalFormattingResult(it, 0, it.count { card -> card.selected }, "尚未设置可用的个人 AI API")
    },
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val publicSources = remember {
        ExternalDeckImporter.sources.filter {
            it.capability == ImportCapability.PUBLIC_SHARE_PAGE && it.id != "studysmarter"
        }
    }
    var mode by remember { mutableStateOf(ExternalImportMode.URL) }
    var selectedDeckId by remember(decks, initialDeckId) {
        mutableStateOf(
            initialDeckId?.takeIf { requestedId -> decks.any { it.id == requestedId } }
                ?: decks.firstOrNull()?.id
        )
    }
    var selectedSource by remember { mutableStateOf(publicSources.first()) }
    var searchQuery by remember { mutableStateOf("") }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<ExternalSearchResult>>(emptyList()) }
    var shareUrl by remember { mutableStateOf("") }
    var pastedText by remember { mutableStateOf("") }
    var guidance by remember {
        mutableStateOf("贴上分享网址后会识别来源；公开文字、CSV、TSV、JSON 可直接预览。")
    }
    var loading by remember { mutableStateOf(false) }
    var importedCount by remember { mutableStateOf<Int?>(null) }
    var aiFormattingEnabled by remember(aiAvailable) { mutableStateOf(aiAvailable) }
    var aiPrepared by remember { mutableStateOf(false) }
    val candidates = remember { mutableStateListOf<ExternalCardCandidate>() }
    val listState = rememberLazyListState()

    fun showParsed(title: String, source: String, cards: List<ExternalCardCandidate>) {
        candidates.clear()
        candidates.addAll(cards.map { it.copy(sourceName = it.sourceName.ifBlank { source }) })
        aiPrepared = false
        guidance = "$title：找到 ${cards.size} 张卡片，请勾选后导入。"
        importedCount = null
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            guidance = "无法打开网站：${it.message ?: "找不到浏览器"}"
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            runCatching { ExternalDeckImporter.parseUri(context, uri) }
                .onSuccess { showParsed(it.title, it.sourceName, it.cards) }
                .onFailure { guidance = "文件解析失败：${it.message ?: "格式不支持"}" }
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 48.dp,
                title = {
                    Text("线上导入", fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(top = 6.dp).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("保存到", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (decks.isEmpty()) {
                    Text(
                        "请先创建文件夹。",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    ImportDestinationSelector(
                        decks = decks,
                        selectedDeckId = selectedDeckId,
                        onDeckSelected = { selectedDeckId = it }
                    )
                }
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ExternalImportMode.entries) { item ->
                        FilterChip(
                            selected = mode == item,
                            onClick = { mode = item },
                            label = { Text(item.label) },
                            leadingIcon = if (mode == item) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )
                    }
                }
            }

            when (mode) {
                ExternalImportMode.URL -> item {
                    ImportPanel("贴上分享网址", Icons.Default.Link) {
                        OutlinedTextField(
                            value = shareUrl,
                            onValueChange = {
                                shareUrl = it
                                ExternalDeckImporter.detectSource(it)?.let { detected ->
                                    selectedSource = detected
                                    guidance = "${detected.name}：${detected.guidance}"
                                }
                            },
                            label = { Text("https://…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    shareUrl = clipboard.getText()?.text.orEmpty()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null)
                                Text("贴上")
                            }
                            Button(
                                onClick = {
                                    val url = shareUrl.trim()
                                    if (url.isBlank()) {
                                        guidance = "请先贴上分享网址"
                                        return@Button
                                    }
                                    val source = ExternalDeckImporter.detectSource(url)
                                    selectedSource = source ?: selectedSource
                                    when {
                                        ExternalDeckImporter.isDirectTextUrl(url) -> scope.launch {
                                            loading = true
                                            runCatching {
                                                ExternalDeckImporter.parseText(
                                                    ExternalDeckImporter.fetchDirectText(url),
                                                    source?.name ?: "网址导入"
                                                )
                                            }.onSuccess {
                                                showParsed(it.title, source?.name ?: it.sourceName, it.cards)
                                            }.onFailure {
                                                guidance = "网址导入失败：${it.message ?: "无法下载"}"
                                            }
                                            loading = false
                                        }
                                        else -> scope.launch {
                                            loading = true
                                            guidance = "正在读取公开分享页，只会导入网站实际返回的内容…"
                                            runCatching {
                                                ExternalDeckImporter.fetchSharedDeck(context, url)
                                            }.onSuccess {
                                                showParsed(it.title, it.sourceName, it.cards)
                                                guidance = "${it.sourceName} 公开分享页解析成功：找到 ${it.cards.size} 张真实卡片，请预览后再导入。"
                                            }.onFailure {
                                                guidance = "分享页无法导入：${it.message ?: "网站没有公开卡片数据"}"
                                            }
                                            loading = false
                                        }
                                    }
                                },
                                enabled = !loading,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Text("识别")
                            }
                        }
                    }
                }

                ExternalImportMode.SEARCH -> item {
                    ImportPanel("搜索平台", Icons.Default.Search) {
                        Text("选择网站", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { sourceMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(selectedSource.name, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "选择网站")
                            }
                            DropdownMenu(
                                expanded = sourceMenuExpanded,
                                onDismissRequest = { sourceMenuExpanded = false }
                            ) {
                                publicSources.forEach { source ->
                                    DropdownMenuItem(
                                        text = { Text(source.name) },
                                        onClick = {
                                            selectedSource = source
                                            sourceMenuExpanded = false
                                            searchResults = emptyList()
                                            guidance = source.guidance
                                        }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("例如：TOEIC 600、Oxford 3000") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (searchQuery.isBlank()) {
                                    guidance = "请输入搜索关键字"
                                } else scope.launch {
                                    loading = true
                                    searchResults = emptyList()
                                    guidance = "正在搜索 ${selectedSource.name} 的真实公开结果…"
                                    runCatching {
                                        ExternalDeckImporter.searchPublicDecks(
                                            context,
                                            selectedSource,
                                            searchQuery
                                        )
                                    }.onSuccess { results ->
                                        searchResults = results
                                        guidance = if (results.isEmpty()) {
                                            "${selectedSource.name} 没有找到可识别的公开结果"
                                        } else {
                                            "找到 ${results.size} 个结果，选择后会读取该页实际公开的卡片。"
                                        }
                                    }.onFailure {
                                        guidance = "搜索失败：${it.message ?: "网站没有返回公开结果"}"
                                    }
                                    loading = false
                                }
                            },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Text("搜索 ${selectedSource.name}")
                        }
                        Text(
                            when (selectedSource.capability) {
                                ImportCapability.PUBLIC_SHARE_PAGE ->
                                    "找到牌组后，复制分享网址回来解析。"
                                ImportCapability.EXPORT_AND_PASTE ->
                                    "从平台导出后，再回来贴上内容。"
                                else ->
                                    "可贴分享网址，或下载文件后导入。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        searchResults.forEach { result ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(result.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (result.description.isNotBlank()) {
                                        Text(
                                            result.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        result.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Button(
                                        onClick = {
                                            when (selectedSource.capability) {
                                                ImportCapability.PUBLIC_SHARE_PAGE -> scope.launch {
                                                    loading = true
                                                    guidance = "正在依原始顺序读取「${result.title}」…"
                                                    runCatching {
                                                        ExternalDeckImporter.fetchSharedDeck(context, result.url)
                                                    }.onSuccess {
                                                        showParsed(it.title, it.sourceName, it.cards)
                                                        guidance = "${it.sourceName} 解析完成：${it.cards.size} 张，已保留来源顺序并移除重复项目。"
                                                    }.onFailure {
                                                        guidance = "此结果无法导入：${it.message ?: "页面没有公开卡片数据"}"
                                                    }
                                                    loading = false
                                                }
                                                else -> {
                                                    guidance = selectedSource.guidance
                                                    openUrl(result.url)
                                                }
                                            }
                                        },
                                        enabled = !loading,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            if (selectedSource.capability == ImportCapability.PUBLIC_SHARE_PAGE) Icons.Default.Download
                                            else Icons.Default.OpenInBrowser,
                                            contentDescription = null
                                        )
                                        Text(
                                            if (selectedSource.capability == ImportCapability.PUBLIC_SHARE_PAGE) "选择并导入"
                                            else "打开官方页面"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                ExternalImportMode.PASTE -> item {
                    ImportPanel("贴上文字", Icons.Default.ContentPaste) {
                        OutlinedTextField(
                            value = pastedText,
                            onValueChange = { pastedText = it },
                            label = { Text("每行：单词［Tab］解释［Tab］例句") },
                            minLines = 6,
                            maxLines = 12,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { pastedText = clipboard.getText()?.text.orEmpty() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null)
                                Text("贴上剪贴簿")
                            }
                            Button(
                                onClick = {
                                    runCatching { ExternalDeckImporter.parseText(pastedText, selectedSource.name) }
                                        .onSuccess { showParsed(it.title, selectedSource.name, it.cards) }
                                        .onFailure { guidance = "解析失败：${it.message ?: "格式不正确"}" }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("预览")
                            }
                        }
                    }
                }

                ExternalImportMode.FILE -> item {
                    ImportPanel("选择导出文件", Icons.Default.Download) {
                        Text(
                            "支持 CSV、TSV、TXT、JSON 与 Anki APKG。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = {
                                fileLauncher.launch(
                                    arrayOf(
                                        "text/*",
                                        "application/json",
                                        "application/zip",
                                        "application/octet-stream"
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Text("选择文件")
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.height(24.dp))
                        else Icon(Icons.Default.Language, contentDescription = null)
                        Text(guidance, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (candidates.isNotEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = aiFormattingEnabled,
                                onCheckedChange = {
                                    aiFormattingEnabled = it
                                    aiPrepared = false
                                },
                                enabled = aiAvailable
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("AI 整理", fontWeight = FontWeight.Bold)
                                Text(
                                    if (aiAvailable) {
                                        "补齐字段，确认后再保存。"
                                    } else {
                                        "设置 API 后可用；目前保留原始内容。"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("导入预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "已选 ${candidates.count { it.selected }}／${candidates.size}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        OutlinedButton(onClick = {
                            val shouldSelect = candidates.any { !it.selected }
                            candidates.indices.forEach { index ->
                                candidates[index] = candidates[index].copy(selected = shouldSelect)
                            }
                            if (shouldSelect && candidates.any { !it.aiFormatted }) aiPrepared = false
                        }) {
                            Text(if (candidates.any { !it.selected }) "全选" else "全不选")
                        }
                    }
                }

                itemsIndexed(candidates, key = { index, card -> "$index-${card.word}" }) { index, card ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Checkbox(
                                checked = card.selected,
                                onCheckedChange = {
                                    candidates[index] = card.copy(selected = it)
                                    if (it && !card.aiFormatted) aiPrepared = false
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(card.word, fontWeight = FontWeight.Bold)
                                if (card.partOfSpeech.isNotBlank() || card.phonetic.isNotBlank()) {
                                    Text(
                                        listOf(card.partOfSpeech, card.phonetic)
                                            .filter(String::isNotBlank)
                                            .joinToString("  "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    card.definition,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (card.exampleSentence.isNotBlank()) {
                                    Text(
                                        card.exampleSentence,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Button(
                        onClick = {
                            val deckId = selectedDeckId ?: return@Button
                            if (aiFormattingEnabled && aiAvailable && !aiPrepared) {
                                scope.launch {
                                    loading = true
                                    guidance = "AI 正在依照目前顺序整理 ${candidates.count { it.selected }} 张卡片…"
                                    val result = runCatching {
                                        onFormatCardsWithAi(candidates.toList())
                                    }.getOrElse {
                                        AiExternalFormattingResult(
                                            cards = candidates.toList(),
                                            formattedCount = 0,
                                            fallbackCount = candidates.count { card -> card.selected },
                                            message = "AI 整理失败：${it.message ?: "未知错误"}"
                                        )
                                    }
                                    candidates.clear()
                                    candidates.addAll(result.cards)
                                    aiPrepared = true
                                    guidance = "${result.message}。请检查预览，确认后再按保存。"
                                    loading = false
                                }
                            } else {
                                loading = true
                                onImportCards(candidates.toList(), deckId) {
                                    importedCount = it
                                    loading = false
                                    if (it > 0) {
                                        guidance = "已依目前预览顺序导入 $it 张卡片；同文件夹不会创建重复单词。"
                                    }
                                }
                            }
                        },
                        enabled = !loading && selectedDeckId != null && candidates.any { it.selected },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (aiFormattingEnabled && aiAvailable && !aiPrepared) {
                                "AI 整理 · ${candidates.count { it.selected }}"
                            } else {
                                "保存 · ${candidates.count { it.selected }}"
                            }
                        )
                    }
                    importedCount?.let {
                        Text("本次完成：$it 张", color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
        if (candidates.isNotEmpty() && listState.canScrollBackward) {
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                        listState.animateScrollToItem(minOf(6, last))
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(44.dp)
            ) { Icon(Icons.Default.KeyboardArrowUp, "回到第一个单词") }
        }
        if (candidates.isNotEmpty() && listState.canScrollForward) {
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(44.dp)
            ) { Icon(Icons.Default.KeyboardArrowDown, "前往最下面") }
        }
        }
    }
}

@Composable
private fun ImportPanel(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}
