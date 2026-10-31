package com.example.ui.screens
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.example.ui.components.ModernButton as Button
import com.example.ui.components.ModernOutlinedButton as OutlinedButton
import com.example.ui.components.ModernTonalButton as FilledTonalButton
import com.example.ui.components.ModernChoiceRow
import com.example.ui.components.ModernCard as Card
import com.example.ui.components.ModernListRow
import com.example.ui.components.CalmSectionHeader
import com.example.ui.components.ModernTextField as OutlinedTextField
import com.example.ui.components.ModernAlertDialog as AlertDialog

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.data.entity.GrammarDraft
import com.example.data.entity.GrammarQuestion
import com.example.data.entity.GrammarQuizResult
import com.example.data.entity.GrammarPattern
import com.example.data.entity.GrammarExample
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarImportScreen(
    onBack: () -> Unit,
    onManual: () -> Unit,
    onAiCreate: () -> Unit,
    onRecognize: suspend (List<Uri>, String) -> List<GrammarDraft>,
    onRecognizeText: suspend (String, String) -> GrammarDraft,
    onImportShare: suspend (Uri) -> List<GrammarDraft>,
    onPreview: (List<GrammarDraft>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pastedText by remember { mutableStateOf("") }
    fun process(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            busy = true; message = null
            runCatching { onRecognize(uris, "ENRICH") }
                .onSuccess(onPreview)
                .onFailure { message = it.message ?: "无法识别内容" }
            busy = false
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents(), ::process)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ::process)
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) pendingCameraUri?.let { process(listOf(it)) } }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) runCatching { GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.map { it.imageUri }.orEmpty() }
            .onSuccess(::process).onFailure { message = "无法读取扫描结果，请重新扫描或改用照片" }
    }
    val shareFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { busy = true; message = null; runCatching { onImportShare(uri) }.onSuccess(onPreview).onFailure { message = it.message ?: "无法读取分享档" }; busy = false }
    }
    fun launchCamera() {
        val file = File(context.cacheDir, "grammar_import_${System.currentTimeMillis()}.jpg")
        pendingCameraUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        camera.launch(pendingCameraUri!!)
    }
    fun launchScanner() {
        val activity = context.grammarActivity() ?: return
        val options = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(10)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
        runCatching { GmsDocumentScanning.getClient(options).getStartScanIntent(activity) }
            .onFailure { message = it.message ?: "此设备不支持扫描服务" }
            .getOrNull()
            ?.addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
            ?.addOnFailureListener { message = it.message ?: "无法打开扫描器" }
    }
    if (showPasteDialog) AlertDialog(
        onDismissRequest = { if (!busy) showPasteDialog = false }, title = { Text("贴上语法内容") },
        text = { OutlinedTextField(pastedText, { pastedText = it }, label = { Text("教材文字") }, minLines = 7) },
        confirmButton = { Button(enabled = pastedText.isNotBlank() && !busy, onClick = { scope.launch { busy = true; message = null; runCatching { onRecognizeText(pastedText, "ENRICH") }.onSuccess { showPasteDialog = false; onPreview(listOf(it)) }.onFailure { message = it.message ?: "无法整理文字" }; busy = false } }) { Text("创建预览") } },
        dismissButton = { TextButton(onClick = { showPasteDialog = false }) { Text("取消") } }
    )
    Scaffold(
        topBar = { TopAppBar(title = { Text("导入语法") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("选择教材来源", style = MaterialTheme.typography.headlineSmall)
                Text("创建新的语法笔记", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { ModernListRow("手动创建", "整理核心概念、句型与练习题", Icons.Default.EditNote, onManual) }
            item { ModernListRow("AI 依主题创建", "输入想学的语法与程度", Icons.Default.AutoAwesome, onAiCreate) }
            item { CalmSectionHeader("导入教材") }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalIconButton(onClick = ::launchCamera, enabled = !busy, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.CameraAlt, "拍照") }
                    FilledTonalIconButton(onClick = ::launchScanner, enabled = !busy, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.DocumentScanner, "扫描") }
                    FilledTonalIconButton(onClick = { gallery.launch("image/*") }, enabled = !busy, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.PhotoLibrary, "照片") }
                }
            }
            item { ModernListRow("文件", "PDF、Word、图片与文字档", Icons.Default.FileOpen, { if (!busy) files.launch(arrayOf("image/*", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain")) }) }
            item { ModernListRow("贴上文字", "贴上教材内容", Icons.Default.ContentPaste, { if (!busy) showPasteDialog = true }) }
            item { ModernListRow("分享档", "导入 .vocabshare", Icons.Default.Share, { if (!busy) shareFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) }
            if (busy) item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text("AI 正在识别、整理并补齐…") }
                }
            }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
private fun GrammarSourceButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(14.dp), modifier = modifier.height(54.dp)) { Icon(icon, null); Spacer(Modifier.width(7.dp)); Text(text, maxLines = 1) }
}

@Composable
private fun GrammarDropdownAnchor(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
    }
}

