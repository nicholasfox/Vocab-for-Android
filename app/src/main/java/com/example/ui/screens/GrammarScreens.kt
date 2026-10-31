package com.example.ui.screens
import com.example.ui.components.ModernButton as Button
import com.example.ui.components.ModernOutlinedButton as OutlinedButton
import com.example.ui.components.ModernTonalButton as FilledTonalButton
import com.example.ui.components.ModernChoiceRow
import com.example.ui.components.ModernTabRow as TabRow
import com.example.ui.components.ModernCard as Card
import com.example.ui.components.ModernListRow
import com.example.ui.components.CalmSectionHeader
import com.example.ui.components.CalmEmptyState
import com.example.ui.components.ModernTextField as OutlinedTextField
import com.example.ui.components.ModernAlertDialog as AlertDialog

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.data.entity.*
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import java.io.File
import org.json.JSONArray
import kotlin.math.abs
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
private fun GrammarCourseChips(
    notes: List<GrammarNote>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    val courses = remember(notes) { notes.groupingBy { it.course.ifBlank { "未分类" } }.eachCount().toList() }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("选择课程", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = selected == null,
                    onClick = { onSelect(null) },
                    label = { Text("全部文件夹 (${notes.map { it.folder }.distinct().size})", maxLines = 1) }
                )
            }
            items(courses, key = { it.first }) { (course, count) ->
                FilterChip(
                    selected = selected == course,
                    onClick = { onSelect(course) },
                    label = { Text("$course ($count)", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}

@Composable
fun GrammarDashboardScreen(
    notes: List<GrammarNote>,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onOpen: (GrammarNote) -> Unit,
    onLearn: (GrammarNote) -> Unit,
    onQuiz: (GrammarNote) -> Unit,
    onSeeAllNotes: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val now = System.currentTimeMillis()
    val due = notes.filter { it.nextReviewAt <= now }
    val recentNotes = remember(notes) { notes.sortedByDescending { it.updatedAt }.take(4) }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 16.dp, 18.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("让句型变成直觉", style = MaterialTheme.typography.headlineSmall)
            Text("从理解到应用，一次掌握一个语法。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("今日语法", style = MaterialTheme.typography.titleMedium)
                    Text("${due.size} 个待复习", style = MaterialTheme.typography.headlineLarge)
                    Text("共 ${notes.size} 篇笔记", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = notes.isNotEmpty(), onClick = { (due.firstOrNull() ?: notes.firstOrNull())?.let(onLearn) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("开始复习") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onAdd, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f).height(50.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("添加语法") }
                FilledTonalButton(onClick = onImport, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f).height(50.dp)) { Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(6.dp)); Text("导入教材") }
            }
        }
        item { CalmSectionHeader("最近研读", action = { TextButton(onClick = onSeeAllNotes) { Text("查看全部") } }) }
        if (recentNotes.isEmpty()) item { CalmEmptyState(Icons.Default.MenuBook, "创建第一篇语法笔记", "添加句型或导入教材，整理出例句与练习题。", action = { TextButton(onClick = onAdd) { Text("添加语法") } }) }
        items(recentNotes, key = { it.id }) { note ->
            ModernListRow(note.title, "${note.course} · ${note.masteryPercent}% 精通", Icons.Default.MenuBook, { onOpen(note) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarNotesScreen(
    notes: List<GrammarNote>,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onReorderNotes: (List<Long>) -> Unit,
    onOpen: (GrammarNote) -> Unit,
    modifier: Modifier = Modifier
) {
    val filterPrefs = LocalContext.current.getSharedPreferences("grammar_page_filters", Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val courses = remember(notes) { notes.map { it.course }.filter { it.isNotBlank() }.distinct() }
    var selectedCourse by rememberSaveable { mutableStateOf(filterPrefs.getString("library_course", null)) }
    var selectedType by rememberSaveable { mutableStateOf(filterPrefs.getString("library_folder", null)) }
    val folders = remember(notes, selectedCourse) { notes.filter { selectedCourse == null || it.course == selectedCourse }.map { it.folder }.filter { it.isNotBlank() }.distinct() }
    var typeMenu by remember { mutableStateOf(false) }
    LaunchedEffect(selectedCourse, selectedType) { filterPrefs.edit().apply {
        if (selectedCourse == null) remove("library_course") else putString("library_course", selectedCourse)
        if (selectedType == null) remove("library_folder") else putString("library_folder", selectedType)
    }.apply() }

    val filteredNotes = remember(notes, searchQuery, selectedCourse, selectedType) {
        notes.filter { note ->
            val matchCourse = selectedCourse == null || note.course.equals(selectedCourse, ignoreCase = true)
            val matchType = selectedType == null || note.folder.equals(selectedType, ignoreCase = true)
            val matchQuery = searchQuery.isBlank() ||
                note.title.contains(searchQuery, ignoreCase = true) ||
                note.summary.contains(searchQuery, ignoreCase = true) ||
                note.structure.contains(searchQuery, ignoreCase = true) ||
                note.course.contains(searchQuery, ignoreCase = true) || note.folder.contains(searchQuery, ignoreCase = true)
            matchCourse && matchType && matchQuery
        }
    }
    val reorderEnabled = searchQuery.isBlank() && selectedType == null
    val sourceNoteIds = if (reorderEnabled) filteredNotes.map { it.id } else emptyList()
    var displayedNoteIds by remember(sourceNoteIds) { mutableStateOf(sourceNoteIds) }
    val displayedNotes = if (reorderEnabled) {
        displayedNoteIds.mapNotNull { id -> filteredNotes.firstOrNull { it.id == id } }
    } else filteredNotes
    val noteListState = rememberLazyListState()
    var noteOrderChanged by remember { mutableStateOf(false) }
    val noteReorderState = rememberReorderableLazyListState(noteListState) { from, to ->
        // Search, filters and action buttons occupy the first three positions.
        if (reorderEnabled && displayedNoteIds.isNotEmpty()) {
            val fromIndex = (from.index - 3).coerceIn(displayedNoteIds.indices)
            val toIndex = (to.index - 3).coerceIn(displayedNoteIds.indices)
            if (fromIndex != toIndex) {
                displayedNoteIds = displayedNoteIds.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
                noteOrderChanged = true
            }
        }
    }

    LaunchedEffect(noteReorderState.isAnyItemDragging) {
        if (!noteReorderState.isAnyItemDragging && noteOrderChanged) {
            onReorderNotes(displayedNoteIds)
            noteOrderChanged = false
        }
    }

    LazyColumn(
        state = noteListState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Search bar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索语法或句型", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GrammarCourseChips(notes, selectedCourse) { selectedCourse = it; selectedType = null }
                ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { typeMenu = it }) {
                    OutlinedTextField(
                        value = selectedType ?: "全部文件夹", onValueChange = {}, readOnly = true,
                        label = { Text("文件夹") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenu) },
                        singleLine = true, modifier = Modifier.menuAnchor().fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge
                    )
                    ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        DropdownMenuItem(text = { Text("全部文件夹") }, onClick = { selectedType = null; typeMenu = false })
                        folders.forEach { type -> DropdownMenuItem(text = { Text(type, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { selectedType = type; typeMenu = false }) }
                    }
                }
            }
        }

        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onAdd, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("添加") }
            FilledTonalButton(onClick = onImport, modifier = Modifier.weight(1f)) { Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(6.dp)); Text("导入") }
        } }

        val showFolderOverview = searchQuery.isBlank() && selectedType == null
        val folderGroups = filteredNotes.groupBy { it.folder.ifBlank { "未分类" } }
        if (showFolderOverview && folderGroups.isNotEmpty()) {
            items(folderGroups.entries.toList(), key = { "grammar-folder-${it.key}" }) { (folder, folderNotes) ->
                val dueCount = folderNotes.count { it.nextReviewAt <= now }
                Card(
                    onClick = { selectedType = folder },
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(folder, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(selectedCourse ?: folderNotes.firstOrNull()?.course.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.ChevronRight, "管理")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Text("${folderNotes.size} 篇语法")
                            Text("待复习 $dueCount")
                            Text("题目 ${folderNotes.sumOf { if (it.questionTemplate.isBlank()) 0 else 1 }}")
                        }
                        Button(onClick = { selectedType = folder }, modifier = Modifier.fillMaxWidth()) { Text("管理") }
                    }
                }
            }
        } else if (displayedNotes.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                        Text("未找到符合条件的语法笔记", fontWeight = FontWeight.Bold)
                        Button(onClick = onAdd) { Text("创建新笔记") }
                    }
                }
            }
        } else {
            itemsIndexed(displayedNotes, key = { _, note -> note.id }) { _, note ->
                ReorderableItem(state = noteReorderState, key = note.id) {
                    GrammarNoteCard(
                        note = note,
                        now = now,
                        onOpen = onOpen,
                        showReorder = reorderEnabled,
                        dragHandleModifier = Modifier.draggableHandle()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarStudyHubScreen(
    notes: List<GrammarNote>,
    onLearn: (GrammarNote) -> Unit,
    onQuiz: (GrammarNote) -> Unit,
    onOpen: (GrammarNote) -> Unit,
    modifier: Modifier = Modifier
) {
    val filterPrefs = LocalContext.current.getSharedPreferences("grammar_page_filters", Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    val courses = remember(notes) { notes.map { it.course }.filter(String::isNotBlank).distinct() }
    var selectedCourse by rememberSaveable { mutableStateOf(filterPrefs.getString("study_course", null)) }
    var selectedType by rememberSaveable { mutableStateOf(filterPrefs.getString("study_folder", null)) }
    val grammarTypes = remember(notes, selectedCourse) { notes.filter { selectedCourse == null || it.course == selectedCourse }.map { it.folder }.filter(String::isNotBlank).distinct() }
    var typeMenu by remember { mutableStateOf(false) }
    LaunchedEffect(selectedCourse, selectedType) { filterPrefs.edit().apply {
        if (selectedCourse == null) remove("study_course") else putString("study_course", selectedCourse)
        if (selectedType == null) remove("study_folder") else putString("study_folder", selectedType)
    }.apply() }
    val filtered = remember(notes, selectedCourse, selectedType) { notes.filter {
        (selectedCourse == null || it.course == selectedCourse) && (selectedType == null || it.folder == selectedType)
    } }
    val due = filtered.filter { it.nextReviewAt <= now }
    val nextNote = due.firstOrNull() ?: filtered.firstOrNull()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GrammarCourseChips(notes, selectedCourse) { selectedCourse = it; selectedType = null }
                GrammarFilterMenu("文件夹", selectedType ?: "全部文件夹", grammarTypes, typeMenu, { typeMenu = it }, { selectedType = it }, Modifier.fillMaxWidth())
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Style, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("语法学习", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text("一次只专注一个概念、句型或例句，进度会自动保存。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        enabled = nextNote != null,
                        onClick = { nextNote?.let(onLearn) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (due.isNotEmpty()) "复习全部 (${due.size})" else "学习全部")
                    }
                }
            }
        }

        item {
            Text("按笔记学习与演练", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (filtered.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Style, null, tint = MaterialTheme.colorScheme.primary)
                        Text("还没有可学习的语法笔记", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            items(filtered, key = { it.id }) { note ->
                Card(
                    onClick = { onOpen(note) },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GrammarNoteCardBody(note = note, now = now)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onLearn(note) }, modifier = Modifier.weight(1f)) {
                                Text("学习")
                            }
                            Button(onClick = { onQuiz(note) }, modifier = Modifier.weight(1f)) {
                                Text("测试")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GrammarNoteCard(
    note: GrammarNote,
    now: Long,
    onOpen: (GrammarNote) -> Unit,
    showReorder: Boolean = false,
    dragHandleModifier: Modifier = Modifier
) {
    Card(onClick = { onOpen(note) }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { GrammarNoteCardBody(note = note, now = now) }
                if (showReorder) {
                    IconButton(onClick = {}, modifier = dragHandleModifier.size(40.dp)) {
                        Icon(Icons.Default.DragHandle, contentDescription = "拖动调整语法顺序")
                    }
                }
            }
        }
    }
}

@Composable
private fun GrammarNoteCardBody(note: GrammarNote, now: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(note.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note.structure.ifBlank { note.summary }.ifBlank { "尚未填写句型" }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (note.nextReviewAt <= now) "待复习" else "${note.masteryPercent}%", color = MaterialTheme.colorScheme.primary)
    }
    LinearProgressIndicator(progress = { note.masteryPercent / 100f }, modifier = Modifier.fillMaxWidth())
    AssistChip(onClick = {}, label = { Text("${note.course} · ${note.folder}", maxLines = 1, overflow = TextOverflow.Ellipsis) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GrammarFilterMenu(
    label: String,
    value: String,
    options: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange, modifier = modifier) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, singleLine = true,
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(text = { Text("全部$label") }, onClick = { onSelect(null); onExpandedChange(false) })
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); onExpandedChange(false) }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GrammarEditableMenu(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    onAddRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.filter(String::isNotBlank).distinct().forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onSelect(option); expanded = false }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("添加$label…") },
                leadingIcon = { Icon(Icons.Default.Add, null) },
                onClick = { expanded = false; onAddRequested() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarEditorScreen(note: GrammarNote?, initialDraft: GrammarDraft? = null, questions: List<GrammarQuestion>, patterns: List<GrammarPattern>, examples: List<GrammarExample>, courses: List<String>, folders: List<String> = emptyList(), categories: List<String> = emptyList(), levels: List<String> = emptyList(), initialCourse: String? = null, initialFolder: String? = null, initialShowAiDialog: Boolean = false, onGenerateAi: suspend (String) -> GrammarDraft, onScanImages: suspend (List<Uri>) -> GrammarDraft, onSave: (GrammarNote, List<GrammarQuestionDraft>, List<GrammarPatternDraft>) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var course by rememberSaveable(note?.id, initialCourse) { mutableStateOf(note?.course ?: initialCourse ?: courses.firstOrNull().orEmpty()) }
    var folder by rememberSaveable(note?.id, initialFolder) { mutableStateOf(note?.folder ?: initialFolder ?: "未分类") }
    var title by rememberSaveable(note?.id) { mutableStateOf(note?.title ?: initialDraft?.title.orEmpty()) }
    val linkedCourses = remember(courses) { (listOf("通用") + courses).filter(String::isNotBlank).distinct() }
    val linkedFolders = remember(folders, course, note?.folder) { (listOf("未分类") + folders + note?.folder.orEmpty()).filter(String::isNotBlank).distinct() }
    var summary by rememberSaveable(note?.id) { mutableStateOf(note?.summary ?: initialDraft?.summary.orEmpty()) }; var structure by rememberSaveable(note?.id) { mutableStateOf(note?.structure ?: initialDraft?.structure.orEmpty()) }; var usage by rememberSaveable(note?.id) { mutableStateOf(note?.usage ?: initialDraft?.usage.orEmpty()) }
    var example by rememberSaveable(note?.id) { mutableStateOf(note?.exampleSentence ?: initialDraft?.exampleSentence.orEmpty()) }; var translation by rememberSaveable(note?.id) { mutableStateOf(note?.exampleTranslation ?: initialDraft?.exampleTranslation.orEmpty()) }; var mistakes by rememberSaveable(note?.id) { mutableStateOf(note?.commonMistakes ?: initialDraft?.commonMistakes.orEmpty()) }; var comparison by rememberSaveable(note?.id) { mutableStateOf(note?.comparison ?: initialDraft?.comparison.orEmpty()) }; var tags by rememberSaveable(note?.id) { mutableStateOf(note?.tags ?: initialDraft?.tags.orEmpty()) }
    var category by rememberSaveable(note?.id) { mutableStateOf(note?.category ?: initialDraft?.category ?: "其他") }
    var level by rememberSaveable(note?.id) { mutableStateOf(note?.level ?: initialDraft?.level ?: "未分级") }
    val categoryOptions = remember(categories, note?.category) { (listOf("时态", "句型", "介系词", "连接词", "语态", "比较", "其他") + categories + note?.category.orEmpty()).filter(String::isNotBlank).distinct() }
    val levelOptions = remember(levels, note?.level) { (listOf("初级", "中级", "中高级", "高级", "未分级") + levels + note?.level.orEmpty()).filter(String::isNotBlank).distinct() }
    var customField by remember { mutableStateOf<String?>(null) }
    var customValue by remember { mutableStateOf("") }
    val patternDrafts = remember(note?.id, patterns, examples) { mutableStateListOf<GrammarPatternDraft>().apply {
        addAll(patterns.map { pattern -> GrammarPatternDraft(
            title = pattern.title, formula = pattern.formula, meaning = pattern.meaning,
            usage = pattern.usage, notes = pattern.notes,
            examples = examples.filter { it.grammarPatternId == pattern.id }.map { GrammarExampleDraft(it.sentence, it.translation, it.highlightedText) }
        ) })
        if (isEmpty()) addAll(initialDraft?.patterns.orEmpty())
        if (isEmpty() && (note?.structure?.isNotBlank() == true || note?.exampleSentence?.isNotBlank() == true)) add(
            GrammarPatternDraft(title = "主要句型", formula = note.structure, meaning = note.summary, usage = note.usage,
                examples = if (note.exampleSentence.isBlank()) emptyList() else listOf(GrammarExampleDraft(note.exampleSentence, note.exampleTranslation)))
        )
    } }
    val drafts = remember(note?.id, questions, patterns) { mutableStateListOf<GrammarQuestionDraft>().apply { addAll(questions.map { q -> GrammarQuestionDraft(
        id = q.id, grammarPatternIndex = patterns.indexOfFirst { it.id == q.grammarPatternId }, type = q.type,
        prompt = q.prompt, translation = q.translation, answer = q.answer, acceptedAnswers = q.acceptedAnswers,
        options = q.options, explanation = q.explanation, difficulty = q.difficulty, sourceType = q.sourceType
    ) }); if (isEmpty()) addAll(initialDraft?.questions.orEmpty()); if (isEmpty() && note?.questionTemplate?.isNotBlank() == true) add(GrammarQuestionDraft(prompt = note.questionTemplate, translation = note.exampleTranslation, answer = note.answer, acceptedAnswers = note.acceptedAnswers, options = note.options, explanation = note.explanation)) } }
    var aiPrompt by rememberSaveable { mutableStateOf("") }; var showAiDialog by remember { mutableStateOf(initialShowAiDialog) }; var busy by remember { mutableStateOf(false) }; var aiApplied by rememberSaveable(note?.id) { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var editorSection by rememberSaveable(note?.id) { mutableIntStateOf(0) }
    var horizontalDrag by remember { mutableFloatStateOf(0f) }
    fun applyDraft(d: GrammarDraft, onlyBlank: Boolean = false) {
        aiApplied = true
        if (!onlyBlank || title.isBlank()) title=d.title
        if (!onlyBlank || category.isBlank() || category == "其他") category=d.category
        if (!onlyBlank || level.isBlank() || level == "未分级") level=d.level
        if (!onlyBlank || summary.isBlank()) summary=d.summary
        if (!onlyBlank || structure.isBlank()) structure=d.structure
        if (!onlyBlank || usage.isBlank()) usage=d.usage
        if (!onlyBlank || example.isBlank()) example=d.exampleSentence
        if (!onlyBlank || translation.isBlank()) translation=d.exampleTranslation
        if (!onlyBlank || mistakes.isBlank()) mistakes=d.commonMistakes
        if (!onlyBlank || comparison.isBlank()) comparison=d.comparison
        if (!onlyBlank || tags.isBlank()) tags=d.tags
        val generatedPatterns=d.patterns.ifEmpty { if (d.structure.isBlank()) emptyList() else listOf(GrammarPatternDraft("主要句型", d.structure, d.summary, d.usage, examples = if (d.exampleSentence.isBlank()) emptyList() else listOf(GrammarExampleDraft(d.exampleSentence, d.exampleTranslation)))) }
        if (!onlyBlank || patternDrafts.isEmpty()) { patternDrafts.clear(); patternDrafts.addAll(generatedPatterns) }
        val generatedQuestions=d.questions.ifEmpty { if (d.questionTemplate.isBlank()) emptyList() else listOf(GrammarQuestionDraft(prompt=d.questionTemplate, translation=d.exampleTranslation, answer=d.answer, acceptedAnswers=d.acceptedAnswers, options=d.options, explanation=d.explanation, sourceType="AI")) }
        if (!onlyBlank || drafts.isEmpty()) { drafts.clear(); drafts.addAll(generatedQuestions) }
    }
    fun importImages(uris: List<Uri>) { if (uris.isEmpty()) return; scope.launch { busy=true; error=null; runCatching { onScanImages(uris) }.onSuccess(::applyDraft).onFailure { error=it.message ?: "识别失败" }; busy=false } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { importImages(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { if (it) pendingCameraUri?.let { u -> importImages(listOf(u)) } }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r -> if (r.resultCode == Activity.RESULT_OK) runCatching { GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pages?.map { it.imageUri }.orEmpty() }.onSuccess(::importImages).onFailure { error = "无法读取扫描结果，请改用照片" } }
    fun launchCamera() { val f=File(context.cacheDir,"grammar_${System.currentTimeMillis()}.jpg"); pendingCameraUri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",f); camera.launch(pendingCameraUri!!) }
    fun launchScanner() { val activity=context.findActivity() ?: return; val o=GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(10).setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build(); runCatching { GmsDocumentScanning.getClient(o).getStartScanIntent(activity) }.onFailure { error=it.message ?: "此设备不支持扫描服务" }.getOrNull()?.addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }?.addOnFailureListener { error=it.message ?: "无法打开扫描器" } }
    if (customField != null) AlertDialog(
        onDismissRequest = { customField = null },
        title = { Text("添加${customField}") },
        text = { OutlinedTextField(customValue, { customValue = it }, singleLine = true, label = { Text(customField.orEmpty()) }) },
        confirmButton = { Button(enabled = customValue.isNotBlank(), onClick = {
            when (customField) { "课程" -> course = customValue.trim(); "语法库" -> folder = customValue.trim(); "分类" -> category = customValue.trim(); "程度" -> level = customValue.trim() }
            customValue = ""; customField = null
        }) { Text("应用") } },
        dismissButton = { TextButton(onClick = { customField = null }) { Text("取消") } }
    )
    if (showAiDialog) AlertDialog(onDismissRequest={if(!busy)showAiDialog=false},title={Text("AI 创建或补齐语法")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text("输入主题可创建完整内容；已有内容时也能只补齐空白字段。");OutlinedTextField(aiPrompt,{aiPrompt=it},label={Text("语法主题与程度")},minLines=3);error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={Column(horizontalAlignment=Alignment.End){Button(enabled=aiPrompt.isNotBlank()&&!busy,onClick={scope.launch{busy=true;error=null;runCatching{onGenerateAi(aiPrompt)}.onSuccess{applyDraft(it,false);showAiDialog=false}.onFailure{error=it.message?:"AI 产生失败"};busy=false}}){Text(if(busy)"处理中…" else "依主题生成")};TextButton(enabled=aiPrompt.isNotBlank()&&!busy,onClick={scope.launch{busy=true;error=null;runCatching{onGenerateAi(aiPrompt)}.onSuccess{applyDraft(it,true);showAiDialog=false}.onFailure{error=it.message?:"AI 补齐失败"};busy=false}}){Text("只补齐空白")}}},dismissButton={TextButton(enabled=!busy,onClick={showAiDialog=false}){Text("取消")}})
    Scaffold(topBar={TopAppBar(title={Text(if(note==null)"添加语法" else "编辑语法")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回")}},actions={IconButton(onClick={showAiDialog=true}){Icon(Icons.Default.AutoAwesome,"AI 创建")}})},bottomBar={Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(onClick=onBack,modifier=Modifier.weight(1f)){Text("取消")};Button(enabled=title.isNotBlank()&&!busy,onClick={val q=drafts.firstOrNull();val firstPattern=patternDrafts.firstOrNull();val firstExample=firstPattern?.examples?.firstOrNull();onSave((note?:GrammarNote(title=title)).copy(course=course.trim().ifBlank{"通用"},folder=folder.trim().ifBlank{"未分类"},title=title.trim(),category=category,level=level,summary=summary,structure=firstPattern?.formula.orEmpty(),usage=firstPattern?.usage.orEmpty(),exampleSentence=firstExample?.sentence.orEmpty(),exampleTranslation=firstExample?.translation.orEmpty(),commonMistakes=mistakes,comparison=comparison,tags=tags,sourceType=if(note==null&&(aiApplied||initialDraft!=null))"AI" else note?.sourceType?:"MANUAL",questionTemplate=q?.prompt.orEmpty(),answer=q?.answer.orEmpty(),acceptedAnswers=q?.acceptedAnswers.orEmpty(),options=q?.options.orEmpty(),explanation=q?.explanation.orEmpty()),drafts.toList(),patternDrafts.toList())},modifier=Modifier.weight(1f)){Text("保存")}}}) { p ->
        Column(Modifier.padding(p).fillMaxSize().pointerInput(editorSection){
            detectHorizontalDragGestures(
                onDragStart={ horizontalDrag=0f },
                onHorizontalDrag={ _, amount -> horizontalDrag += amount },
                onDragEnd={ if(abs(horizontalDrag)>100f) editorSection=(editorSection + if(horizontalDrag<0) 1 else -1).coerceIn(0,3) }
            )
        }.verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            TabRow(selectedTabIndex=editorSection,containerColor=MaterialTheme.colorScheme.surface.copy(alpha=0.92f),divider={}){
                listOf("基本","句型","题目","补充").forEachIndexed{index,label->Tab(selected=editorSection==index,onClick={editorSection=index},text={Text(label,maxLines=1)})}
            }
            when(editorSection){
                0->{
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        FilledTonalIconButton(enabled=!busy,onClick=::launchCamera){Icon(Icons.Default.CameraAlt,"拍照")}
                        FilledTonalIconButton(enabled=!busy,onClick=::launchScanner){Icon(Icons.Default.DocumentScanner,"扫描")}
                        FilledTonalIconButton(enabled=!busy,onClick={gallery.launch("image/*")}){Icon(Icons.Default.PhotoLibrary,"图片")}
                    }
                    if(busy)Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(22.dp));Spacer(Modifier.width(10.dp));Text("AI 正在识别并整理语法…")}}
                    error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                    GrammarEditableMenu("课程",course.ifBlank{"通用"},linkedCourses,{course=it},{customField="课程"})
                    GrammarEditableMenu("语法库",folder.ifBlank{"未分类"},linkedFolders,{folder=it},{customField="语法库"})
                    OutlinedTextField(title,{title=it},label={Text("语法类型名称 *")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        GrammarEditableMenu("分类",category,categoryOptions,{category=it},{customField="分类"},Modifier.weight(1f))
                        GrammarEditableMenu("程度",level,levelOptions,{level=it},{customField="程度"},Modifier.weight(1f))
                    }
                    OutlinedTextField(summary,{summary=it},label={Text("核心概念")},minLines=3,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(tags,{tags=it},label={Text("标签（逗号分隔）")},singleLine=true,modifier=Modifier.fillMaxWidth())
                }
                1->{
                    Row(verticalAlignment=Alignment.CenterVertically){Text("句型与例句",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));IconButton(onClick={patternDrafts.add(GrammarPatternDraft(title="句型 ${patternDrafts.size+1}"))}){Icon(Icons.Default.AddCircle,"添加句型")}}
                    patternDrafts.forEachIndexed { index, pattern -> GrammarPatternEditor(index, pattern, { patternDrafts[index]=it }, { patternDrafts.removeAt(index) }) }
                    if(patternDrafts.isEmpty()) OutlinedButton(onClick={patternDrafts.add(GrammarPatternDraft(title="主要句型"))},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Add,null);Spacer(Modifier.width(6.dp));Text("添加第一组句型")}
                }
                2->{
                    Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("练习题",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);Text("每题可链接到一组句型。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};IconButton(onClick={drafts.add(GrammarQuestionDraft())}){Icon(Icons.Default.AddCircle,"添加题目")}}
                    drafts.forEachIndexed{i,d->GrammarQuestionEditor(i,d,patternDrafts,{drafts[i]=it},{drafts.removeAt(i)})}
                    if(drafts.isEmpty())OutlinedButton(onClick={drafts.add(GrammarQuestionDraft())},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Add,null);Spacer(Modifier.width(6.dp));Text("添加第一题")}
                }
                else->{
                    OutlinedTextField(mistakes,{mistakes=it},label={Text("常见错误")},minLines=4,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(comparison,{comparison=it},label={Text("容易混淆的语法")},minLines=4,modifier=Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(72.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GrammarQuestionEditor(
    index: Int,
    value: GrammarQuestionDraft,
    patterns: List<GrammarPatternDraft>,
    onChange: (GrammarQuestionDraft) -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(index) { mutableStateOf(index == 0) }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("第 ${index + 1} 题 · ${if (value.options.isBlank()) "填空" else "选择"}", fontWeight = FontWeight.Bold)
                    Text(
                        value.prompt.ifBlank { "尚未输入题目" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起" else "展开") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "删除这题") }
            }
            if (expanded) {
                if (patterns.isNotEmpty()) ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
                    OutlinedTextField(
                        value = patterns.getOrNull(value.grammarPatternIndex)?.title ?: "整篇语法",
                        onValueChange = {}, readOnly = true, label = { Text("对应句型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                        singleLine = true, modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("整篇语法") }, onClick = { onChange(value.copy(grammarPatternIndex = -1)); menu = false })
                        patterns.forEachIndexed { i, pattern ->
                            DropdownMenuItem(text = { Text(pattern.title.ifBlank { "句型 ${i + 1}" }, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { onChange(value.copy(grammarPatternIndex = i)); menu = false })
                        }
                    }
                }
                OutlinedTextField(value.prompt, { onChange(value.copy(prompt = it)) }, label = { Text("题目句型") }, supportingText = { Text("例：She {{answer}} here yesterday.") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value.translation, { onChange(value.copy(translation = it)) }, label = { Text("中文提示") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value.answer, { onChange(value.copy(answer = it)) }, label = { Text("正确答案") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value.acceptedAnswers, { onChange(value.copy(acceptedAnswers = it)) }, label = { Text("其他可接受答案（逗号分隔）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value.options, { onChange(value.copy(options = it)) }, label = { Text("选项（留空即为填空题）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value.explanation, { onChange(value.copy(explanation = it)) }, label = { Text("答案说明") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun GrammarPatternEditor(
    index: Int,
    value: GrammarPatternDraft,
    onChange: (GrammarPatternDraft) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by rememberSaveable(index) { mutableStateOf(index == 0) }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(value.title.ifBlank { "句型 ${index + 1}" }, fontWeight = FontWeight.Bold)
                    Text("${value.examples.size} 个例句", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起" else "展开") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "删除句型") }
            }
            if (expanded) {
                OutlinedTextField(value.title, { onChange(value.copy(title = it)) }, label = { Text("句型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value.formula, { onChange(value.copy(formula = it)) }, label = { Text("公式") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value.meaning, { onChange(value.copy(meaning = it)) }, label = { Text("意思") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value.usage, { onChange(value.copy(usage = it)) }, label = { Text("使用时机") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                value.examples.forEachIndexed { exampleIndex, example ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("例句 ${exampleIndex + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onChange(value.copy(examples = value.examples.toMutableList().also { it.removeAt(exampleIndex) })) }) { Icon(Icons.Default.Close, "删除例句") }
                            }
                            OutlinedTextField(example.sentence, { sentence -> onChange(value.copy(examples = value.examples.toMutableList().also { it[exampleIndex] = example.copy(sentence = sentence) })) }, label = { Text("英文") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(example.translation, { translation -> onChange(value.copy(examples = value.examples.toMutableList().also { it[exampleIndex] = example.copy(translation = translation) })) }, label = { Text("中文") }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                TextButton(onClick = { onChange(value.copy(examples = value.examples + GrammarExampleDraft())) }) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("添加例句")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class) @Composable fun GrammarDetailScreen(note:GrammarNote,questionCount:Int,onBack:()->Unit,onEdit:()->Unit,onDelete:()->Unit,onFavorite:()->Unit,onLearn:()->Unit,onQuiz:()->Unit){var confirm by remember{mutableStateOf(false)};if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("删除语法笔记？")},text={Text("笔记、题目与薄薄弱点纪录会一并删除，且无法恢复。")},confirmButton={TextButton(onClick={confirm=false;onDelete()}){Text("删除",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}});Scaffold(topBar={TopAppBar(title={Text(note.title,maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回")}},actions={IconButton(onClick=onFavorite){Icon(if(note.favorite)Icons.Default.Star else Icons.Default.StarBorder,"收藏")};IconButton(onClick=onEdit){Icon(Icons.Default.Edit,"编辑")};IconButton(onClick={confirm=true}){Icon(Icons.Default.Delete,"删除")}})},bottomBar={Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(onClick=onLearn,modifier=Modifier.weight(1f)){Text("开始学习")};Button(onClick=onQuiz,enabled=questionCount>0,modifier=Modifier.weight(1f)){Text("测试（$questionCount）")}}}){p->LazyColumn(Modifier.padding(p),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){AssistChip(onClick={},label={Text(note.course)});AssistChip(onClick={},label={Text("熟练度 ${note.masteryPercent}%")});AssistChip(onClick={},label={Text("$questionCount 题")})}};grammarSection("核心概念",note.summary);grammarSection("句型结构",note.structure);grammarSection("使用时机",note.usage);if(note.exampleSentence.isNotBlank())item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)){Column(Modifier.padding(16.dp)){Text("例句",fontWeight=FontWeight.Bold);Text(note.exampleSentence);if(note.exampleTranslation.isNotBlank())Text(note.exampleTranslation,color=MaterialTheme.colorScheme.onSurfaceVariant)}}};grammarSection("常见错误",note.commonMistakes);grammarSection("容易混淆",note.comparison);item{Spacer(Modifier.height(64.dp))}}}}
private fun androidx.compose.foundation.lazy.LazyListScope.grammarSection(title:String,content:String){if(content.isBlank())return;item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,fontWeight=FontWeight.Bold);content.lines().filter(String::isNotBlank).forEach{Text(it)}}}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarLearnScreen(
    note: GrammarNote,
    patterns: List<GrammarPattern>,
    examples: List<GrammarExample>,
    onProgress: (Int) -> Unit,
    onRatePattern: (Long, Boolean) -> Unit,
    onBack: () -> Unit,
    onQuiz: () -> Unit
) {
    data class LearnPage(val title: String, val primary: String, val secondary: String = "", val patternId: Long = 0)
    val pages = remember(note, patterns, examples) {
        buildList {
            add(LearnPage("核心概念", note.summary.ifBlank { "尚未填写核心概念" }, listOf(note.category, note.level).filter { it.isNotBlank() }.joinToString(" · ")))
            if (patterns.isEmpty()) {
                if (note.structure.isNotBlank()) add(LearnPage("主要句型", note.structure, note.usage))
                if (note.exampleSentence.isNotBlank()) add(LearnPage("例句", note.exampleSentence, note.exampleTranslation))
            } else patterns.forEachIndexed { index, pattern ->
                add(LearnPage(pattern.title.ifBlank { "句型 ${index + 1}" }, pattern.formula, listOf(pattern.meaning, pattern.usage).filter { it.isNotBlank() }.joinToString("\n\n"), pattern.id))
                examples.filter { it.grammarPatternId == pattern.id }.forEach { example ->
                    add(LearnPage("例句", example.sentence, example.translation, pattern.id))
                }
            }
            if (note.commonMistakes.isNotBlank()) add(LearnPage("常见错误", note.commonMistakes))
            if (note.comparison.isNotBlank()) add(LearnPage("容易混淆", note.comparison))
            add(LearnPage("学习完成", "你已看完这篇语法。可以前往测试，或返回学习区稍后再复习。"))
        }
    }
    var step by rememberSaveable(note.id, pages.size) { mutableIntStateOf(note.studyStep.coerceIn(0, pages.lastIndex)) }
    val page = pages[step]
    BackHandler { onProgress(step); onBack() }
    Scaffold(
        topBar = { TopAppBar(title = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onClick = { onProgress(step); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, actions = { Text("${step + 1}／${pages.size}", modifier = Modifier.padding(end = 16.dp)) }) },
        bottomBar = { Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedIconButton(enabled = step > 0, onClick = { step-- }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "上一个") }
            if (page.patternId > 0) {
                OutlinedButton(onClick = { onRatePattern(page.patternId, false); if (step < pages.lastIndex) { step++; onProgress(step) } }, modifier = Modifier.weight(1f)) { Text("还不熟") }
                Button(onClick = { onRatePattern(page.patternId, true); if (step < pages.lastIndex) { step++; onProgress(step) } }, modifier = Modifier.weight(1f)) { Text("看懂了") }
            } else {
                Button(onClick = { if (step < pages.lastIndex) { step++; onProgress(step) } else onQuiz() }, modifier = Modifier.weight(1f)) { Text(if (step < pages.lastIndex) "下一个" else "开始测试") }
            }
        } }
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp, vertical = 8.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(progress = { (step + 1f) / pages.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            Text(page.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(page.primary, style = if (page.title.startsWith("句型")) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)
                    if (page.secondary.isNotBlank()) { HorizontalDivider(); Text(page.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarQuizScreen(note: GrammarNote, questions: List<GrammarQuestion>, onAnswer: (Boolean, Boolean) -> Unit, onBack: () -> Unit, onRetry: () -> Unit) {
    val initial = remember(note.id, questions) { questions.sortedBy { it.sortOrder } }
    var queue by remember(note.id, questions) { mutableStateOf(initial) }
    var index by rememberSaveable(note.id) { mutableIntStateOf(0) }
    var selected by rememberSaveable(note.id) { mutableStateOf("") }
    var input by rememberSaveable(note.id) { mutableStateOf("") }
    var checked by rememberSaveable(note.id) { mutableStateOf(false) }
    var correct by rememberSaveable(note.id) { mutableStateOf(false) }
    var completed by rememberSaveable(note.id) { mutableStateOf(false) }
    var round by rememberSaveable(note.id) { mutableIntStateOf(1) }
    var optionSeed by rememberSaveable(note.id) { mutableIntStateOf(kotlin.random.Random.nextInt()) }
    val wrong = remember(note.id) { mutableStateListOf<GrammarQuestion>() }
    val q = queue.getOrNull(index)
    fun clear() { selected = ""; input = ""; checked = false; correct = false }
    fun restart() { queue = initial; index = 0; wrong.clear(); completed = false; round = 1; optionSeed = kotlin.random.Random.nextInt(); clear(); onRetry() }

    Scaffold(topBar = { TopAppBar(title = { Text(if (completed) "测试完成" else "${note.title}测试") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (completed) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary); Text("测试完成", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("所有题目都已答对") } }
                Button(onClick = ::restart, modifier = Modifier.fillMaxWidth()) { Text("再次测试") }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回语法") }
            } else if (q == null) Text("这篇语法还没有练习题。") else {
                val rawChoices = q.options.split(',', '、', '\n').map(String::trim).filter(String::isNotBlank).distinct().take(4)
                val choices = remember(q.id, optionSeed) { rawChoices.shuffled(kotlin.random.Random(optionSeed xor q.id.hashCode())) }
                val response = if (choices.isNotEmpty()) selected else input
                val accepted = (q.acceptedAnswers.split(',', '、') + q.answer).map { it.trim().lowercase() }.filter(String::isNotBlank).toSet()
                Text(if (round == 1) "第 ${index + 1}／${queue.size} 题" else "错题练习 · 第 ${index + 1}／${queue.size} 题", color = MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(progress = { (index + 1f) / queue.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(q.prompt.replace("{{answer}}", "____"), style = MaterialTheme.typography.headlineSmall); if (q.translation.isNotBlank()) Text(q.translation, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                if (choices.isNotEmpty()) choices.forEach { choice ->
                    ModernChoiceRow(text = choice, selected = selected == choice, enabled = !checked, onClick = { selected = choice }, label = ('A' + choices.indexOf(choice)).toString())
                } else OutlinedTextField(input, { input = it }, enabled = !checked, label = { Text("输入完整答案") }, textStyle = MaterialTheme.typography.titleMedium, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (checked) Card(colors = CardDefaults.cardColors(containerColor = if (correct) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(if (correct) "答对了" else "答错了", fontWeight = FontWeight.Bold); if (!correct) Text("正确答案：${q.answer}"); if (q.explanation.isNotBlank()) Text(q.explanation) } }
                Button(enabled = response.isNotBlank(), onClick = { if (!checked) { correct = response.trim().lowercase() in accepted; checked = true; onAnswer(correct, false); if (!correct && wrong.none { it.id == q.id }) wrong.add(q) } else if (!correct) clear() else if (index < queue.lastIndex) { index++; clear() } else if (wrong.isNotEmpty()) { queue = wrong.toList(); wrong.clear(); index = 0; round++; clear() } else completed = true }, modifier = Modifier.fillMaxWidth()) { Text(if (!checked) "确认答案" else if (!correct) "再答一次" else if (index == queue.lastIndex && wrong.isEmpty()) "完成" else "下一题") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarWritingCheckScreen(
    weaknesses: List<GrammarWeakness>,
    records: List<GrammarWritingRecord> = emptyList(),
    courses: List<String> = emptyList(),
    onCheck: suspend (String) -> List<GrammarWritingIssue>,
    onAccept: (GrammarWritingIssue) -> Unit,
    onSaveRecord: (String, String, String, String, List<GrammarWritingIssue>) -> Unit = { _, _, _, _, _ -> },
    onDeleteRecord: (GrammarWritingRecord) -> Unit = {},
    onRecognizeSources: (suspend (List<Uri>) -> GrammarWritingScanResult)? = null,
    onBack: () -> Unit = {},
    showTopBar: Boolean = true,
    initialRecordId: Long? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var originalText by rememberSaveable { mutableStateOf("") }
    var issues by remember { mutableStateOf<List<GrammarWritingIssue>>(emptyList()) }
    val accepted = remember { mutableStateListOf<String>() }
    val ignored = remember { mutableStateListOf<String>() }
    var showRevised by rememberSaveable { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var uncertainParts by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var recordTitle by rememberSaveable { mutableStateOf("") }
    var recordCourse by rememberSaveable { mutableStateOf(courses.firstOrNull() ?: "通用") }
    var courseMenu by remember { mutableStateOf(false) }

    LaunchedEffect(initialRecordId, records) {
        records.firstOrNull { it.id == initialRecordId }?.let { record ->
            recordTitle = record.title
            recordCourse = record.course
            originalText = record.originalText
            text = record.revisedText
            val saved = runCatching { JSONArray(record.issuesJson) }.getOrNull()
            issues = buildList {
                repeat(saved?.length() ?: 0) { index ->
                    val item = saved?.optJSONObject(index) ?: return@repeat
                    add(GrammarWritingIssue(
                        ruleKey = item.optString("ruleKey"), title = item.optString("title"),
                        originalSentence = item.optString("originalSentence"), correctedSentence = item.optString("correctedSentence"),
                        originalText = item.optString("originalText"), correctedText = item.optString("correctedText"),
                        explanation = item.optString("explanation")
                    ))
                }
            }
        }
    }
    if (showSaveDialog) AlertDialog(
        onDismissRequest = { showSaveDialog = false },
        title = { Text("保存作文纪录") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(recordTitle, { recordTitle = it }, label = { Text("作文标题") }, singleLine = true)
            ExposedDropdownMenuBox(expanded = courseMenu, onExpandedChange = { courseMenu = it }) {
                OutlinedTextField(recordCourse, {}, readOnly = true, label = { Text("课程") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(courseMenu) }, singleLine = true, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = courseMenu, onDismissRequest = { courseMenu = false }) {
                    (listOf("通用") + courses).distinct().forEach { course -> DropdownMenuItem(text = { Text(course) }, onClick = { recordCourse = course; courseMenu = false }) }
                }
            }
        } },
        confirmButton = { Button(enabled = recordTitle.isNotBlank(), onClick = { onSaveRecord(recordTitle, recordCourse, originalText.ifBlank { text }, text, issues); showSaveDialog = false }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("取消") } }
    )

    fun recognize(uris: List<Uri>) {
        val recognizer = onRecognizeSources ?: return
        if (uris.isEmpty()) return
        scope.launch {
            busyLabel = "正在识别作文…"; error = null
            runCatching { recognizer(uris) }
                .onSuccess { result ->
                    originalText = result.recognizedText
                    text = result.recognizedText
                    uncertainParts = result.uncertainParts
                    issues = emptyList()
                }
                .onFailure { error = it.message ?: "无法识别作文" }
            busyLabel = null
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents(), ::recognize)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ::recognize)
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pendingCameraUri?.let { recognize(listOf(it)) }
    }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            runCatching { GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.map { it.imageUri }.orEmpty() }
                .onSuccess(::recognize).onFailure { error = "无法读取扫描结果，请改用照片" }
        }
    }
    fun takePhoto() {
        val file = File(context.cacheDir, "grammar_writing_${System.currentTimeMillis()}.jpg")
        pendingCameraUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        camera.launch(pendingCameraUri!!)
    }
    fun scanDocument() {
        val activity = context.findActivity() ?: return
        val options = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(10)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
        runCatching { GmsDocumentScanning.getClient(options).getStartScanIntent(activity) }
            .onFailure { error = it.message ?: "此设备不支持扫描服务" }
            .getOrNull()
            ?.addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
            ?.addOnFailureListener { error = it.message ?: "无法打开扫描器" }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (showTopBar) TopAppBar(
                title = { Text("写作检查") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    enabled = text.isNotBlank() && busyLabel == null,
                    onClick = {
                        scope.launch {
                            busyLabel = "正在检查语法与用字…"; error = null
                            runCatching { onCheck(text) }.onSuccess { issues = it }
                                .onFailure { error = it.message ?: "检查失败" }
                            busyLabel = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)
                ) { Icon(Icons.Default.Spellcheck, null); Spacer(Modifier.width(6.dp)); Text("开始检查") }
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (onRecognizeSources != null) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = ::takePhoto) { Icon(Icons.Default.CameraAlt, "拍照") }
                    FilledTonalIconButton(onClick = ::scanDocument) { Icon(Icons.Default.DocumentScanner, "扫描") }
                    FilledTonalIconButton(onClick = { gallery.launch("image/*") }) { Icon(Icons.Default.PhotoLibrary, "照片") }
                    FilledTonalIconButton(onClick = { files.launch(arrayOf("image/*", "application/pdf", "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }) { Icon(Icons.Default.FileOpen, "文件") }
                }
            }
            busyLabel?.let { label -> item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Text(label) } } }
            if (uncertainParts.isNotEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text("有 ${uncertainParts.size} 处识别不确定，请先对照原图校正。", Modifier.padding(14.dp))
                }
            }
            item {
                Text("你的作文", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(recordTitle, { recordTitle = it }, label = { Text("作文标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                ExposedDropdownMenuBox(expanded = courseMenu, onExpandedChange = { courseMenu = it }) {
                    OutlinedTextField(recordCourse, {}, readOnly = true, label = { Text("课程") }, singleLine = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(courseMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = courseMenu, onDismissRequest = { courseMenu = false }) {
                        (listOf("通用") + courses).distinct().forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { recordCourse = name; courseMenu = false }) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(if (originalText.isBlank()) "英文作文" else "识别文字（请先校对）") },
                    minLines = 8, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)
                )
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (issues.isNotEmpty()) item {
                val grammarCount = issues.count { it.ruleKey.contains("grammar", true) || it.title.contains("语法") || it.title.contains("时态") }
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("检查完成", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("共 ${issues.size} 项建议 · 语法 ${grammarCount} · 其他 ${issues.size - grammarCount}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !showRevised, onClick = { showRevised = false }, label = { Text("原文") })
                            FilterChip(selected = showRevised, onClick = { showRevised = true }, label = { Text("目前修正版") })
                        }
                        if (showRevised) Text(text, style = MaterialTheme.typography.bodyMedium)
                        Text("AI 建议仍应由你确认；只有按下应用的内容会修改文字并创建薄薄弱点。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            onClick = {
                                if (recordTitle.isBlank()) recordTitle = "作文 ${records.size + 1}"
                                showSaveDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("保存作文纪录") }
                    }
                }
            }
            items(issues.filter { "${it.ruleKey}|${it.originalSentence}" !in ignored }, key = { "${it.ruleKey}|${it.originalSentence}" }) { issue ->
                val key = "${issue.ruleKey}|${issue.originalSentence}"
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text(issue.title) })
                        Text("原文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(issue.originalSentence, color = MaterialTheme.colorScheme.error)
                        Text("建议", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(issue.correctedSentence, color = MaterialTheme.colorScheme.primary)
                        Text(issue.explanation)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { ignored.add(key) }, modifier = Modifier.weight(1f)) { Text("略过") }
                            Button(
                                enabled = key !in accepted,
                                onClick = {
                                    text = text.replaceFirst(issue.originalSentence, issue.correctedSentence)
                                    accepted.add(key); onAccept(issue)
                                }, modifier = Modifier.weight(1f)
                            ) { Text(if (key in accepted) "已应用" else "应用并练习") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarQuizHubScreen(
    notes: List<GrammarNote>,
    questions: List<GrammarQuestion>,
    weaknesses: List<GrammarWeakness>,
    onStartTopicQuiz: (String, List<GrammarQuestion>) -> Unit,
    onGenerateAiTopicQuiz: (String) -> Unit,
    onWritingCheck: suspend (String) -> List<GrammarWritingIssue>,
    onAcceptWritingIssue: (GrammarWritingIssue) -> Unit,
    onOpenWritingCheck: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val filterPrefs = LocalContext.current.getSharedPreferences("grammar_page_filters", Context.MODE_PRIVATE)
    val courses = remember(notes) { notes.map { it.course }.filter(String::isNotBlank).distinct() }
    var selectedCourse by rememberSaveable { mutableStateOf(filterPrefs.getString("quiz_course", null)) }
    val folders = remember(notes, selectedCourse) {
        notes.filter { selectedCourse == null || it.course == selectedCourse }
            .map { it.folder }.filter(String::isNotBlank).distinct()
    }
    var selectedFolder by rememberSaveable { mutableStateOf(filterPrefs.getString("quiz_folder", null)) }
    var folderMenu by remember { mutableStateOf(false) }
    LaunchedEffect(selectedCourse, selectedFolder) { filterPrefs.edit().apply {
        if (selectedCourse == null) remove("quiz_course") else putString("quiz_course", selectedCourse)
        if (selectedFolder == null) remove("quiz_folder") else putString("quiz_folder", selectedFolder)
    }.apply() }
    LaunchedEffect(selectedCourse) { if (selectedFolder !in folders) selectedFolder = null }
    val selectedNotes = notes.filter { (selectedCourse == null || it.course == selectedCourse) && (selectedFolder == null || it.folder == selectedFolder) }
    val selectedIds = selectedNotes.map { it.id }.toSet()
    val selectedQuestions = questions.filter { it.grammarNoteId in selectedIds }
    LazyColumn(
        modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GrammarCourseChips(notes, selectedCourse) { selectedCourse = it; selectedFolder = null }
            GrammarFilterMenu("文件夹", selectedFolder ?: "全部文件夹", folders, folderMenu, { folderMenu = it }, { selectedFolder = it }, Modifier.fillMaxWidth())
        } }
        item {
            Button(
                enabled = selectedQuestions.isNotEmpty(),
                onClick = { onStartTopicQuiz("混合测试", selectedQuestions) }, modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Default.Quiz, null); Spacer(Modifier.width(6.dp)); Text("测试全部 (${selectedQuestions.size})") }
        }
        item { Text("选择语法类型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (selectedQuestions.isEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("目前范围还没有测试题", fontWeight = FontWeight.Bold)
                    TextButton(onClick = { onGenerateAiTopicQuiz(selectedFolder ?: selectedCourse ?: "综合语法") }) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(4.dp)); Text("AI 创建题目") }
                }
            }
        }
        items(selectedNotes.filter { note -> selectedQuestions.any { it.grammarNoteId == note.id } }, key = { it.id }) { note ->
            val noteQuestions = selectedQuestions.filter { it.grammarNoteId == note.id }
            Card(onClick = { onStartTopicQuiz(note.title, noteQuestions) }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(note.title, fontWeight = FontWeight.Bold); Text("${note.category} · ${noteQuestions.size} 个句子／题目", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.Default.ChevronRight, "开始")
                }
            }
        }
        item {
            Card(onClick = onOpenWritingCheck, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Spellcheck, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("写作检查", fontWeight = FontWeight.Bold); Text("输入、拍照或扫描作文", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (weaknesses.isNotEmpty()) Text("${weaknesses.size} 个薄薄弱点", color = MaterialTheme.colorScheme.error)
                    Icon(Icons.Default.ChevronRight, "打开")
                }
            }
        }
    }
}

private tailrec fun Context.findActivity():Activity?=when(this){is Activity->this;is ContextWrapper->baseContext.findActivity();else->null}