@Composable
private fun RecognitionOption(value: String, selected: String, title: String, subtitle: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = value == selected, onClick = { onSelect(value) })
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarNoteDetailScreen(
    note: com.example.data.entity.GrammarNote,
    patterns: List<GrammarPattern>,
    examples: List<GrammarExample>,
    questionCount: Int,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onSpeak: (String) -> Unit,
    onLearn: () -> Unit,
    onQuiz: () -> Unit
) {
    var showMistakes by remember { mutableStateOf(false) }
    var showComparison by remember { mutableStateOf(false) }
    var showWhy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除语法笔记？") }, text = { Text("笔记、题目与薄薄弱点纪录会一并删除，且无法恢复。") }, confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
    Scaffold(
        topBar = { TopAppBar(title = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, actions = { IconButton(onClick = onFavorite) { Icon(if (note.favorite) Icons.Default.Star else Icons.Default.StarBorder, "收藏") }; IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑") }; IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "删除") } }) },
        bottomBar = { Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = onLearn, modifier = Modifier.weight(1f)) { Text("开始学习") }; Button(onClick = onQuiz, enabled = questionCount > 0, modifier = Modifier.weight(1f)) { Text("开始测试") } } }
    ) { padding -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { AssistChip(onClick = {}, label = { Text(note.course) }); note.tags.split(',', '、').firstOrNull { it.isNotBlank() }?.let { AssistChip(onClick = {}, label = { Text(it.trim()) }) }; Text("熟练度 ${note.masteryPercent}%", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically)) } }
        if (note.summary.isNotBlank()) item { GrammarPanel("核心概念") { Text(note.summary) } }
        if (patterns.isNotEmpty()) itemsIndexed(patterns, key = { _, pattern -> pattern.id }) { index, pattern ->
            GrammarPanel(pattern.title.ifBlank { "句型 ${index + 1}" }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pattern.formula, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${pattern.masteryPercent}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pattern.meaning.isNotBlank()) Text(pattern.meaning)
                if (pattern.usage.isNotBlank()) Text("使用时机\n${pattern.usage}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                examples.filter { it.grammarPatternId == pattern.id }.forEach { example ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(example.sentence)
                            if (example.translation.isNotBlank()) Text(example.translation, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onSpeak(listOf(example.sentence, example.translation).filter(String::isNotBlank).joinToString("。")) }) { Icon(Icons.Default.VolumeUp, "播放例句") }
                    }
                }
                if (pattern.notes.isNotBlank()) Text(pattern.notes, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            if (note.structure.isNotBlank()) item { GrammarPanel("句型") { Text(note.structure) } }
            if (note.exampleSentence.isNotBlank()) item { GrammarPanel("例句") { Text(note.exampleSentence); if (note.exampleTranslation.isNotBlank()) Text(note.exampleTranslation, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        if (note.commonMistakes.isNotBlank()) item { ExpandableGrammarPanel("常见错误", showMistakes, { showMistakes = !showMistakes }, note.commonMistakes) }
        if (note.comparison.isNotBlank()) item { ExpandableGrammarPanel("容易混淆", showComparison, { showComparison = !showComparison }, note.comparison) }
        item { Spacer(Modifier.height(70.dp)) }
    } }
}

@Composable private fun GrammarPanel(title: String, content: @Composable ColumnScope.() -> Unit) { Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontWeight = FontWeight.Bold); content() } } }
@Composable private fun ExpandableGrammarPanel(title: String, expanded: Boolean, onToggle: () -> Unit, text: String) { Card(onClick = onToggle, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row { Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起" else "展开") }; if (expanded) Text(text) } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarImportPreviewScreen(
    initialDrafts: List<GrammarDraft>, courses: List<String>, onBack: () -> Unit,
    onSave: (List<GrammarDraft>, String, String) -> Unit
) {
    val drafts = remember(initialDrafts) { mutableStateListOf<GrammarDraft>().apply { addAll(initialDrafts) } }
    val selected = remember(initialDrafts) { mutableStateListOf<Boolean>().apply { repeat(initialDrafts.size) { add(true) } } }
    var course by remember { mutableStateOf(courses.firstOrNull().orEmpty()) }
    var courseMenu by remember { mutableStateOf(false) }
    var duplicateMode by remember { mutableStateOf("OVERWRITE") }
    var duplicateMenu by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editSummary by remember { mutableStateOf("") }
    val selectedDrafts = drafts.filterIndexed { i, _ -> selected.getOrElse(i) { false } }
    editingIndex?.let { index -> AlertDialog(
        onDismissRequest = { editingIndex = null }, title = { Text("编辑导入内容") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(editTitle, { editTitle = it }, label = { Text("语法名称") }); OutlinedTextField(editSummary, { editSummary = it }, label = { Text("核心概念") }, minLines = 3) } },
        confirmButton = { Button(enabled = editTitle.isNotBlank(), onClick = { drafts[index] = drafts[index].copy(title = editTitle.trim(), summary = editSummary); editingIndex = null }) { Text("应用") } },
        dismissButton = { TextButton(onClick = { editingIndex = null }) { Text("取消") } }
    ) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("导入预览") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) },
        bottomBar = { Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("重新识别") }; Button(enabled = selectedDrafts.isNotEmpty(), onClick = { onSave(selectedDrafts, course, duplicateMode) }, modifier = Modifier.weight(1f)) { Text("保存 ${selectedDrafts.size} 篇") } } }
    ) { padding -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ExposedDropdownMenuBox(expanded = courseMenu, onExpandedChange = { courseMenu = it }, modifier = Modifier.weight(1f)) {
                GrammarDropdownAnchor("课程", course, Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = courseMenu, onDismissRequest = { courseMenu = false }) { courses.forEach { DropdownMenuItem(text = { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { course = it; courseMenu = false }) } }
            }
            ExposedDropdownMenuBox(expanded = duplicateMenu, onExpandedChange = { duplicateMenu = it }, modifier = Modifier.weight(1f)) {
                GrammarDropdownAnchor("重复处理", when (duplicateMode) { "SKIP" -> "略过同名语法"; "COPY" -> "另存新笔记"; else -> "覆盖同名语法" }, Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = duplicateMenu, onDismissRequest = { duplicateMenu = false }) { listOf("OVERWRITE" to "覆盖同名语法", "SKIP" to "略过同名语法", "COPY" to "另存新笔记").forEach { (v, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { duplicateMode = v; duplicateMenu = false }) } }
            }
        } }
        itemsIndexed(drafts) { index, draft -> Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(selected[index], { selected[index] = it }); Column(Modifier.weight(1f)) { Text(draft.title.ifBlank { "未命名语法" }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${draft.patterns.size} 组句型 · ${draft.patterns.sumOf { it.examples.size }} 个例句 · ${draft.questions.size} 题", color = MaterialTheme.colorScheme.onSurfaceVariant); AssistChip(onClick = {}, label = { Text("AI 已整理") }) }; IconButton(onClick = { editingIndex = index; editTitle = draft.title; editSummary = draft.summary }) { Icon(Icons.Default.Edit, "编辑") } } } }
        item { Spacer(Modifier.height(70.dp)) }
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarQuizResultScreen(noteTitle: String, result: GrammarQuizResult, questions: List<GrammarQuestion>, onBack: () -> Unit, onPracticeWrong: () -> Unit, onRetryAll: () -> Unit) {
    val wrong = questions.filter { it.id in result.wrongQuestionIds }.sortedByDescending { result.wrongCounts[it.id] ?: 0 }
    var showWrongDetails by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("测试完成") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.Close, "关闭") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("本次正确率", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${result.accuracyPercent}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold); Text("首次答对 ${result.firstTryCorrect}／${result.totalQuestions} · 共作答 ${result.totalAttempts} 次") } } }
            item { Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("熟练度变化", fontWeight = FontWeight.Bold); Row { Text(noteTitle, modifier = Modifier.weight(1f)); Text("${result.masteryBefore}% → ${result.masteryAfter}%", fontWeight = FontWeight.Bold) }; LinearProgressIndicator(progress = { result.masteryAfter / 100f }, modifier = Modifier.fillMaxWidth()); Text("依首次答对与本次错题计算。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            if (wrong.isNotEmpty()) item { Text("需要加强", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (showWrongDetails) itemsIndexed(wrong) { _, q -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(q.prompt.replace("{{answer}}", "____"), maxLines = 2); Text("正确答案：${q.answer}"); Text("错 ${result.wrongCounts[q.id] ?: 1} 次", color = MaterialTheme.colorScheme.error) } } } }
            item { if (wrong.isNotEmpty()) { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = { showWrongDetails = !showWrongDetails }, modifier = Modifier.weight(1f)) { Text(if (showWrongDetails) "收起错题" else "查看错题") }; Button(onClick = onPracticeWrong, modifier = Modifier.weight(1f)) { Text("薄薄弱点练习") } } } else Button(onClick = onRetryAll, modifier = Modifier.fillMaxWidth()) { Text("再次测试") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarQuizFlowScreen(
    noteId: Long,
    noteTitle: String,
    masteryBefore: Int,
    questions: List<GrammarQuestion>,
    onAnswer: (GrammarQuestion, Boolean, Boolean) -> Unit,
    onBack: () -> Unit,
    onComplete: (GrammarQuizResult) -> Unit
) {
    val original = remember(noteId, questions) { questions.sortedBy { it.sortOrder } }
    var queue by remember(noteId, questions) { mutableStateOf(original) }
    var index by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(false) }
    var correct by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(false) }
    var totalAttempts by remember { mutableIntStateOf(0) }
    var firstTryCorrect by remember { mutableIntStateOf(0) }
    val attempted = remember { mutableStateListOf<Long>() }
    val wrongCounts = remember { mutableStateMapOf<Long, Int>() }
    val wrongQueue = remember { mutableStateListOf<GrammarQuestion>() }
    val question = queue.getOrNull(index)
    fun clearResponse() { selected = ""; input = ""; checked = false; correct = false; hint = false }
    fun finish() {
        val penalty = wrongCounts.values.sum() * 3
        val reward = firstTryCorrect * 5
        onComplete(GrammarQuizResult(noteId, original.size, firstTryCorrect, totalAttempts, wrongCounts.keys.toList(), wrongCounts.toMap(), masteryBefore, (masteryBefore + reward - penalty).coerceIn(0, 100)))
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("语法测试") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.Close, "退出") } }, actions = { Text("${(index + 1).coerceAtMost(queue.size)}／${queue.size}", modifier = Modifier.padding(end = 16.dp)) }) },
        bottomBar = { if (question != null) Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(enabled = !checked, onClick = { hint = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Lightbulb, null); Spacer(Modifier.width(5.dp)); Text("提示") }
            val choices = question.options.split(',', '、', '\n').map(String::trim).filter(String::isNotBlank)
            val response = if (choices.isNotEmpty()) selected else input
            Button(enabled = response.isNotBlank(), onClick = {
                if (!checked) {
                    val accepted = (question.acceptedAnswers.split(',', '、') + question.answer).map { it.trim().lowercase() }.filter(String::isNotBlank).toSet()
                    correct = response.trim().lowercase() in accepted
                    checked = true; totalAttempts++; onAnswer(question, correct, hint)
                    if (question.id !in attempted) { attempted += question.id; if (correct) firstTryCorrect++ }
                    if (!correct) { wrongCounts[question.id] = (wrongCounts[question.id] ?: 0) + 1; if (wrongQueue.none { it.id == question.id }) wrongQueue += question }
                } else if (!correct) clearResponse()
                else if (index < queue.lastIndex) { index++; clearResponse() }
                else if (wrongQueue.isNotEmpty()) { queue = wrongQueue.toList(); wrongQueue.clear(); index = 0; clearResponse() }
                else finish()
            }, modifier = Modifier.weight(1f)) { Text(if (!checked) "确认答案" else if (correct) "下一题" else "再答一次") }
        } }
    ) { padding ->
        if (question == null) Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text("这篇语法尚未创建练习题") }
        else {
            val choices = remember(question.id, noteId) { question.options.split(',', '、', '\n').map(String::trim).filter(String::isNotBlank).distinct().take(4).shuffled() }
            Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(progress = { (index + 1f) / queue.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { AssistChip(onClick = {}, label = { Text(if (choices.isEmpty()) "填空" else "选择") }); Text(question.prompt.replace("{{answer}}", "____"), style = MaterialTheme.typography.titleLarge); if (question.translation.isNotBlank()) Text(question.translation, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                if (choices.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { choice ->
                        ModernChoiceRow(text = choice, selected = selected == choice, enabled = !checked, onClick = { selected = choice }, label = ('A' + choices.indexOf(choice)).toString())
                    }
                }
                else OutlinedTextField(input, { input = it }, enabled = !checked, label = { Text("输入完整答案") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (hint && !checked) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Row(Modifier.padding(14.dp)) { Icon(Icons.Default.Lightbulb, null); Spacer(Modifier.width(8.dp)); Text(question.explanation.ifBlank { "想想这个时间或语境需要使用哪一种语法形式。" }) } }
                if (checked) Card(colors = CardDefaults.cardColors(containerColor = if (correct) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(if (correct) "答对了" else "答错了", fontWeight = FontWeight.Bold); if (!correct) Text("正确答案：${question.answer}"); if (question.explanation.isNotBlank()) Text(question.explanation) } }
            }
        }
    }
}

private tailrec fun Context.grammarActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.grammarActivity(); else -> null }
