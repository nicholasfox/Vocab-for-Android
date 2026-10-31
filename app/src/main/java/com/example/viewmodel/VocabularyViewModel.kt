package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.VocabularyRepository
import com.example.data.dictionary.DictionaryEngine
import com.example.data.dictionary.DictionaryEntry
import com.example.data.dictionary.EcdictLookupService
import com.example.data.dictionary.GoogleTranslateLookupService
import com.example.data.dictionary.FreeDictionaryLookupService
import com.example.data.dictionary.CambridgeDictionaryLookupService
import com.example.data.dictionary.WiktionaryLookupService
import com.example.data.dictionary.MyMemoryTranslateLookupService
import com.example.data.entity.Deck
import com.example.data.entity.Flashcard
import com.example.data.entity.StudyLog
import com.example.data.entity.GrammarDraft
import com.example.data.entity.GrammarNote
import com.example.data.entity.GrammarQuestion
import com.example.data.entity.GrammarQuestionDraft
import com.example.data.entity.GrammarWeakness
import com.example.data.entity.GrammarWritingIssue
import com.example.data.entity.GrammarWritingScanResult
import com.example.data.entity.GrammarWritingRecord
import com.example.data.entity.GrammarPattern
import com.example.data.entity.GrammarPatternDraft
import com.example.data.entity.GrammarExample
import com.example.data.entity.GrammarExampleDraft
import com.example.data.entity.GrammarLibrary
import com.example.data.importer.ExternalCardCandidate
import com.example.data.importer.AiExternalFormattingResult
import com.example.data.learning.LearningSessionStore
import com.example.data.learning.StudyCheckInStore
import com.example.data.settings.AppSettings
import com.example.data.settings.ReminderTime
import com.example.data.settings.SettingsRepository
import com.example.data.pronunciation.PronunciationPracticeStore
import com.example.util.DocumentParser
import com.example.util.AiImagePreprocessor
import androidx.room.withTransaction
import com.example.util.OcrCardCandidate
import com.example.widgets.VocabWidgetProvider
import com.example.util.OcrWordParser
import com.example.util.TtsManager
import com.example.util.SpeechSegment
import com.example.notifications.StudyReminderScheduler
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

import com.example.data.api.GeminiService
import com.example.data.api.AiCredentialsStore
import com.example.data.api.AiApiProfile
import com.example.data.api.AiProvider
import com.example.data.api.DirectAiService
import com.example.data.api.PersonalAiConfig
import com.example.data.backup.BackupService
import com.example.data.assistant.AssistantChatStore
import com.example.data.assistant.AssistantConversation
import com.example.data.assistant.AssistantMessage
import com.example.data.assistant.AssistantPendingAction
import com.example.data.assistant.savedArticles
import com.example.data.assistant.applyAssistantCardUpdate
import com.example.data.assistant.assistantEditableFields
import com.example.data.assistant.createAssistantGrammar
import com.example.data.assistant.assistantGrammarSnapshot
import com.example.data.assistant.undoAssistantGrammarCreation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Loading : ImportUiState
    data class Success(val itemCount: Int) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

data class AssistantSendOutcome(
    val id: Long = System.nanoTime(),
    val succeeded: Boolean,
    val errorMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repository = VocabularyRepository(db, db.deckDao(), db.flashcardDao(), db.studyLogDao())
    private val settingsRepository = SettingsRepository(application)
    private val backupService = BackupService(application, db)
    private val aiCredentialsStore = AiCredentialsStore(application)
    private val learningSessionStore = LearningSessionStore(application)
    private val studyCheckInStore = StudyCheckInStore(application)
    private val assistantChatStore = AssistantChatStore(application)
    private val pronunciationPracticeStore = PronunciationPracticeStore(application)
    private val cardSaveMutex = Mutex()
    val ttsManager = TtsManager(application)
    val ttsVoices = ttsManager.voices

    private val currentTime = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    val allDecks: StateFlow<List<Deck>> = repository.allDecks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allCards: StateFlow<List<Flashcard>> = repository.allCards.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val allGrammarNotes: StateFlow<List<GrammarNote>> = db.grammarNoteDao().getAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val grammarLibraries: StateFlow<List<GrammarLibrary>> = db.grammarLibraryDao().getAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val allGrammarQuestions: StateFlow<List<GrammarQuestion>> = db.grammarQuestionDao().getAllQuestions().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val allGrammarPatterns: StateFlow<List<GrammarPattern>> = db.grammarContentDao().getAllPatterns().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val allGrammarExamples: StateFlow<List<GrammarExample>> = db.grammarContentDao().getAllExamples().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val grammarWeaknesses: StateFlow<List<GrammarWeakness>> = db.grammarQuestionDao().getAllWeaknesses().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val grammarWritingRecords: StateFlow<List<GrammarWritingRecord>> = db.grammarWritingDao().getAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val pronunciationWeakCardIds: StateFlow<Set<Long>> = pronunciationPracticeStore.weakCardIds
    val studyCheckInDates = studyCheckInStore.dates

    val dueCards: StateFlow<List<Flashcard>> = currentTime.flatMapLatest {
        repository.getDueCards(it)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val totalCardCount: StateFlow<Int> = repository.totalCardCount.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0
    )

    val masteredCardCount: StateFlow<Int> = repository.masteredCardCount.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0
    )

    val dueCardCount: StateFlow<Int> = currentTime.flatMapLatest {
        repository.getDueCardCount(it)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0
    )

    val recentStudyLogs: StateFlow<List<StudyLog>> = repository.getAllLogs().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Selection & UI Filters
    private val _selectedDeckId = MutableStateFlow<Long?>(null)
    val selectedDeckId: StateFlow<Long?> = _selectedDeckId.asStateFlow()

    // OCR candidates state
    private val _ocrCandidates = MutableStateFlow<List<OcrCardCandidate>>(emptyList())
    val ocrCandidates: StateFlow<List<OcrCardCandidate>> = _ocrCandidates.asStateFlow()

    private val _importUiState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importUiState: StateFlow<ImportUiState> = _importUiState.asStateFlow()

    private val _dataMessage = MutableStateFlow<String?>(null)
    val dataMessage: StateFlow<String?> = _dataMessage.asStateFlow()

    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    private val _apiKeyConfigured = MutableStateFlow(aiCredentialsStore.hasApiKey())
    val apiKeyConfigured: StateFlow<Boolean> = _apiKeyConfigured.asStateFlow()

    private val _aiApiProfiles = MutableStateFlow<List<AiApiProfile>>(emptyList())
    val aiApiProfiles: StateFlow<List<AiApiProfile>> = _aiApiProfiles.asStateFlow()

    private val _availableAiModels = MutableStateFlow<List<String>>(emptyList())
    val availableAiModels: StateFlow<List<String>> = _availableAiModels.asStateFlow()

    private val _aiConnectionStatus = MutableStateFlow<String?>(null)
    val aiConnectionStatus: StateFlow<String?> = _aiConnectionStatus.asStateFlow()

    private val _assistantConversations = MutableStateFlow(assistantChatStore.loadConversations())
    val assistantConversations: StateFlow<List<AssistantConversation>> = _assistantConversations.asStateFlow()
    private val assistantAllMessages = assistantChatStore.loadMessages().toMutableList()
    private val _savedArticles = MutableStateFlow(assistantAllMessages.savedArticles())
    val savedArticles: StateFlow<List<AssistantMessage>> = _savedArticles.asStateFlow()
    private val _assistantMessages = MutableStateFlow<List<AssistantMessage>>(emptyList())
    val assistantMessages: StateFlow<List<AssistantMessage>> = _assistantMessages.asStateFlow()
    private val _assistantCurrentConversationId = MutableStateFlow<String?>(null)
    val assistantCurrentConversationId: StateFlow<String?> = _assistantCurrentConversationId.asStateFlow()
    private val _assistantBusy = MutableStateFlow(false)
    val assistantBusy: StateFlow<Boolean> = _assistantBusy.asStateFlow()
    private val _assistantStatus = MutableStateFlow<String?>(null)
    val assistantStatus: StateFlow<String?> = _assistantStatus.asStateFlow()
    private val _assistantSendOutcome = MutableStateFlow<AssistantSendOutcome?>(null)
    val assistantSendOutcome: StateFlow<AssistantSendOutcome?> = _assistantSendOutcome.asStateFlow()
    private val _assistantPendingAction = MutableStateFlow<AssistantPendingAction?>(null)
    val assistantPendingAction: StateFlow<AssistantPendingAction?> = _assistantPendingAction.asStateFlow()
    private val _assistantUndoAction = MutableStateFlow<AssistantPendingAction?>(null)
    val assistantUndoAction: StateFlow<AssistantPendingAction?> = _assistantUndoAction.asStateFlow()
    private val _assistantSelectedDeckIds = MutableStateFlow<Set<Long>>(emptySet())
    val assistantSelectedDeckIds: StateFlow<Set<Long>> = _assistantSelectedDeckIds.asStateFlow()
    private var assistantContextDeckId: Long? = null
    private var assistantRequestJob: Job? = null
    private var assistantRequestGeneration=0L

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .onEach {
            _settingsLoaded.value = true
            configurePersonalAi(it)
        }
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppSettings()
    )

    init {
        viewModelScope.launch {
            DirectAiService.usage.collect {
                refreshAiProfiles(settings.value)
            }
        }
        viewModelScope.launch {
            val savedSettings = settingsRepository.settings.first()
            if (savedSettings.usePersonalAiApi) {
                personalAiConfigs(savedSettings).forEach { config ->
                    runCatching { DirectAiService.refreshUsage(config) }
                }
                refreshAiProfiles(savedSettings)
            }
            if (!savedSettings.dataInitialized) {
                settingsRepository.markDataInitialized()
            }
        }
    }

    fun selectDeck(deckId: Long?) {
        _selectedDeckId.value = deckId
    }

    fun saveGrammarNote(note: GrammarNote, onComplete: (Long) -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                val normalized = note.copy(
                    course = note.course.trim().ifBlank { "通用" },
                    title = note.title.trim(),
                    updatedAt = System.currentTimeMillis()
                )
                require(normalized.title.isNotBlank()) { "请输入语法名称" }
                if (normalized.questionTemplate.isNotBlank()) {
                    require("{{answer}}" in normalized.questionTemplate) { "练习题必须用 {{answer}} 标记挖空位置" }
                    require(normalized.answer.isNotBlank()) { "练习题必须填写正确答案" }
                    val choices = normalized.options.split(',', '、', '\n')
                        .map { it.trim().lowercase() }
                        .filter(String::isNotBlank)
                    require(choices.isEmpty() || normalized.answer.trim().lowercase() in choices) {
                        "选项必须包含正确答案"
                    }
                }
                if (normalized.id == 0L) db.grammarNoteDao().insert(normalized)
                else {
                    db.grammarNoteDao().update(normalized)
                    normalized.id
                }
            }.onSuccess(onComplete).onFailure {
                _dataMessage.value = "保存语法失败：${it.message ?: "未知错误"}"
            }
        }
    }

    fun saveGrammarNoteBundle(
        note: GrammarNote,
        questions: List<GrammarQuestionDraft>,
        patterns: List<GrammarPatternDraft> = emptyList(),
        onComplete: (Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val normalized = note.copy(
                        course = note.course.trim().ifBlank { "通用" },
                        folder = note.folder.trim().ifBlank { "未分类" },
                        title = note.title.trim(),
                        updatedAt = System.currentTimeMillis()
                    )
                    require(normalized.title.isNotBlank()) { "请输入语法名称" }
                    if (db.grammarLibraryDao().getAllOnce().none {
                            it.course.equals(normalized.course, true) && it.name.equals(normalized.folder, true)
                        }) {
                        db.grammarLibraryDao().insert(
                            GrammarLibrary(course = normalized.course, name = normalized.folder)
                        )
                    }
                    questions.forEach(::validateGrammarQuestion)
                    val noteId = if (normalized.id == 0L) db.grammarNoteDao().insert(normalized)
                    else {
                        db.grammarNoteDao().update(normalized)
                        normalized.id
                    }
                    val previousPatterns = db.grammarContentDao().getPatternsForNote(noteId)
                    db.grammarQuestionDao().deleteForNote(noteId)
                    db.grammarContentDao().deleteExamplesForNote(noteId)
                    db.grammarContentDao().deletePatternsForNote(noteId)
                    val effectivePatterns = patterns.ifEmpty { normalized.toLegacyPatternDrafts() }
                    val patternIds = effectivePatterns.mapIndexed { patternIndex, pattern ->
                        val previous = previousPatterns.firstOrNull { it.formula.trim().equals(pattern.formula.trim(), true) }
                        val patternId = db.grammarContentDao().insertPattern(
                            GrammarPattern(
                                grammarNoteId = noteId,
                                title = pattern.title.trim().ifBlank { "句型 ${patternIndex + 1}" },
                                formula = pattern.formula.trim(), meaning = pattern.meaning.trim(),
                                usage = pattern.usage.trim(), notes = pattern.notes.trim(),
                                masteryPercent = previous?.masteryPercent ?: 0,
                                nextReviewAt = previous?.nextReviewAt ?: System.currentTimeMillis(),
                                attemptCount = previous?.attemptCount ?: 0,
                                correctCount = previous?.correctCount ?: 0,
                                sortOrder = patternIndex.toLong()
                            )
                        )
                        pattern.examples.filter { it.sentence.isNotBlank() }.forEachIndexed { exampleIndex, example ->
                            db.grammarContentDao().insertExample(
                                GrammarExample(
                                    grammarNoteId = noteId, grammarPatternId = patternId,
                                    sentence = example.sentence.trim(), translation = example.translation.trim(),
                                    highlightedText = example.highlightedText.trim(), sortOrder = exampleIndex.toLong()
                                )
                            )
                        }
                        patternId
                    }
                    questions.forEachIndexed { index, draft ->
                        db.grammarQuestionDao().insertQuestion(
                            GrammarQuestion(
                                grammarNoteId = noteId,
                                grammarPatternId = patternIds.getOrNull(draft.grammarPatternIndex) ?: 0,
                                type = draft.type,
                                prompt = draft.prompt.trim(),
                                translation = draft.translation.trim(),
                                answer = draft.answer.trim(),
                                acceptedAnswers = draft.acceptedAnswers.trim(),
                                options = draft.options.trim(),
                                explanation = draft.explanation.trim(),
                                difficulty = draft.difficulty.coerceIn(1, 3),
                                sourceType = draft.sourceType,
                                sortOrder = index.toLong()
                            )
                        )
                    }
                    noteId
                }
            }.onSuccess(onComplete).onFailure {
                _dataMessage.value = "保存语法失败：${it.message ?: "未知错误"}"
            }
        }
    }

    fun saveGrammarDrafts(
        drafts: List<GrammarDraft>,
        course: String,
        duplicateMode: String,
        onComplete: (List<Long>) -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val savedIds = mutableListOf<Long>()
                    drafts.filter { it.title.isNotBlank() }.forEach { draft ->
                        draft.questions.forEach(::validateGrammarQuestion)
                        val normalizedCourse = course.trim().ifBlank { "通用" }
                        if (db.grammarLibraryDao().getAllOnce().none {
                                it.course.equals(normalizedCourse, true) && it.name.equals("未分类", true)
                            }) {
                            db.grammarLibraryDao().insert(GrammarLibrary(course = normalizedCourse, name = "未分类"))
                        }
                        val existing = db.grammarNoteDao().getAllOnce().firstOrNull {
                            it.course.equals(normalizedCourse, true) && it.title.equals(draft.title.trim(), true)
                        }
                        if (existing != null && duplicateMode == "SKIP") return@forEach
                        val title = if (existing != null && duplicateMode == "COPY") {
                            var index = 2
                            var candidate: String
                            val names = db.grammarNoteDao().getAllOnce().map { it.title.lowercase() }.toSet()
                            do { candidate = "${draft.title.trim()} ($index)"; index++ } while (candidate.lowercase() in names)
                            candidate
                        } else draft.title.trim()
                        val legacy = draft.questions.firstOrNull()
                        val note = GrammarNote(
                            id = if (existing != null && duplicateMode == "OVERWRITE") existing.id else 0,
                            course = normalizedCourse, title = title, category = draft.category,
                            level = draft.level, summary = draft.summary,
                            structure = draft.structure, usage = draft.usage,
                            exampleSentence = draft.exampleSentence, exampleTranslation = draft.exampleTranslation,
                            commonMistakes = draft.commonMistakes, comparison = draft.comparison,
                            tags = draft.tags, sourceType = "IMPORT",
                            questionTemplate = legacy?.prompt.orEmpty(), answer = legacy?.answer.orEmpty(),
                            acceptedAnswers = legacy?.acceptedAnswers.orEmpty(), options = legacy?.options.orEmpty(),
                            explanation = legacy?.explanation.orEmpty(),
                            favorite = existing?.favorite ?: false,
                            masteryPercent = if (duplicateMode == "OVERWRITE") existing?.masteryPercent ?: 0 else 0,
                            nextReviewAt = existing?.nextReviewAt ?: System.currentTimeMillis(),
                            studyStep = existing?.studyStep ?: 0,
                            attemptCount = existing?.attemptCount ?: 0,
                            correctCount = existing?.correctCount ?: 0,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis()
                        )
                        val noteId = if (note.id == 0L) db.grammarNoteDao().insert(note)
                        else { db.grammarNoteDao().update(note.copy(updatedAt = System.currentTimeMillis())); note.id }
                        val previousPatterns = db.grammarContentDao().getPatternsForNote(noteId)
                        db.grammarQuestionDao().deleteForNote(noteId)
                        db.grammarContentDao().deleteExamplesForNote(noteId)
                        db.grammarContentDao().deletePatternsForNote(noteId)
                        val effectivePatterns = draft.patterns.ifEmpty { note.toLegacyPatternDrafts() }
                        val patternIds = effectivePatterns.mapIndexed { patternIndex, pattern ->
                            val previous = previousPatterns.firstOrNull { it.formula.trim().equals(pattern.formula.trim(), true) }
                            val patternId = db.grammarContentDao().insertPattern(GrammarPattern(
                                grammarNoteId = noteId, title = pattern.title.ifBlank { "句型 ${patternIndex + 1}" },
                                formula = pattern.formula, meaning = pattern.meaning, usage = pattern.usage,
                                notes = pattern.notes,
                                masteryPercent = previous?.masteryPercent ?: 0,
                                nextReviewAt = previous?.nextReviewAt ?: System.currentTimeMillis(),
                                attemptCount = previous?.attemptCount ?: 0,
                                correctCount = previous?.correctCount ?: 0,
                                sortOrder = patternIndex.toLong()
                            ))
                            pattern.examples.filter { it.sentence.isNotBlank() }.forEachIndexed { exampleIndex, example ->
                                db.grammarContentDao().insertExample(GrammarExample(
                                    grammarNoteId = noteId, grammarPatternId = patternId,
                                    sentence = example.sentence, translation = example.translation,
                                    highlightedText = example.highlightedText, sortOrder = exampleIndex.toLong()
                                ))
                            }
                            patternId
                        }
                        draft.questions.forEachIndexed { index, q ->
                            db.grammarQuestionDao().insertQuestion(GrammarQuestion(
                                grammarNoteId = noteId,
                                grammarPatternId = patternIds.getOrNull(q.grammarPatternIndex) ?: 0,
                                type = q.type, prompt = q.prompt.trim(),
                                translation = q.translation.trim(), answer = q.answer.trim(),
                                acceptedAnswers = q.acceptedAnswers.trim(), options = q.options.trim(),
                                explanation = q.explanation.trim(), difficulty = q.difficulty.coerceIn(1, 3),
                                sourceType = q.sourceType, sortOrder = index.toLong()
                            ))
                        }
                        savedIds += noteId
                    }
                    savedIds
                }
            }.onSuccess(onComplete).onFailure {
                _dataMessage.value = "导入语法失败：${it.message ?: "未知错误"}"
            }
        }
    }

    private fun validateGrammarQuestion(question: GrammarQuestionDraft) {
        require(question.prompt.isNotBlank()) { "题目内容不能空白" }
        require("{{answer}}" in question.prompt) { "每题必须用 {{answer}} 标记唯一挖空位置" }
        require(question.prompt.windowed("{{answer}}".length).count { it == "{{answer}}" } == 1) {
            "每题只能有一个答案位置"
        }
        require(question.answer.isNotBlank()) { "每题都必须填写正确答案" }
        val choices = question.options.split(',', '、', '\n').map { it.trim().lowercase() }.filter(String::isNotBlank)
        require(choices.isEmpty() || question.answer.trim().lowercase() in choices) { "题目选项必须包含正确答案" }
        require(choices.size == choices.distinct().size) { "题目选项不能重复" }
    }

    private fun GrammarNote.toLegacyPatternDrafts(): List<GrammarPatternDraft> {
        if (structure.isBlank() && exampleSentence.isBlank()) return emptyList()
        val formulas = structure.lines().map(String::trim).filter(String::isNotBlank)
            .ifEmpty { listOf("主要句型") }
        return formulas.mapIndexed { index, formula ->
            GrammarPatternDraft(
                title = if (formulas.size == 1) "主要句型" else "句型 ${index + 1}",
                formula = formula,
                meaning = if (index == 0) summary else "",
                usage = if (index == 0) usage else "",
                examples = if (index == 0 && exampleSentence.isNotBlank()) listOf(
                    GrammarExampleDraft(exampleSentence, exampleTranslation)
                ) else emptyList()
            )
        }
    }

    fun deleteGrammarNote(note: GrammarNote, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    db.grammarQuestionDao().deleteForNote(note.id)
                    db.grammarQuestionDao().deleteWeaknessesForNote(note.id)
                    db.grammarContentDao().deleteExamplesForNote(note.id)
                    db.grammarContentDao().deletePatternsForNote(note.id)
                    db.grammarNoteDao().delete(note)
                }
            }
                .onSuccess { onComplete() }
                .onFailure { _dataMessage.value = "删除语法失败：${it.message ?: "未知错误"}" }
        }
    }

    fun toggleGrammarFavorite(note: GrammarNote) {
        viewModelScope.launch {
            db.grammarNoteDao().getById(note.id)?.let { current ->
                db.grammarNoteDao().update(current.copy(favorite = !current.favorite, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    fun updateGrammarStudyStep(note: GrammarNote, step: Int) {
        viewModelScope.launch {
            db.grammarNoteDao().getById(note.id)?.let { current ->
                db.grammarNoteDao().update(current.copy(studyStep = step.coerceAtLeast(0), updatedAt = System.currentTimeMillis()))
            }
        }
    }

    fun recordGrammarAnswer(note: GrammarNote, correct: Boolean, usedHint: Boolean = false) {
        viewModelScope.launch {
            val current = db.grammarNoteDao().getById(note.id) ?: return@launch
            val now = System.currentTimeMillis()
            val gain = when {
                !correct -> -8
                usedHint -> 3
                else -> 8
            }
            val nextMastery = (current.masteryPercent + gain).coerceIn(0, 100)
            val delayDays = when {
                !correct -> 1L
                nextMastery < 40 -> 2L
                nextMastery < 70 -> 4L
                nextMastery < 90 -> 7L
                else -> 14L
            }
            db.grammarNoteDao().update(
                current.copy(
                    masteryPercent = nextMastery,
                    nextReviewAt = now + delayDays * 86_400_000L,
                    attemptCount = current.attemptCount + 1,
                    correctCount = current.correctCount + if (correct) 1 else 0,
                    studyStep = if (correct) 4 else current.studyStep,
                    updatedAt = now
                )
            )
        }
    }

    fun recordGrammarPatternResult(patternId: Long, correct: Boolean, usedHint: Boolean = false) {
        if (patternId <= 0) return
        viewModelScope.launch {
            val pattern = db.grammarContentDao().getPatternById(patternId) ?: return@launch
            val now = System.currentTimeMillis()
            val gain = when { !correct -> -10; usedHint -> 3; else -> 10 }
            val mastery = (pattern.masteryPercent + gain).coerceIn(0, 100)
            val days = when { !correct -> 1L; mastery < 40 -> 2L; mastery < 70 -> 4L; mastery < 90 -> 7L; else -> 14L }
            db.grammarContentDao().updatePattern(pattern.copy(
                masteryPercent = mastery,
                nextReviewAt = now + days * 86_400_000L,
                attemptCount = pattern.attemptCount + 1,
                correctCount = pattern.correctCount + if (correct) 1 else 0
            ))
        }
    }

    suspend fun generateGrammarDraft(request: String): GrammarDraft {
        require(settings.value.aiEnabled) { "请先在设置中启用 AI" }
        val result = GeminiService.runAssistant(
            """
            你是严谨的英文语法教材编辑。请根据用户要求创建一篇简体中文语法笔记。
            一篇笔记代表一个可独立学习与测试的「语法类型」；同一类型可包含多组句型，每组句型再包含多个例句。
            只返回一个 JSON 对象，不要 Markdown。字段：
            title, category, level, summary, commonMistakes, comparison, tags、patterns 与 questions。
            patterns 每组包含 title, formula, meaning, usage, notes, examples；examples 每笔包含 sentence, translation, highlightedText。
            每个独立句型必须分成一组 pattern，不可把全部公式塞进同一字符串。
            structure、usage、commonMistakes、comparison 可用换行分隔；tags、acceptedAnswers、options 用逗号分隔。
            questions 每题格式：grammarPatternIndex, type, prompt, translation, answer, acceptedAnswers, options, explanation, difficulty。
            prompt 必须用 {{answer}} 标记唯一挖空位置，答案填回后必须成为自然完整的英文句子。
            options 必须包含 answer、不得重复，最多四个，且正确答案不可固定放第一个。
            全篇合计必须刚好 6 个例句与 10 题。前 2 题是基本概念选择题，其余 8 题以句子应用为主，包含动词形式（V-ing、to V 等）、挖空、改错、翻译或重组；grammarPatternIndex 必须正确指向所属句型。
            不确定的内容留空，不可捏造来源。

            用户要求：${request.trim()}
            """.trimIndent()
        )
        return parseGrammarDraft(result)
    }

    suspend fun generateGrammarDraftFromText(text: String, mode: String): GrammarDraft {
        require(text.isNotBlank()) { "请先贴上语法内容" }
        val rule = when (mode) {
            "ENRICH" -> "保留原文内容，并补充自然例句、常见错误与 6～12 题练习。"
            "ORGANIZE" -> "不可改变原意，将内容整理成结构清楚的笔记与练习题。"
            else -> "只能提取文字中确实存在的内容，不得补充或猜测；原文没有题目就不要创建题目。"
        }
        return generateGrammarDraft("$rule\n\n待导入内容：\n${text.trim()}")
    }

    suspend fun readGrammarShare(uri: Uri): List<GrammarDraft> {
        val text = getApplication<Application>().contentResolver.openInputStream(uri)
            ?.bufferedReader()?.use { it.readText() } ?: error("无法读取分享档")
        val root = JSONObject(text)
        require(root.optString("format") == "vocabshare") { "不是有效的 .vocabshare 文件" }
        val items = root.optJSONArray("grammar") ?: root.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val questionsJson = item.optJSONArray("questions") ?: JSONArray()
                val patternsJson = item.optJSONArray("patterns") ?: JSONArray()
                val patterns = buildList {
                    for (patternIndex in 0 until patternsJson.length()) {
                        val pattern = patternsJson.optJSONObject(patternIndex) ?: continue
                        val examplesJson = pattern.optJSONArray("examples") ?: JSONArray()
                        val examples = buildList {
                            for (exampleIndex in 0 until examplesJson.length()) {
                                val example = examplesJson.optJSONObject(exampleIndex) ?: continue
                                val sentence = example.optString("sentence").trim()
                                if (sentence.isNotBlank()) add(GrammarExampleDraft(sentence, example.optString("translation"), example.optString("highlightedText")))
                            }
                        }
                        val formula = pattern.optString("formula").trim()
                        if (formula.isNotBlank()) add(GrammarPatternDraft(
                            title = pattern.optString("title", "句型 ${patternIndex + 1}"), formula = formula,
                            meaning = pattern.optString("meaning"), usage = pattern.optString("usage"),
                            notes = pattern.optString("notes"), examples = examples
                        ))
                    }
                }
                val questions = buildList {
                    for (qIndex in 0 until questionsJson.length()) {
                        val q = questionsJson.optJSONObject(qIndex) ?: continue
                        val draft = GrammarQuestionDraft(
                            grammarPatternIndex = q.optInt("grammarPatternIndex", -1),
                            type = q.optString("type", "CLOZE_CHOICE"), prompt = q.optString("prompt"),
                            translation = q.optString("translation"), answer = q.optString("answer"),
                            acceptedAnswers = q.optString("acceptedAnswers"), options = q.optString("options"),
                            explanation = q.optString("explanation"), difficulty = q.optInt("difficulty", 1).coerceIn(1, 3),
                            sourceType = "SHARE"
                        )
                        if (draft.prompt.isNotBlank() && draft.answer.isNotBlank()) add(draft)
                    }
                }
                val draft = GrammarDraft(
                    title = item.optString("title"), category = item.optString("category", "其他"),
                    level = item.optString("level", "未分级"), summary = item.optString("summary"),
                    structure = item.optString("structure"), usage = item.optString("usage"),
                    exampleSentence = item.optString("exampleSentence"), exampleTranslation = item.optString("exampleTranslation"),
                    commonMistakes = item.optString("commonMistakes"), comparison = item.optString("comparison"),
                    tags = item.optString("tags"), patterns = patterns, questions = questions
                )
                if (draft.title.isNotBlank()) add(draft)
            }
        }.also { require(it.isNotEmpty()) { "分享档没有可导入的语法" } }
    }

    suspend fun generateGrammarDraftFromImages(uris: List<Uri>): GrammarDraft = generateGrammarDraftsFromSources(uris, "ENRICH").first()

    suspend fun generateGrammarDraftFromSources(uris: List<Uri>, mode: String = "FAITHFUL"): GrammarDraft =
        generateGrammarDraftsFromSources(uris, mode).first()

    suspend fun generateGrammarDraftsFromSources(uris: List<Uri>, mode: String = "ENRICH"): List<GrammarDraft> {
        require(settings.value.aiEnabled) { "请先在设置中启用 AI" }
        require(uris.isNotEmpty()) { "请先选择图片或文件" }
        val context = getApplication<Application>()
        val payloads = buildList {
            uris.take(5).forEach { uri ->
                val mime = context.contentResolver.getType(uri).orEmpty()
                if (mime.contains("pdf", true)) addAll(DocumentParser.renderPdfPagesForAi(context, uri, 5))
                else if (mime.startsWith("image/")) add(AiImagePreprocessor.prepare(context, uri))
            }
        }
        val extractedText = uris.take(5).mapNotNull { uri ->
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("image/") || mime.contains("pdf", true)) null
            else runCatching { DocumentParser.extractTextFromUri(context, uri, settings.value.pdfPageLimit) }.getOrNull()
        }.filter(String::isNotBlank).joinToString("\n\n")
        require(payloads.isNotEmpty() || extractedText.isNotBlank()) { "文件没有可识别的内容" }
        val result = GeminiService.runAssistant(
            """
            你是严谨的英文语法教材图片识别与整理助手。先忠实读取图片或文件，再补齐成可学习的语法数据。
            数据阶层固定为：课程由 App 另行选择；每篇 note 是一个语法类型；类型内有多组 patterns；每组 pattern 有多个 examples；questions 必须涵盖这个类型的每一个例句。
            依照图片由上到下、由左到右整理。不同且可独立学习的语法观念必须拆成不同笔记。
            只返回 JSON 对象 {"notes":[...]}，不要 Markdown。每篇字段：title, category, level, summary, commonMistakes, comparison, tags, patterns, questions。
            patterns 每组包含 title, formula, meaning, usage, notes, examples；examples 包含 sentence, translation, highlightedText。
            questions 每题包含 grammarPatternIndex, type, prompt, translation, answer, acceptedAnswers, options, explanation, difficulty；题目中以 {{answer}} 标记唯一答案位置。
            教材已有的规则、句型与例句必须优先保留；看不清楚的内容留空，不可猜测原文。
            每篇完成稿必须补齐为刚好 6 个例句与 10 题。前 2 题为基本概念选择题，后 8 题为应用题，依内容加入 V-ing、to V、时态、介系词挖空、改错、翻译或重组。options 必须包含答案但不可固定把答案放第一个。
            识别模式：${when (mode) {
                "ENRICH" -> "AI 补充：忠实保留原文，补齐自然例句、常见错误与练习题。每个例句至少要有一题，题目需涵盖整个语法类型；补充题 sourceType 设为 AI。"
                "ORGANIZE" -> "AI 整理：不可改变原意，可重整段落与字段，并依原文创建题目。"
                else -> "AI 补齐：先忠实保留文件内容，再补齐缺少的句型、例句、补充说明与题目。"
            }}
            """.trimIndent() + if (extractedText.isBlank()) "" else "\n\n文件文字：\n$extractedText",
            payloads
        )
        val notes = result.optJSONArray("notes")
        val drafts = if (notes == null) listOf(parseGrammarDraft(result)) else buildList {
            for (index in 0 until notes.length()) {
                val item = notes.optJSONObject(index) ?: continue
                runCatching { parseGrammarDraft(item) }.getOrNull()?.let(::add)
            }
        }
        require(drafts.isNotEmpty()) { "AI 没有识别到可导入的语法" }
        return drafts
    }

    private fun parseGrammarDraft(result: JSONObject): GrammarDraft {
        fun value(name: String): String {
            val raw = result.opt(name)
            return when (raw) {
                is JSONArray -> (0 until raw.length()).joinToString("\n") { raw.optString(it) }
                else -> result.optString(name).trim()
            }
        }
        val patternItems = result.optJSONArray("patterns") ?: JSONArray()
        val patterns = buildList {
            for (index in 0 until patternItems.length()) {
                val item = patternItems.optJSONObject(index) ?: continue
                val exampleItems = item.optJSONArray("examples") ?: JSONArray()
                val examples = buildList {
                    for (exampleIndex in 0 until exampleItems.length()) {
                        val example = exampleItems.optJSONObject(exampleIndex) ?: continue
                        val sentence = example.optString("sentence").trim()
                        if (sentence.isNotBlank()) add(GrammarExampleDraft(
                            sentence = sentence,
                            translation = example.optString("translation").trim(),
                            highlightedText = example.optString("highlightedText").trim()
                        ))
                    }
                }
                val pattern = GrammarPatternDraft(
                    title = item.optString("title", "句型 ${index + 1}").trim(),
                    formula = item.optString("formula").trim(),
                    meaning = item.optString("meaning").trim(),
                    usage = item.optString("usage").trim(),
                    notes = item.optString("notes").trim(), examples = examples
                )
                if (pattern.formula.isNotBlank() || pattern.meaning.isNotBlank()) add(pattern)
            }
        }
        val questionItems = result.optJSONArray("questions") ?: JSONArray()
        val questions = buildList {
            for (index in 0 until questionItems.length()) {
                val item = questionItems.optJSONObject(index) ?: continue
                fun qValue(name: String): String {
                    val raw = item.opt(name)
                    return if (raw is JSONArray) (0 until raw.length()).joinToString(",") { raw.optString(it) }
                    else item.optString(name).trim()
                }
                val question = GrammarQuestionDraft(
                    grammarPatternIndex = item.optInt("grammarPatternIndex", -1),
                    type = qValue("type").ifBlank { "CLOZE_CHOICE" },
                    prompt = qValue("prompt"), translation = qValue("translation"),
                    answer = qValue("answer"), acceptedAnswers = qValue("acceptedAnswers"),
                    options = qValue("options"), explanation = qValue("explanation"),
                    difficulty = item.optInt("difficulty", 1).coerceIn(1, 3), sourceType = "AI"
                )
                if (question.prompt.isNotBlank() && question.answer.isNotBlank() && "{{answer}}" in question.prompt) add(question)
            }
        }
        val draft = GrammarDraft(
            title = value("title"),
            category = value("category").ifBlank { "其他" },
            level = value("level").ifBlank { "未分级" },
            summary = value("summary"),
            structure = value("structure"),
            usage = value("usage"),
            exampleSentence = value("exampleSentence"),
            exampleTranslation = value("exampleTranslation"),
            commonMistakes = value("commonMistakes"),
            comparison = value("comparison"),
            tags = value("tags").replace("\n", ","),
            questionTemplate = value("questionTemplate"),
            answer = value("answer"),
            acceptedAnswers = value("acceptedAnswers").replace("\n", ","),
            options = value("options").replace("\n", ","),
            explanation = value("explanation"), patterns = patterns,
            questions = questions
        )
        require(draft.title.isNotBlank()) { "AI 没有返回语法名称" }
        if (draft.questionTemplate.isNotBlank()) {
            require("{{answer}}" in draft.questionTemplate) { "AI 题目缺少答案位置" }
            require(draft.answer.isNotBlank()) { "AI 题目缺少答案" }
        }
        return draft
    }

    suspend fun recognizeGrammarWritingSources(uris: List<Uri>): GrammarWritingScanResult {
        require(settings.value.aiEnabled) { "请先在设置中启用 AI" }
        require(uris.isNotEmpty()) { "请先选择图片或文件" }
        val context = getApplication<Application>()
        val payloads = buildList {
            uris.take(10).forEach { uri ->
                val mime = context.contentResolver.getType(uri).orEmpty()
                if (mime.contains("pdf", true)) addAll(DocumentParser.renderPdfPagesForAi(context, uri, settings.value.pdfPageLimit))
                else if (mime.startsWith("image/")) add(AiImagePreprocessor.prepare(context, uri))
            }
        }
        val documentText = uris.take(10).mapNotNull { uri ->
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("image/") || mime.contains("pdf", true)) null
            else runCatching { DocumentParser.extractTextFromUri(context, uri, settings.value.pdfPageLimit) }.getOrNull()
        }.filter(String::isNotBlank).joinToString("\n\n")
        require(payloads.isNotEmpty() || documentText.isNotBlank()) { "文件没有可识别的作文内容" }
        val result = GeminiService.runAssistant(
            """
            你是严谨的英文作文识别助手。依图片或文件原始顺序逐字提取英文作文，保留段落、大小写、拼字、标点与原本错误，不可先修正语法，不可猜测看不清楚的字。
            只返回 JSON：{"recognizedText":"完整原文","uncertainParts":["无法确定的原始片段"]}。
            看不清楚处在 recognizedText 中以 [看不清楚] 标示。多张图片依收到的顺序合并。
            """.trimIndent() + if (documentText.isBlank()) "" else "\n\n文件内容：\n$documentText",
            payloads
        )
        return GrammarWritingScanResult(
            recognizedText = result.optString("recognizedText").trim(),
            uncertainParts = result.optJSONArray("uncertainParts")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
            }.orEmpty()
        ).also { require(it.recognizedText.isNotBlank()) { "AI 没有识别到英文作文" } }
    }

    suspend fun checkGrammarWriting(text: String): List<GrammarWritingIssue> {
        require(text.isNotBlank()) { "请先输入英文内容" }
        require(settings.value.aiEnabled) { "请先在设置中启用 AI" }
        val result = GeminiService.runAssistant(
            """
            你是严谨的英文语法检查助手。分析下方英文，只回报确定存在的语法错误；风格偏好或可接受变体不要当成错误。
            只返回 JSON 对象：{"issues":[{"ruleKey":"稳定的英文规则代码","title":"简体中文语法名称","originalSentence":"原句","correctedSentence":"完整修正句","originalText":"错误片段","correctedText":"替换片段","explanation":"简体中文原因"}]}。
            originalText 必须逐字存在于 originalSentence；correctedText 必须逐字存在于 correctedSentence。没有可靠错误就返回空数组。

            待检查内容：
            $text
            """.trimIndent()
        )
        val items = result.optJSONArray("issues") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val issue = GrammarWritingIssue(
                    ruleKey = item.optString("ruleKey").trim(), title = item.optString("title").trim(),
                    originalSentence = item.optString("originalSentence").trim(),
                    correctedSentence = item.optString("correctedSentence").trim(),
                    originalText = item.optString("originalText").trim(), correctedText = item.optString("correctedText").trim(),
                    explanation = item.optString("explanation").trim()
                )
                if (issue.ruleKey.isNotBlank() && issue.title.isNotBlank() &&
                    issue.originalText in issue.originalSentence && issue.correctedText in issue.correctedSentence
                ) add(issue)
            }
        }.distinctBy { "${it.ruleKey}|${it.originalSentence}" }
    }

    fun acceptGrammarWritingIssue(issue: GrammarWritingIssue, onComplete: (Long) -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val existingNote = db.grammarNoteDao().getAllOnce().firstOrNull {
                        it.sourceType == "WRITING_CHECK" && it.title.equals(issue.title, ignoreCase = true)
                    }
                    val noteId = existingNote?.id ?: db.grammarNoteDao().insert(
                        GrammarNote(
                            course = "写作薄弱点", folder = "写作薄弱点", title = issue.title,
                            summary = issue.explanation, commonMistakes = "${issue.originalText} → ${issue.correctedText}",
                            sourceType = "WRITING_CHECK"
                        )
                    )
                    if (db.grammarLibraryDao().getAllOnce().none {
                            it.course == "写作薄弱点" && it.name == "写作薄弱点"
                        }) {
                        db.grammarLibraryDao().insert(
                            GrammarLibrary(
                                course = "写作薄弱点",
                                name = "写作薄弱点",
                                description = "由作文批改自动整理的语法薄弱点"
                            )
                        )
                    }
                    val oldWeakness = db.grammarQuestionDao().getWeakness(issue.ruleKey)
                    if (oldWeakness == null) db.grammarQuestionDao().insertWeakness(
                        GrammarWeakness(
                            ruleKey = issue.ruleKey, grammarNoteId = noteId, title = issue.title,
                            originalText = issue.originalText, correctedText = issue.correctedText,
                            explanation = issue.explanation
                        )
                    ) else db.grammarQuestionDao().updateWeakness(
                        oldWeakness.copy(
                            grammarNoteId = noteId, originalText = issue.originalText,
                            correctedText = issue.correctedText, explanation = issue.explanation,
                            occurrenceCount = oldWeakness.occurrenceCount + 1,
                            lastOccurredAt = System.currentTimeMillis()
                        )
                    )
                    val prompt = issue.correctedSentence.replaceFirst(issue.correctedText, "{{answer}}")
                    if ("{{answer}}" in prompt && db.grammarQuestionDao().getForNote(noteId).none { it.prompt == prompt }) {
                        db.grammarQuestionDao().insertQuestion(
                            GrammarQuestion(
                                grammarNoteId = noteId, type = "CLOZE_INPUT", prompt = prompt,
                                answer = issue.correctedText, explanation = issue.explanation,
                                sourceType = "WRITING_CHECK"
                            )
                        )
                    }
                    noteId
                }
            }.onSuccess(onComplete).onFailure {
                _dataMessage.value = "创建薄弱点练习失败：${it.message ?: "未知错误"}"
            }
        }
    }

    fun saveGrammarWritingRecord(
        title: String,
        course: String,
        originalText: String,
        revisedText: String,
        issues: List<GrammarWritingIssue>,
        recordId: Long? = null,
        onComplete: (Long) -> Unit = {}
    ) {
        if (title.isBlank() || originalText.isBlank()) return
        viewModelScope.launch {
            val payload = JSONArray().apply {
                issues.forEach { issue -> put(JSONObject().apply {
                    put("ruleKey", issue.ruleKey); put("title", issue.title)
                    put("originalSentence", issue.originalSentence); put("correctedSentence", issue.correctedSentence)
                    put("originalText", issue.originalText); put("correctedText", issue.correctedText)
                    put("explanation", issue.explanation)
                }) }
            }.toString()
            val existing = recordId?.let { target -> grammarWritingRecords.value.firstOrNull { it.id == target } }
            val record = GrammarWritingRecord(
                    id = existing?.id ?: 0,
                    course = course.ifBlank { "通用" },
                    title = title.trim(),
                    originalText = originalText,
                    revisedText = revisedText.ifBlank { originalText },
                    issuesJson = payload,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            val id = if (existing == null) db.grammarWritingDao().insert(record) else {
                db.grammarWritingDao().update(record)
                record.id
            }
            onComplete(id)
        }
    }

    fun deleteGrammarWritingRecord(record: GrammarWritingRecord) {
        viewModelScope.launch { db.grammarWritingDao().delete(record) }
    }

    fun moveCard(card: Flashcard, direction: Int) {
        if (direction !in setOf(-1, 1)) return
        viewModelScope.launch {
            repository.moveCard(card.id, card.deckId, direction)
        }
    }

    fun moveDeck(deck: Deck, direction: Int) {
        if (direction !in setOf(-1, 1)) return
        viewModelScope.launch {
            repository.moveDeck(deck.id, direction)
        }
    }

    fun moveDeckGlobally(deck: Deck, direction: Int) {
        if (direction !in setOf(-1, 1)) return
        viewModelScope.launch { repository.moveDeckGlobally(deck.id, direction) }
    }

    fun reorderDecks(orderedDeckIds: List<Long>) {
        viewModelScope.launch { repository.reorderDecks(orderedDeckIds) }
    }

    fun reorderCards(deckId: Long, orderedCardIds: List<Long>) {
        viewModelScope.launch { repository.reorderCards(deckId, orderedCardIds) }
    }

    fun reorderGrammarNotes(orderedNoteIds: List<Long>) {
        if (orderedNoteIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val all = db.grammarNoteDao().getAllOnce()
                    val requestedIds = orderedNoteIds.distinct()
                    require(requestedIds.size == orderedNoteIds.size) { "语法排序包含重复项目" }
                    val requestedSet = requestedIds.toSet()
                    require(requestedSet.all { id -> all.any { it.id == id } }) { "找不到要排序的语法" }
                    val reordered = requestedIds.map { id -> requireNotNull(all.firstOrNull { it.id == id }) }
                    var reorderedIndex = 0
                    val merged = all.map { note ->
                        if (note.id in requestedSet) reordered[reorderedIndex++] else note
                    }
                    db.grammarNoteDao().updateAll(
                        merged.mapIndexed { index, note -> note.copy(sortOrder = index.toLong()) }
                    )
                }
            }.onFailure {
                _dataMessage.value = "调整语法顺序失败：${it.message ?: "未知错误"}"
            }
        }
    }

    fun addGrammarLibrary(course: String, name: String, description: String, colorHex: String) {
        viewModelScope.launch {
            val cleanCourse = course.trim().ifBlank { "通用" }
            val cleanName = name.trim()
            if (cleanName.isBlank()) return@launch
            val duplicate = db.grammarLibraryDao().getAllOnce().any {
                it.course.equals(cleanCourse, true) && it.name.equals(cleanName, true)
            }
            if (duplicate) {
                _dataMessage.value = "这个课程已有同名语法库"
            } else {
                db.grammarLibraryDao().insert(
                    GrammarLibrary(course = cleanCourse, name = cleanName, description = description.trim(), colorHex = colorHex)
                )
            }
        }
    }

    fun updateGrammarLibrary(library: GrammarLibrary) {
        viewModelScope.launch {
            db.withTransaction {
                val previous = db.grammarLibraryDao().getAllOnce().firstOrNull { it.id == library.id }
                if (previous != null && (previous.course != library.course || previous.name != library.name)) {
                    db.grammarNoteDao().renameLibrary(previous.course, previous.name, library.name, System.currentTimeMillis())
                    if (previous.course != library.course) {
                        db.grammarNoteDao().getAllOnce()
                            .filter { it.course == previous.course && it.folder == library.name }
                            .forEach { db.grammarNoteDao().update(it.copy(course = library.course, updatedAt = System.currentTimeMillis())) }
                    }
                }
                db.grammarLibraryDao().update(library)
            }
        }
    }

    fun deleteGrammarLibrary(library: GrammarLibrary) {
        viewModelScope.launch {
            db.withTransaction {
                val notes = db.grammarNoteDao().getAllOnce().filter { it.course == library.course && it.folder == library.name }
                notes.forEach { note ->
                    db.grammarQuestionDao().deleteForNote(note.id)
                    db.grammarContentDao().deleteExamplesForNote(note.id)
                    db.grammarContentDao().deletePatternsForNote(note.id)
                    db.grammarNoteDao().delete(note)
                }
                db.grammarLibraryDao().delete(library)
            }
        }
    }

    fun reorderGrammarLibraries(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val all = db.grammarLibraryDao().getAllOnce()
            val requested = ids.mapNotNull { id -> all.firstOrNull { it.id == id } }
            if (requested.size == ids.size) {
                val selected = ids.toSet()
                var index = 0
                val merged = all.map { if (it.id in selected) requested[index++] else it }
                db.grammarLibraryDao().updateAll(merged.mapIndexed { order, item -> item.copy(sortOrder = order.toLong()) })
            }
        }
    }

    fun renameCourse(oldName: String, newName: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = runCatching {
                db.withTransaction {
                    repository.renameCourse(oldName, newName)
                    db.grammarNoteDao().renameCourse(oldName.trim(), newName.trim(), System.currentTimeMillis())
                }
            }.isSuccess
            onComplete(success)
        }
    }

    fun moveCards(
        cards: List<Flashcard>,
        targetDeckId: Long,
        onComplete: (movedCount: Int, mergedCount: Int) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            runCatching {
                repository.moveCards(cards.map { it.id }, targetDeckId)
            }.onSuccess { result ->
                onComplete(result.movedCount, result.mergedDuplicateCount)
            }.onFailure {
                _dataMessage.value = "移动失败：${it.message ?: "未知错误"}"
                onComplete(-1, 0)
            }
        }
    }

    fun createFolderAndMoveCards(
        courseName: String,
        folderName: String,
        description: String,
        colorHex: String,
        cards: List<Flashcard>,
        onComplete: (movedCount: Int, mergedCount: Int) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            runCatching {
                val newId = repository.insertDeck(
                    Deck(
                        category = courseName.trim(),
                        name = folderName.trim(),
                        description = description.trim(),
                        colorHex = colorHex
                    )
                )
                repository.moveCards(cards.map { it.id }, newId)
            }.onSuccess { result ->
                onComplete(result.movedCount, result.mergedDuplicateCount)
            }.onFailure {
                _dataMessage.value = "创建文件夹并移动失败：${it.message ?: "未知错误"}"
                onComplete(-1, 0)
            }
        }
    }

    fun copyCardToDeck(card: Flashcard, targetDeckId: Long, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                val sortOrder = repository.nextSortOrder(targetDeckId, 1)
                repository.insertCardsDeduplicating(
                    listOf(
                        card.copy(
                            id = 0,
                            deckId = targetDeckId,
                            isMastered = false,
                            isFavorite = false,
                            mistakeCount = 0,
                            intervalDays = 0,
                            easeFactor = 2.5f,
                            repetitionCount = 0,
                            nextReviewTimestamp = System.currentTimeMillis(),
                            lastReviewedTimestamp = null,
                            createdAt = System.currentTimeMillis(),
                            sortOrder = sortOrder
                        )
                    )
                )
            }.onSuccess { onComplete(it > 0) }
                .onFailure {
                    _dataMessage.value = "加入文件夹失败：${it.message ?: "未知错误"}"
                    onComplete(false)
                }
        }
    }

    fun createFolderAndCopyCard(
        courseName: String,
        folderName: String,
        description: String,
        colorHex: String,
        card: Flashcard,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching {
                val safeCourse = courseName.trim()
                val safeName = folderName.trim()
                require(safeCourse.isNotBlank() && safeName.isNotBlank()) { "课程与文件夹名称不能空白" }
                require(allDecks.value.none {
                    it.category.equals(safeCourse, ignoreCase = true) &&
                        it.name.equals(safeName, ignoreCase = true)
                }) { "同一课程中已经有同名文件夹" }
                val deckId = repository.insertDeck(
                    Deck(
                        category = safeCourse,
                        name = safeName,
                        description = description.trim(),
                        colorHex = colorHex
                    )
                )
                val copied = card.copy(
                    id = 0,
                    deckId = deckId,
                    isMastered = false,
                    isFavorite = false,
                    mistakeCount = 0,
                    intervalDays = 0,
                    easeFactor = 2.5f,
                    repetitionCount = 0,
                    nextReviewTimestamp = System.currentTimeMillis(),
                    lastReviewedTimestamp = null,
                    createdAt = System.currentTimeMillis(),
                    sortOrder = System.currentTimeMillis()
                )
                repository.insertCardsDeduplicating(listOf(copied)) > 0
            }.onSuccess(onComplete)
                .onFailure {
                    _dataMessage.value = "创建文件夹失败：${it.message ?: "未知错误"}"
                    onComplete(false)
                }
        }
    }

    fun toggleStudyCheckIn(date: java.time.LocalDate) {
        if (date <= java.time.LocalDate.now()) {
            studyCheckInStore.toggle(date)
            VocabWidgetProvider.requestUpdate(getApplication())
        }
    }

    /**
     * Auto-fill dictionary entry details for a selected word.
     */
    fun getDictionaryMatch(word: String): DictionaryEntry? {
        return DictionaryEngine.findExact(word)
    }

    /**
     * The AI action always performs a real AI request. Offline dictionary lookup
     * is used only for exact local matches elsewhere and is never presented as AI output.
     */
    suspend fun fetchAiWordDetails(word: String): DictionaryEntry? {
        if (!settings.value.aiEnabled) return null
        val entry = GeminiService.fetchWordDetails(word)
        if (entry == null) {
            GeminiService.lastError.value?.let { error(it) }
        }
        return entry
    }

    suspend fun fetchAiCardDetails(candidate: OcrCardCandidate): DictionaryEntry? =
        fetchAiCardDetailsBatch(listOf(candidate))[0]

    suspend fun fetchAiCardDetailsBatch(candidates: List<OcrCardCandidate>): Map<Int, DictionaryEntry> {
        check(settings.value.aiEnabled) { "请先在设置中启用 AI 功能" }
        val grouped = candidates.take(80).withIndex().filter { it.value.word.isNotBlank() }
            .groupBy { it.value.word.trim().lowercase() }.values
        val results = mutableMapOf<Int, DictionaryEntry>()
        // Same word in different folders may have different sentences. Keep those
        // requests separate, while batching distinct words for efficiency.
        repeat(grouped.maxOfOrNull { it.size } ?: 0) { round ->
            val batch = grouped.mapNotNull { it.getOrNull(round) }
            val enriched = GeminiService.batchEnrichCandidates(batch.map { it.value })
                .associateBy { it.word.trim().lowercase() }
            batch.forEach { indexed ->
                val item = enriched[indexed.value.word.trim().lowercase()] ?: return@forEach
                if (item != indexed.value) results[indexed.index] = DictionaryEntry(
                    word = item.word, phonetic = item.phonetic, partOfSpeech = item.partOfSpeech,
                    definition = item.definition, exampleSentence = item.exampleSentence,
                    exampleTranslation = item.exampleTranslation, category = "AI 补充"
                )
            }
        }
        if (results.isEmpty()) GeminiService.lastError.value?.let { error(it) }
        return results
    }

    suspend fun fetchAiWordDetailsBatch(words: List<String>): Map<String, DictionaryEntry> {
        if (!settings.value.aiEnabled) error("请先在设置中启用 AI 功能")
        val uniqueWords = words
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(80)
        if (uniqueWords.isEmpty()) return emptyMap()

        val candidates = uniqueWords.map { word ->
            OcrCardCandidate(
                word = word,
                partOfSpeech = "",
                definition = "",
                exampleSentence = ""
            )
        }
        val enriched = GeminiService.batchEnrichCandidates(candidates)
        val results = enriched.mapNotNull { item ->
            if (
                item.partOfSpeech.isBlank() &&
                item.definition.isBlank() &&
                item.phonetic.isBlank() &&
                item.exampleSentence.isBlank() &&
                item.exampleTranslation.isBlank()
            ) {
                null
            } else {
                item.word.lowercase() to DictionaryEntry(
                    word = item.word,
                    phonetic = item.phonetic,
                    partOfSpeech = item.partOfSpeech,
                    definition = item.definition,
                    exampleSentence = item.exampleSentence,
                    exampleTranslation = item.exampleTranslation,
                    category = "AI 补充"
                )
            }
        }.toMap().toMutableMap()
        uniqueWords.forEach { word ->
            val current = results[word.lowercase()]
            if (current?.phonetic.isNullOrBlank()) {
                runCatching { GeminiService.fetchWordDetails(word) }
                    .getOrNull()
                    ?.takeIf { it.phonetic.isNotBlank() }
                    ?.let { results[word.lowercase()] = it }
            }
        }
        if (results.isEmpty()) GeminiService.lastError.value?.let { error(it) }
        return results
    }

    suspend fun formatExternalCardsWithAi(
        candidates: List<ExternalCardCandidate>
    ): AiExternalFormattingResult {
        val selected = candidates.filter { it.selected }
        if (selected.isEmpty()) {
            return AiExternalFormattingResult(candidates, 0, 0, "没有选取需要整理的卡片")
        }
        val pending = selected.filterNot { it.aiFormatted }
        if (pending.isEmpty()) {
            return AiExternalFormattingResult(candidates, selected.size, 0, "已选卡片都已完成 AI 格式整理")
        }
        val currentSettings = settings.value
        if (
            !currentSettings.aiEnabled ||
            !currentSettings.usePersonalAiApi ||
            personalAiConfigs(currentSettings).isEmpty()
        ) {
            return AiExternalFormattingResult(
                cards = candidates,
                formattedCount = 0,
                fallbackCount = pending.size,
                message = "尚未设置可用的个人 AI API"
            )
        }

        val formattedBySelectedIndex = GeminiService.formatImportedCards(
            candidates = pending,
            customInstructions = currentSettings.aiWordPrompt
        )
        var pendingIndex = 0
        val formattedCards = candidates.map { candidate ->
            if (!candidate.selected || candidate.aiFormatted) return@map candidate
            val entry = formattedBySelectedIndex[pendingIndex++]
            if (entry == null) {
                candidate
            } else {
                candidate.copy(
                    word = entry.word,
                    phonetic = entry.phonetic,
                    partOfSpeech = entry.partOfSpeech,
                    definition = entry.definition,
                    exampleSentence = entry.exampleSentence,
                    exampleTranslation = entry.exampleTranslation,
                    aiFormatted = true
                )
            }
        }
        val formattedCount = formattedBySelectedIndex.size
        val fallbackCount = pending.size - formattedCount
        return AiExternalFormattingResult(
            cards = formattedCards,
            formattedCount = formattedCount,
            fallbackCount = fallbackCount,
            message = if (fallbackCount == 0) {
                "AI 已完成 ${pending.size} 张卡片的格式整理"
            } else {
                "AI 完成 $formattedCount 张，$fallbackCount 张保留原始正背面"
            }
        )
    }

    fun updateBooleanSetting(name: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBoolean(name, enabled)
            if (name == "remindersEnabled") {
                if (enabled) StudyReminderScheduler.scheduleAll(getApplication(), settings.value.reminderTimes)
                else StudyReminderScheduler.cancelAll(getApplication(), settings.value.reminderTimes)
            }
        }
    }

    fun updateIntSetting(name: String, value: Int) {
        viewModelScope.launch {
            settingsRepository.setInt(name, value)
            if (name == "dailyGoalCards") {
                VocabWidgetProvider.requestUpdate(getApplication())
            }
            if (name in setOf("reminderHour", "reminderMinute") && settings.value.remindersEnabled) {
                val hour = if (name == "reminderHour") value else settings.value.reminderHour
                val minute = if (name == "reminderMinute") value else settings.value.reminderMinute
                StudyReminderScheduler.schedule(getApplication(), hour, minute)
            }
        }
    }

    fun updateDictionarySource(source: String) {
        viewModelScope.launch { settingsRepository.setDictionarySource(source) }
    }

    fun updateReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepository.setReminderTime(hour, minute)
            if (settings.value.remindersEnabled) {
                StudyReminderScheduler.schedule(getApplication(), hour, minute)
            }
        }
    }

    fun updateReminderTimes(times: List<ReminderTime>) {
        viewModelScope.launch {
            val oldTimes = settings.value.reminderTimes
            settingsRepository.setReminderTimes(times)
            if (settings.value.remindersEnabled) {
                StudyReminderScheduler.replaceAll(getApplication(), oldTimes, times)
            } else {
                StudyReminderScheduler.cancelAll(getApplication(), oldTimes)
            }
        }
    }

    fun updateAiWordPrompt(prompt: String) {
        viewModelScope.launch { settingsRepository.setAiWordPrompt(prompt) }
    }

    fun resetAiWordPrompt() {
        viewModelScope.launch {
            settingsRepository.setAiWordPrompt(com.example.data.api.AiPromptDefaults.WORD_DETAILS)
        }
    }

    fun updateAiImagePrompt(prompt: String) {
        viewModelScope.launch { settingsRepository.setAiImagePrompt(prompt) }
    }

    fun resetAiImagePrompt() {
        viewModelScope.launch {
            settingsRepository.setAiImagePrompt(
                com.example.data.api.AiPromptDefaults.IMAGE_VOCABULARY_EXTRACTION
            )
        }
    }

    fun updateThemeMode(mode: String) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun updateThemeColorPreset(preset: String) {
        viewModelScope.launch { settingsRepository.setThemeColorPreset(preset) }
    }

    fun updateCustomThemeColors(primary: String, secondary: String) {
        viewModelScope.launch {
            settingsRepository.setCustomThemeColors(primary, secondary)
        }
    }

    fun updateGradientColors(start: String, end: String) {
        viewModelScope.launch { settingsRepository.setGradientColors(start, end) }
    }

    fun updateCustomTextColor(color: String) {
        viewModelScope.launch { settingsRepository.setCustomTextColor(color) }
    }

    fun updateBackgroundAppearance(brightness: Float, opacity: Float) {
        viewModelScope.launch {
            settingsRepository.setBackgroundAppearance(brightness, opacity)
        }
    }

    fun updateFontAppearance(chineseFamily: String, englishFamily: String, scale: Float) {
        viewModelScope.launch {
            settingsRepository.setFontAppearance(chineseFamily, englishFamily, scale)
        }
    }

    fun updateSpeechRate(rate: Float) {
        viewModelScope.launch { settingsRepository.setSpeechRate(rate) }
    }

    fun updateTtsVoiceStyle(style: String) {
        viewModelScope.launch { settingsRepository.setTtsVoiceStyle(style) }
    }

    fun updateTtsVoiceName(name: String) {
        viewModelScope.launch { settingsRepository.setTtsVoiceName(name) }
    }

    fun previewTtsVoice() {
        val value = settings.value
        ttsManager.speak(
            text = "Hello! This is your English pronunciation voice.",
            rate = value.speechRate,
            voiceName = value.ttsVoiceName,
            voiceStyle = value.ttsVoiceStyle
        )
    }

    fun updateAiProvider(providerName: String) {
        val provider = AiProvider.from(providerName)
        viewModelScope.launch {
            settingsRepository.setAiProvider(provider.name, aiCredentialsStore.savedModel(provider))
            _availableAiModels.value = emptyList()
            _aiConnectionStatus.value = null
        }
    }

    fun updateAiModel(model: String) {
        viewModelScope.launch {
            settingsRepository.setAiModel(model)
            aiCredentialsStore.updateModel(AiProvider.from(settings.value.aiProvider), model)
            _aiConnectionStatus.value = null
            refreshAiProfiles(settings.value.copy(aiModel = model))
        }
    }

    fun updateAiBaseUrl(baseUrl: String) {
        viewModelScope.launch {
            settingsRepository.setAiBaseUrl(baseUrl)
            _availableAiModels.value = emptyList()
            _aiConnectionStatus.value = null
        }
    }

    fun updateAiChatStyle(style: String) {
        viewModelScope.launch { settingsRepository.setAiChatStyle(style) }
    }

    fun savePersonalApiKey(apiKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val value = settings.value
                val provider = AiProvider.from(value.aiProvider)
                aiCredentialsStore.saveApiKey(provider, apiKey, value.aiModel)
                settingsRepository.setBoolean("usePersonalAiApi", true)
            }.onSuccess {
                _apiKeyConfigured.value = aiCredentialsStore.hasApiKey()
                configurePersonalAi(settings.value.copy(usePersonalAiApi = true))
                refreshAiProfiles(settings.value)
                _aiConnectionStatus.value = "已加密保存，可继续加入其他供应商"
            }.onFailure {
                _aiConnectionStatus.value = "保存失败：${it.message ?: "未知错误"}"
            }
        }
    }

    fun clearPersonalApiKey(credentialId: String) {
        aiCredentialsStore.clearCredential(credentialId)
        _apiKeyConfigured.value = aiCredentialsStore.hasApiKey()
        _availableAiModels.value = emptyList()
        configurePersonalAi(settings.value)
        refreshAiProfiles(settings.value)
        if (!_apiKeyConfigured.value) {
            viewModelScope.launch { settingsRepository.setBoolean("usePersonalAiApi", false) }
        }
        _aiConnectionStatus.value = "已移除 API Key"
    }

    fun refreshAiModels() {
        val config = selectedAiConfig(settings.value)
        if (config == null) {
            _aiConnectionStatus.value = "请先保存 API Key"
            return
        }
        viewModelScope.launch {
            _aiConnectionStatus.value = "正在读取可用模型..."
            runCatching { DirectAiService.listModels(config) }
                .onSuccess {
                    _availableAiModels.value = it
                    _aiConnectionStatus.value = "已取得 ${it.size} 个可用模型"
                }
                .onFailure {
                    _aiConnectionStatus.value = "读取模型失败：${it.message ?: "请检查 Key"}"
                }
        }
    }

    fun testAiConnection(credentialId: String? = null) {
        val config = if (credentialId == null) {
            selectedAiConfig(settings.value)
        } else {
            personalAiConfigs(settings.value).firstOrNull { it.credentialId == credentialId }
        }
        if (config == null) {
            _aiConnectionStatus.value = "找不到这组 API Key，请重新加入"
            return
        }
        viewModelScope.launch {
            _aiConnectionStatus.value = "正在测试 ${config.provider.displayName}..."
            val result = DirectAiService.test(config)
            if (result.isSuccess) {
                _aiConnectionStatus.value = "连接测试成功，${config.model} 可以使用"
                DirectAiService.refreshUsage(config)
                refreshAiProfiles(settings.value)
            } else {
                val error = result.exceptionOrNull()
                _aiConnectionStatus.value =
                    "连接失败：${error?.message ?: "请检查 Key、模型与额度"}"
            }
        }
    }

    fun completeOnboarding(dailyGoalCards: Int, remindersEnabled: Boolean, reminderHour: Int) {
        viewModelScope.launch {
            settingsRepository.completeOnboarding(dailyGoalCards, remindersEnabled, reminderHour)
            if (remindersEnabled) {
                StudyReminderScheduler.schedule(getApplication(), reminderHour, 0)
            }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            runCatching {
                repository.clearAllData()
                db.grammarQuestionDao().deleteAllQuestions()
                db.grammarQuestionDao().deleteAllWeaknesses()
                db.grammarContentDao().deleteAllExamples()
                db.grammarContentDao().deleteAllPatterns()
                db.grammarNoteDao().deleteAll()
                db.grammarWritingDao().deleteAll()
                db.grammarLibraryDao().deleteAll()
                settingsRepository.resetAfterDataClear()
                aiCredentialsStore.clear()
                learningSessionStore.clearAll()
                studyCheckInStore.clear()
                assistantChatStore.clear()
                pronunciationPracticeStore.clear()
                assistantAllMessages.clear()
                _assistantConversations.value = emptyList()
                _assistantMessages.value = emptyList()
                _savedArticles.value = emptyList()
                _assistantCurrentConversationId.value = null
                _assistantPendingAction.value = null
                _assistantUndoAction.value = null
                GeminiService.configurePersonalApis(emptyList())
                _apiKeyConfigured.value = false
                _availableAiModels.value = emptyList()
                StudyReminderScheduler.cancelAll(getApplication(), settings.value.reminderTimes)
                _selectedDeckId.value = null
            }.onSuccess {
                _dataMessage.value = "所有数据已清空，现在可从 0 开始"
            }.onFailure {
                _dataMessage.value = "清空失败：${it.message ?: "未知错误"}"
            }
        }
    }

    fun recordPronunciationResult(cardId: Long, score: Int) {
        pronunciationPracticeStore.recordResult(cardId, score.coerceIn(0, 100))
    }

    fun recordReadingMistake(cardId: Long) {
        allCards.value.firstOrNull { it.id == cardId }?.let(::recordGameMistake)
    }

    private fun configurePersonalAi(value: AppSettings) {
        GeminiService.configureWordDetailsPrompt(value.aiWordPrompt)
        GeminiService.configureImagePrompt(value.aiImagePrompt)
        GeminiService.configurePersonalApis(
            if (value.usePersonalAiApi) personalAiConfigs(value) else emptyList()
        )
        refreshAiProfiles(value)
    }

    private fun personalAiConfigs(value: AppSettings): List<PersonalAiConfig> {
        val provider = AiProvider.from(value.aiProvider)
        val customBaseUrl = value.aiBaseUrl.trim()
        return aiCredentialsStore.readConfigs(
            preferredProvider = provider,
            preferredModel = value.aiModel.ifBlank { provider.defaultModel }
        ).map { config ->
            if (config.provider == AiProvider.OPENAI_COMPATIBLE && customBaseUrl.isNotBlank()) {
                config.copy(baseUrl = customBaseUrl)
            } else {
                config
            }
        }
    }

    private fun selectedAiConfig(value: AppSettings): PersonalAiConfig? =
        personalAiConfigs(value).firstOrNull {
            it.provider == AiProvider.from(value.aiProvider)
        }

    private fun refreshAiProfiles(value: AppSettings) {
        _aiApiProfiles.value = personalAiConfigs(value).map { config ->
            AiApiProfile(
                credentialId = config.credentialId,
                provider = config.provider,
                model = config.model,
                maskedKey = "••••••••••••"
            )
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            _dataMessage.value = runCatching {
                val count = backupService.exportJson(uri)
                "已备份 $count 张单词卡"
            }.getOrElse { "备份失败：${it.message ?: "无法写入文件"}" }
        }
    }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            _dataMessage.value = runCatching {
                val count = backupService.exportCsv(uri)
                "已导出 $count 张单词卡"
            }.getOrElse { "CSV 导出失败：${it.message ?: "无法写入文件"}" }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            _dataMessage.value = runCatching {
                val count = backupService.importJson(uri)
                studyCheckInStore.reload()
                "备份恢复完成，添加 $count 张单词卡"
            }.getOrElse { "恢复失败：${it.message ?: "文件格式错误"}" }
        }
    }

    /**
     * Submit SRS Review answer.
     */
    fun submitCardReview(card: Flashcard, rating: Int) {
        viewModelScope.launch {
            repository.recordReview(card, rating)
            VocabWidgetProvider.requestUpdate(getApplication())
        }
    }

    fun undoCardReview(originalCard: Flashcard) {
        viewModelScope.launch {
            repository.undoLatestReview(originalCard)
            VocabWidgetProvider.requestUpdate(getApplication())
        }
    }

    /**
     * Toggle mastered state directly.
     */
    fun toggleMastered(card: Flashcard) {
        viewModelScope.launch {
            repository.toggleMastered(card)
        }
    }

    fun toggleFavorite(card: Flashcard) {
        viewModelScope.launch { repository.toggleFavorite(card) }
    }

    fun recordGameMistake(card: Flashcard) {
        viewModelScope.launch { repository.recordGameMistake(card) }
    }

    fun addCard(card: Flashcard) {
        viewModelScope.launch {
            repository.insertCard(card)
            VocabWidgetProvider.requestUpdate(getApplication())
        }
    }

    fun updateCard(card: Flashcard) {
        viewModelScope.launch {
            repository.updateCard(card)
            VocabWidgetProvider.requestUpdate(getApplication())
        }
    }

    fun deleteCard(card: Flashcard) {
        viewModelScope.launch {
            repository.deleteCard(card)
            VocabWidgetProvider.requestUpdate(getApplication())
        }
    }

    fun deleteCards(cards: List<Flashcard>) {
        if (cards.isEmpty()) return
        viewModelScope.launch {
            repository.deleteCards(cards)
            VocabWidgetProvider.requestUpdate(getApplication())
        }
    }

    suspend fun saveBatchCards(
        cardsToSave: List<Flashcard>,
        cardsToDelete: List<Flashcard> = emptyList()
    ): Result<List<Long>> = runCatching {
        cardSaveMutex.withLock {
            repository.saveBatch(cardsToSave, cardsToDelete).also {
                VocabWidgetProvider.requestUpdate(getApplication())
            }
        }
    }

    fun addDeck(deck: Deck) {
        viewModelScope.launch {
            repository.insertDeck(deck)
        }
    }

    fun addFolder(courseName: String, folderName: String, description: String, colorHex: String) {
        viewModelScope.launch {
            val newDeck = Deck(
                category = courseName,
                name = folderName,
                description = description,
                colorHex = colorHex
            )
            val newId = repository.insertDeck(newDeck)
            _selectedDeckId.value = newId
        }
    }

    suspend fun lookupDictionary(word: String): Result<DictionaryEntry> = runCatching {
        val chineseQuery = word.any { it.code in 0x3400..0x9FFF }
        val offline = if (!chineseQuery && settings.value.dictionarySource == "AUTO")
            runCatching { EcdictLookupService.lookup(getApplication(), word) }.getOrNull() else null
        when {
            settings.value.dictionarySource == "ECDICT" -> EcdictLookupService.lookup(getApplication(), word)
            offline != null -> offline
            chineseQuery && settings.value.dictionarySource == "GOOGLE" -> GoogleTranslateLookupService.lookup(word)
            chineseQuery -> runCatching { GoogleTranslateLookupService.lookup(word) }
                .getOrElse { googleError ->
                    runCatching { MyMemoryTranslateLookupService.lookupChinese(word) }
                        .getOrElse { backupError -> error("Google：${googleError.message}\n备用翻译：${backupError.message}") }
                }
            settings.value.dictionarySource == "GOOGLE" -> GoogleTranslateLookupService.lookup(word).let { GoogleTranslateLookupService.translateExample(it) }
            settings.value.dictionarySource == "CAMBRIDGE" -> runCatching { CambridgeDictionaryLookupService.lookup(word) }
                .getOrElse { WiktionaryLookupService.lookup(word) }
            settings.value.dictionarySource == "WIKTIONARY" -> WiktionaryLookupService.lookup(word)
            settings.value.dictionarySource == "FREE" -> FreeDictionaryLookupService.lookup(word)
            else -> runCatching {
                GoogleTranslateLookupService.lookup(word).let { GoogleTranslateLookupService.translateExample(it) }
            }.getOrElse { googleError ->
                runCatching { CambridgeDictionaryLookupService.lookup(word) }.getOrElse { cambridgeError ->
                    runCatching { WiktionaryLookupService.lookup(word) }.getOrElse { wiktionaryError ->
                        runCatching { FreeDictionaryLookupService.lookup(word) }.getOrElse { freeError ->
                            runCatching { MyMemoryTranslateLookupService.lookupEnglish(word) }.getOrElse { translateError ->
                                error("Google：${googleError.message}\n剑桥：${cambridgeError.message}\n维基：${wiktionaryError.message}\n免费字典：${freeError.message}\n备用翻译：${translateError.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    fun addDictionaryEntryToDeck(entry: DictionaryEntry, deckId: Long, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                repository.insertCardsDeduplicating(
                    listOf(
                        Flashcard(
                            deckId = deckId,
                            word = entry.word.trim(),
                            phonetic = entry.phonetic.trim(),
                            partOfSpeech = entry.partOfSpeech.trim(),
                            definition = entry.definition.trim(),
                            exampleSentence = entry.exampleSentence.trim(),
                            exampleTranslation = entry.exampleTranslation.trim(),
                            createdAt = now,
                            nextReviewTimestamp = now,
                            sortOrder = repository.nextSortOrder(deckId, 1)
                        )
                    )
                ) > 0
            }.onSuccess {
                _dataMessage.value = if (it) "已加入文件夹" else "这个文件夹已有相同单词"
                onComplete(it)
            }.onFailure {
                _dataMessage.value = "加入失败：${it.message ?: "未知错误"}"
                onComplete(false)
            }
        }
    }

    fun createFolderAndAddDictionaryEntry(
        courseName: String,
        folderName: String,
        description: String,
        colorHex: String,
        entry: DictionaryEntry,
        onComplete: (Boolean) -> Unit = {}
    ) = createFolderAndCopyCard(
        courseName = courseName,
        folderName = folderName,
        description = description,
        colorHex = colorHex,
        card = Flashcard(
            deckId = 0,
            word = entry.word.trim(),
            phonetic = entry.phonetic.trim(),
            partOfSpeech = entry.partOfSpeech.trim(),
            definition = entry.definition.trim(),
            exampleSentence = entry.exampleSentence.trim(),
            exampleTranslation = entry.exampleTranslation.trim()
        ),
        onComplete = onComplete
    )

    fun updateDeck(deck: Deck) {
        viewModelScope.launch {
            repository.updateDeck(deck)
        }
    }

    fun deleteDeck(deck: Deck) {
        viewModelScope.launch {
            repository.deleteDeck(deck)
            if (_selectedDeckId.value == deck.id) {
                _selectedDeckId.value = null
            }
        }
    }

    fun speakText(text: String) {
        val value = settings.value
        ttsManager.speak(
            text = text,
            rate = value.speechRate,
            voiceName = value.ttsVoiceName,
            voiceStyle = value.ttsVoiceStyle
        )
    }

    fun speakLearningCard(card: Flashcard, onComplete: () -> Unit = {}) {
        val value = settings.value
        val segments = buildList {
            if (value.ttsReadWord) add(SpeechSegment(card.word, "en-US"))
            if (value.ttsSpellWord) {
                val spelling = card.word.filter(Char::isLetter).uppercase().toCharArray().joinToString(", ")
                if (spelling.isNotBlank()) add(SpeechSegment(spelling, "en-US"))
            }
            if (value.ttsReadDefinition && card.definition.isNotBlank()) {
                add(SpeechSegment(card.definition, "zh-TW"))
            }
            if (value.ttsReadPhonetic && card.phonetic.isNotBlank()) {
                add(SpeechSegment(card.phonetic, "en-US"))
            }
            if (value.ttsReadPartOfSpeech && card.partOfSpeech.isNotBlank()) {
                add(SpeechSegment(expandPartOfSpeechForSpeech(card.partOfSpeech), "en-US"))
            }
            if (value.ttsReadExample && card.exampleSentence.isNotBlank()) {
                add(SpeechSegment(card.exampleSentence, "en-US"))
            }
            if (value.ttsReadExampleTranslation && card.exampleTranslation.isNotBlank()) {
                add(SpeechSegment(card.exampleTranslation, "zh-TW"))
            }
        }
        ttsManager.speakSequence(
            segments = segments,
            rate = value.speechRate,
            voiceName = value.ttsVoiceName,
            voiceStyle = value.ttsVoiceStyle,
            repetitions = value.ttsGroupRepetitions,
            onComplete = onComplete
        )
    }

    fun speakLearningCardFront(card: Flashcard, onComplete: () -> Unit = {}) {
        val value = settings.value
        val segments = buildList {
            if (value.ttsFrontReadWord) add(SpeechSegment(card.word, "en-US"))
            if (value.ttsFrontSpellWord) {
                val spelling = card.word.filter(Char::isLetter).uppercase().toCharArray().joinToString(", ")
                if (spelling.isNotBlank()) add(SpeechSegment(spelling, "en-US"))
            }
            if (value.ttsFrontReadPartOfSpeech && card.partOfSpeech.isNotBlank()) {
                add(SpeechSegment(expandPartOfSpeechForSpeech(card.partOfSpeech), "en-US"))
            }
        }
        ttsManager.speakSequence(
            segments = segments,
            rate = value.speechRate,
            voiceName = value.ttsVoiceName,
            voiceStyle = value.ttsVoiceStyle,
            repetitions = value.ttsFrontRepetitions,
            onComplete = onComplete
        )
    }

    private fun expandPartOfSpeechForSpeech(value: String): String = value
        .replace(Regex("(?i)\\badj\\.?"), "adjective")
        .replace(Regex("(?i)\\badv\\.?"), "adverb")
        .replace(Regex("(?i)\\bvt\\.?"), "transitive verb")
        .replace(Regex("(?i)\\bvi\\.?"), "intransitive verb")
        .replace(Regex("(?i)\\bv\\.?"), "verb")
        .replace(Regex("(?i)\\bn\\.?"), "noun")
        .replace("/", ", ")

    fun stopSpeaking() {
        ttsManager.stop()
    }

    /** Parse pasted or recognized text immediately; AI enrichment is explicit. */
    fun parseOcrPhotoText(text: String, deckId: Long) {
        val initialCandidates = OcrWordParser.parseTextToCards(text, deckId)
        _ocrCandidates.value = initialCandidates
        _importUiState.value = ImportUiState.Success(initialCandidates.size)
    }

    /**
     * Extracts and parses a PDF, document, or image locally.
     * AI enrichment is a separate, explicit action on the preview screen.
     */
    fun parseDocumentUri(context: Context, uri: Uri, deckId: Long, onComplete: (List<OcrCardCandidate>) -> Unit = {}) {
        viewModelScope.launch {
            _importUiState.value = ImportUiState.Loading
            try {
                val (_, candidates) = DocumentParser.parseDocumentToCards(context, uri)
                _ocrCandidates.value = candidates.map { it.copy() }
                _importUiState.value = ImportUiState.Success(candidates.size)
                onComplete(candidates)
            } catch (error: Exception) {
                _importUiState.value = ImportUiState.Error(error.localizedMessage ?: "文件读取失败")
                onComplete(emptyList())
            }
        }
    }

    /**
     * Triggers Gemini AI to batch complete missing phonetics, part of speech, definitions, and example sentences.
     */
    fun batchAiEnrichOcrCandidates() {
        val currentList = _ocrCandidates.value
        if (currentList.isEmpty()) return

        viewModelScope.launch {
            _importUiState.value = ImportUiState.Loading
            try {
                val enriched = GeminiService.batchEnrichCandidates(currentList)
                _ocrCandidates.value = enriched
                _importUiState.value = ImportUiState.Success(enriched.size)
            } catch (error: Exception) {
                _importUiState.value = ImportUiState.Error(error.localizedMessage ?: "AI 补齐暂时无法使用")
            }
        }
    }

    fun importOcrToNewFolder(candidates:List<OcrCardCandidate>,sourceId:Long,onComplete:(Boolean)->Unit={}){
        val source=allDecks.value.firstOrNull{it.id==sourceId}?:return
        val cards=candidates.filter{it.isSelected&&it.word.isNotBlank()}.map{Flashcard(deckId=0,word=it.word,definition=it.definition.ifBlank{"待补齐"},phonetic=it.phonetic,partOfSpeech=it.partOfSpeech,exampleSentence=it.exampleSentence,exampleTranslation=it.exampleTranslation)}
        viewModelScope.launch{importSharedFolders(listOf(com.example.data.importer.SharedFolder(source.name+"（识别导入）",source.category,source.description,source.colorHex,cards)),source.category,false).onSuccess{_dataMessage.value="已导入新文件夹，精熟度已重置；可在文件夹页撤回";onComplete(true)}.onFailure{_dataMessage.value="导入失败：${it.localizedMessage}";onComplete(false)}}
    }
    fun importOcrCandidates(candidates: List<OcrCardCandidate>, targetDeckId: Long,onComplete:(Boolean)->Unit={}) {
        val selectedCandidates = candidates
            .filter { it.isSelected && it.word.isNotBlank() }
            .distinctBy { it.word.trim().lowercase() }
        if (selectedCandidates.isEmpty()) {onComplete(false);return}

        viewModelScope.launch {
            runCatching {
            // Only exact dictionary data is applied automatically. AI changes are
            // made in the preview screen before the user confirms the import.
            val baseEnriched = OcrWordParser.enrichCandidatesWithDictionary(selectedCandidates)
            val bottomSortOrder = repository.nextBottomSortOrder(targetDeckId)

            val cards = baseEnriched.mapIndexed { index, candidate ->
                val dict = DictionaryEngine.findExact(candidate.word)
                val finalDef = candidate.definition.takeIf { it.isNotBlank() }
                    ?.let(OcrWordParser::cleanDefinition)
                    ?: dict?.definition.orEmpty()
                val finalSentence = candidate.exampleSentence.ifBlank {
                    dict?.exampleSentence.orEmpty()
                }
                val finalPhonetic = candidate.phonetic.ifBlank { dict?.phonetic.orEmpty() }
                val finalPos = candidate.partOfSpeech.ifBlank { dict?.partOfSpeech.orEmpty() }

                Flashcard(
                    deckId = targetDeckId,
                    word = candidate.word.trim(),
                    phonetic = finalPhonetic,
                    partOfSpeech = finalPos,
                    definition = finalDef,
                    exampleSentence = finalSentence,
                    exampleTranslation = candidate.exampleTranslation.ifBlank {
                        dict?.exampleTranslation.orEmpty()
                    },
                    // Cards are queried in descending sortOrder. Starting below
                    // the existing minimum appends this scan after every saved card
                    // while preserving the image's top-to-bottom order.
                    sortOrder = bottomSortOrder - index
                )
            }
            val insertedIds=db.withTransaction{val known=db.flashcardDao().getCardsByDeckOnce(targetDeckId).mapTo(hashSetOf()){it.normalizedWord};cards.filter{known.add(it.normalizedWord)}.map{db.flashcardDao().insertCard(it)}}
            _scanImportUndoIds.value=insertedIds
            val insertedCount = insertedIds.size
            val duplicateCount = cards.size - insertedCount
            _ocrCandidates.value = emptyList()
            _importUiState.value = ImportUiState.Success(insertedCount)
            _dataMessage.value = if (duplicateCount > 0) {
                "已依画面顺序导入 $insertedCount 张，略过 $duplicateCount 张重复单词"
            } else {
                "已依画面顺序导入 $insertedCount 张单词卡"
            }
            }.onSuccess{onComplete(true)}.onFailure{_ocrCandidates.value=selectedCandidates;_dataMessage.value="导入失败：${it.localizedMessage}，识别结果保留";onComplete(false)}
        }
    }

    fun importExternalCards(
        candidates: List<ExternalCardCandidate>,
        targetDeckId: Long,
        onComplete: (Int) -> Unit = {}
    ) {
        val selected = candidates
            .filter { it.selected && it.word.isNotBlank() && it.definition.isNotBlank() }
            .distinctBy { it.word.trim().lowercase() }
        if (selected.isEmpty()) {
            onComplete(0)
            return
        }
        viewModelScope.launch {
            val topSortOrder = repository.nextSortOrder(targetDeckId, selected.size)
            val cards = selected.mapIndexed { index, candidate ->
                val dictionary = DictionaryEngine.findExact(candidate.word)
                Flashcard(
                    deckId = targetDeckId,
                    word = candidate.word.trim(),
                    phonetic = candidate.phonetic.ifBlank { dictionary?.phonetic.orEmpty() },
                    partOfSpeech = candidate.partOfSpeech.ifBlank { dictionary?.partOfSpeech.orEmpty() },
                    definition = OcrWordParser.cleanDefinition(candidate.definition),
                    exampleSentence = candidate.exampleSentence.ifBlank { dictionary?.exampleSentence.orEmpty() },
                    exampleTranslation = candidate.exampleTranslation.ifBlank {
                        dictionary?.exampleTranslation.orEmpty()
                    },
                    notes = candidate.sourceName.takeIf { it.isNotBlank() }?.let { "导入来源：$it" }.orEmpty(),
                    sortOrder = topSortOrder - index
                )
            }
            val insertedCount = repository.insertCardsDeduplicating(cards)
            val duplicateCount = cards.size - insertedCount
            _dataMessage.value = if (duplicateCount > 0) {
                "已导入 $insertedCount 张，略过或更新 $duplicateCount 张同文件夹重复单词"
            } else {
                "已依原始顺序导入 $insertedCount 张单词卡"
            }
            onComplete(insertedCount)
        }
    }

    fun openAssistant(contextDeckId: Long?) {
        assistantContextDeckId = contextDeckId
        val availableIds = allDecks.value.mapTo(linkedSetOf()) { it.id }
        _assistantSelectedDeckIds.value = contextDeckId?.let(::setOf) ?: when {
            settings.value.assistantScopeMode == "ALL" -> availableIds
            else -> settings.value.assistantScopeDeckIds.filterTo(linkedSetOf()) { it in availableIds }
                .ifEmpty { availableIds }
        }
        val currentId = _assistantCurrentConversationId.value
        if (currentId == null || _assistantConversations.value.none { it.id == currentId }) {
            val existing = _assistantConversations.value.maxByOrNull { it.updatedAt }
            if (existing != null) selectAssistantConversation(existing.id)
            else newAssistantConversation(false)
        } else {
            refreshAssistantMessages()
        }
    }

    fun setAssistantScope(deckIds: Set<Long>) {
        val availableIds = allDecks.value.mapTo(hashSetOf()) { it.id }
        _assistantSelectedDeckIds.value = deckIds.filterTo(linkedSetOf()) { it in availableIds }
        assistantContextDeckId = _assistantSelectedDeckIds.value.singleOrNull()
        viewModelScope.launch {
            settingsRepository.setAssistantScope(
                allSelected = availableIds.isNotEmpty() && _assistantSelectedDeckIds.value == availableIds,
                deckIds = _assistantSelectedDeckIds.value
            )
        }
    }

    fun newAssistantConversation(incognito: Boolean) {
        val now = System.currentTimeMillis()
        val conversation = AssistantConversation(
            id = UUID.randomUUID().toString(),
            title = if (incognito) "无痕对话" else "新对话",
            createdAt = now,
            updatedAt = now,
            incognito = incognito
        )
        _assistantConversations.value = listOf(conversation) + _assistantConversations.value
        _assistantCurrentConversationId.value = conversation.id
        _assistantPendingAction.value = null
        _assistantUndoAction.value = null
        refreshAssistantMessages()
        persistAssistantState()
    }

    fun selectAssistantConversation(conversationId: String) {
        if (_assistantConversations.value.none { it.id == conversationId }) return
        _assistantCurrentConversationId.value = conversationId
        _assistantPendingAction.value = null
        _assistantUndoAction.value = null
        refreshAssistantMessages()
    }

    fun deleteAssistantConversation(conversationId: String) {
        assistantAllMessages.filter { it.conversationId == conversationId }
            .flatMap { it.attachmentUris }
            .forEach(::deletePersistedAssistantAttachment)
        assistantAllMessages.removeAll { it.conversationId == conversationId }
        _assistantConversations.value = _assistantConversations.value.filterNot { it.id == conversationId }
        if (_assistantCurrentConversationId.value == conversationId) {
            val next = _assistantConversations.value.firstOrNull()
            if (next == null) newAssistantConversation(false)
            else selectAssistantConversation(next.id)
        }
        persistAssistantState()
    }

    fun deleteAssistantMessage(messageId: String) {
        val message = assistantAllMessages.firstOrNull { it.id == messageId } ?: return
        message.attachmentUris.forEach(::deletePersistedAssistantAttachment)
        assistantAllMessages.removeAll { it.id == messageId }
        if (_assistantCurrentConversationId.value == message.conversationId) refreshAssistantMessages()
        persistAssistantState()
    }

    fun clearAssistantChats() {
        assistantAllMessages.flatMap { it.attachmentUris }
            .forEach(::deletePersistedAssistantAttachment)
        assistantAllMessages.clear()
        _assistantConversations.value = emptyList()
        _assistantCurrentConversationId.value = null
        _assistantMessages.value = emptyList()
        _assistantPendingAction.value = null
        _assistantUndoAction.value = null
        assistantChatStore.clear()
        newAssistantConversation(false)
    }

    private fun deletePersistedAssistantAttachment(rawUri: String) {
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return
        if (uri.scheme != "file") return
        val file = uri.path?.let(::File) ?: return
        val attachmentDirectory = File(
            getApplication<Application>().filesDir,
            "assistant_images"
        ).canonicalFile
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (target.parentFile == attachmentDirectory) target.delete()
    }

    private var sharedImportIds:List<Long> = emptyList()
    private var sharedImportCardIds:Set<Long> = emptySet()
    private val _folderShareUndoIds=MutableStateFlow<List<Long>>(emptyList())
    val folderShareUndoIds:StateFlow<List<Long>> = _folderShareUndoIds.asStateFlow()
    private val _scanImportUndoIds=MutableStateFlow<List<Long>>(emptyList())
    val scanImportUndoIds:StateFlow<List<Long>> = _scanImportUndoIds.asStateFlow()
    fun undoLastScanImport(){val ids=_scanImportUndoIds.value;if(ids.isEmpty())return;viewModelScope.launch{runCatching{db.withTransaction{ids.forEach{id->db.flashcardDao().getCardById(id)?.let{db.flashcardDao().deleteCard(it)}}}}.onSuccess{_scanImportUndoIds.value=emptyList();_dataMessage.value="已撤回上次单词导入，原有卡片保留"}.onFailure{_dataMessage.value="撤回失败：${it.localizedMessage}"}}}
    suspend fun importSharedFolders(folders:List<com.example.data.importer.SharedFolder>,course:String,skipDuplicates:Boolean):Result<List<Long>> = runCatching {
        require(folders.isNotEmpty()) { "请选择文件夹" }
        val createdCardIds=mutableSetOf<Long>()
        withContext(Dispatchers.IO){db.withTransaction {
            val existing=db.deckDao().getAllDecksOnce().toMutableList()
            val known=db.flashcardDao().getAllCards().first().mapTo(hashSetOf()){it.normalizedWord}
            val ids=mutableListOf<Long>()
            folders.forEach{source->
                val category=course.trim().ifBlank{source.course};var name=source.name;var suffix=1
                while(existing.any{it.category==category&&it.name==name}){name="${source.name}（导入${if(suffix==1)"" else suffix}）";suffix++}
                val now=System.currentTimeMillis();val deck=Deck(name=name,category=category,description=source.description,colorHex=source.color,sortOrder=(existing.maxOfOrNull{it.sortOrder}?:now)+1)
                val id=db.deckDao().insertDeck(deck);existing.add(deck.copy(id=id));ids.add(id)
                val seen=hashSetOf<String>();val safe=source.cards.filter{seen.add(it.normalizedWord)&&(!skipDuplicates||it.normalizedWord !in known)}
                safe.forEachIndexed{index,card->createdCardIds.add(db.flashcardDao().insertCard(card.copy(id=0,deckId=id,isMastered=false,isFavorite=false,isSuspended=false,mistakeCount=0,intervalDays=0,easeFactor=2.5f,repetitionCount=0,nextReviewTimestamp=now,lastReviewedTimestamp=null,createdAt=now,sortOrder=now+safe.size-index)))}
                known.addAll(safe.map{it.normalizedWord})
            };ids
        }}.also{sharedImportIds=it;sharedImportCardIds=createdCardIds;_folderShareUndoIds.value=it}
    }
    suspend fun undoSharedFolders(ids:List<Long>):Result<Unit> = runCatching {
        require(ids.isNotEmpty()&&ids.toSet()==sharedImportIds.toSet()){ "找不到可撤回的导入批次" }
        withContext(Dispatchers.IO){db.withTransaction{
            require(ids.all{id->db.flashcardDao().getCardsByDeckOnce(id).all{it.id in sharedImportCardIds}}){"文件夹已加入其他卡片，请先移出它们再撤回，避免删除原有数据"}
            sharedImportCardIds.forEach{id->db.flashcardDao().getCardById(id)?.let{db.flashcardDao().deleteCard(it)}}
            ids.forEach{id->db.deckDao().getDeckById(id)?.let{db.deckDao().deleteDeck(it)}}
        }}
        sharedImportIds=emptyList()
        sharedImportCardIds=emptySet()
        _folderShareUndoIds.value=emptyList()
    }

    fun sendAssistantMessage(text: String, attachments: List<Uri> = emptyList()) {
        val safeAttachments = attachments.distinct().take(5)
        val attachmentLabels = safeAttachments.mapIndexed { index, uri ->
            assistantAttachmentLabel(uri, index)
        }
        val request = text.trim().take(4_000).ifBlank {
            if (safeAttachments.isNotEmpty()) "请分析我附加的文件。" else ""
        }
        if ((request.isBlank() && safeAttachments.isEmpty()) || _assistantBusy.value) return
        if (_assistantCurrentConversationId.value == null) newAssistantConversation(false)
        val conversationId = _assistantCurrentConversationId.value ?: return
        val userMessageId = appendAssistantMessage(
            "USER",
            request,
            attachmentUris = safeAttachments.map(Uri::toString)
        )
        updateAssistantConversationTitle(conversationId, request)

        val targetDeck = resolveAssistantDeck(request)
        if(safeAttachments.isEmpty() && (Regex("(?:写|生成|创建|产生|撰写).*(?:文章|故事|阅读)|(?:文章|故事).*(?:生成|撰写)").containsMatchIn(request)||request.contains("继续补齐文章"))){
            generateFullFolderArticle(request,targetDeck,request.contains("继续补齐文章"));return
        }
        val asksForSort = request.contains("排序") || request.contains("排列")
        val mentionsAlphabet = request.contains("字母") ||
            request.contains("A-Z", ignoreCase = true) || request.contains("英文顺序")
        if (safeAttachments.isEmpty() && asksForSort && mentionsAlphabet) {
            if (targetDeck == null) {
                appendAssistantMessage("ASSISTANT", "请先进入一个文件夹，或在消息中告诉我要整理哪个文件夹。")
                return
            }
            if (targetDeck.id !in _assistantSelectedDeckIds.value) {
                appendAssistantMessage(
                    "ASSISTANT",
                    "我可以读取「${targetDeck.name}」的内容，但它不在目前允许编辑的范围内。请先在输入框上方勾选它，才能修改排序。"
                )
                return
            }
            prepareAlphabeticalSort(targetDeck, descending = request.contains("Z-A", ignoreCase = true))
            return
        }

        val requestGeneration=++assistantRequestGeneration
        assistantRequestJob = viewModelScope.launch {
            _assistantBusy.value = true
            _assistantSendOutcome.value = null
            _assistantStatus.value = if (safeAttachments.isEmpty()) null else "正在准备附件…"
            runCatching {
                if (safeAttachments.isNotEmpty()) {
                    val persistedUris = withContext(Dispatchers.IO) {
                        persistAssistantAttachments(safeAttachments)
                    }
                    updateAssistantMessageAttachments(userMessageId, persistedUris)
                }
                if (!settings.value.aiEnabled || !settings.value.usePersonalAiApi) {
                    error("请先在设置中启用 AI，并加入至少一组可用的 API Key")
                }
                val imageAttachments = mutableListOf<DirectAiService.ImagePayload>()
                val textAttachments = StringBuilder()
                val attachmentSummary = mutableListOf<String>()
                safeAttachments.forEachIndexed { index, uri ->
                    val resolver = getApplication<Application>().contentResolver
                    val mimeType = resolver.getType(uri).orEmpty().lowercase()
                    val label = attachmentLabels[index]
                    val lowerLabel = label.lowercase()
                    val isPdf = mimeType.contains("pdf") || lowerLabel.endsWith(".pdf")
                    val isKnownImage = mimeType.startsWith("image/") ||
                        listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic", ".heif")
                            .any(lowerLabel::endsWith)
                    _assistantStatus.value = "正在读取附件 ${index + 1}/${safeAttachments.size}：$label"

                    if (isPdf) {
                        val remainingImageSlots = 5 - imageAttachments.size
                        require(remainingImageSlots > 0) { "图片与 PDF 页面合计最多 5 张" }
                        val pages = DocumentParser.renderPdfPagesForAi(
                            getApplication(),
                            uri,
                            minOf(settings.value.pdfPageLimit, remainingImageSlots)
                        )
                        require(pages.isNotEmpty()) { "$label 没有可读取的页面" }
                        imageAttachments += pages
                        attachmentSummary += "$label：${pages.size} 页 PDF 图片"
                    } else if (isKnownImage) {
                        require(imageAttachments.size < 5) { "图片与 PDF 页面合计最多 5 张" }
                        imageAttachments += AiImagePreprocessor.prepare(getApplication(), uri)
                        attachmentSummary += "$label：图片"
                    } else {
                        val extracted = DocumentParser.extractTextFromUri(
                            getApplication(), uri, settings.value.pdfPageLimit
                        ).trim().take(20_000 - textAttachments.length.coerceAtMost(20_000))
                        require(extracted.isNotBlank()) { "$label 没有读取到可传给 AI 的文字" }
                        textAttachments.append("\n\n--- 附件：$label ---\n").append(extracted)
                        attachmentSummary += "$label：${extracted.length} 字"
                    }
                }
                val contextDeck = targetDeck
                val contextCards = selectAssistantContextCards(request, contextDeck)
                val showProgress = isAssistantOperationRequest(request)
                val editableIds = _assistantSelectedDeckIds.value
                _assistantStatus.value = if (!showProgress) null else assistantDetailedStatus(
                    request = request,
                    contextDeck = contextDeck,
                    contextCardCount = contextCards.size,
                    editableDeckCount = editableIds.size
                )
                GeminiService.runAssistant(
                    buildAssistantPrompt(
                        buildString {
                            append(request)
                            if (attachmentSummary.isNotEmpty()) {
                                append("\n\n附件读取摘要：\n")
                                append(attachmentSummary.joinToString("\n"))
                                append("\n请确实阅读附件内容后再回答，不可声称没有收到附件。")
                            }
                            append(textAttachments)
                        },
                        contextDeck,
                        contextCards
                    ),
                    imageAttachments
                )
            }.onSuccess { result ->
                handleAssistantResult(result, targetDeck)
                if (safeAttachments.isNotEmpty()) {
                    _assistantSendOutcome.value = AssistantSendOutcome(succeeded = true)
                }
            }.onFailure { error ->
                if (error !is CancellationException) {
                    if (safeAttachments.isNotEmpty()) {
                        _assistantSendOutcome.value = AssistantSendOutcome(
                            succeeded = false,
                            errorMessage = error.localizedMessage ?: "附件无法读取或传送"
                        )
                    }
                    appendAssistantMessage(
                        "ASSISTANT",
                        "这次没有完成：${error.localizedMessage ?: "AI 服务暂时无法使用"}"
                    )
                }
            }
            if(requestGeneration==assistantRequestGeneration){_assistantStatus.value=null;_assistantBusy.value=false;assistantRequestJob=null}
        }
    }

    private fun assistantAttachmentLabel(uri: Uri, index: Int): String {
        val resolver = getApplication<Application>().contentResolver
        val displayName = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return displayName?.trim()?.take(120)?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.take(120)
            ?: "附件 ${index + 1}"
    }

    private fun persistAssistantAttachments(uris: List<Uri>): List<String> {
        val application = getApplication<Application>()
        val resolver = application.contentResolver
        val imageDirectory = File(application.filesDir, "assistant_images").apply { mkdirs() }
        return uris.map { uri ->
            val mimeType = resolver.getType(uri).orEmpty().lowercase()
            if (!mimeType.startsWith("image/")) return@map uri.toString()
            runCatching {
                val extension = when (mimeType) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    "image/heic", "image/heif" -> "heic"
                    else -> "jpg"
                }
                val target = File(imageDirectory, "${UUID.randomUUID()}.$extension")
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use(input::copyTo)
                } ?: error("无法保存图片附件")
                Uri.fromFile(target).toString()
            }.getOrDefault(uri.toString())
        }
    }

    private fun generateFullFolderArticle(request:String,deck:Deck?,resume:Boolean){
        val saved=if(resume)assistantAllMessages.lastOrNull{it.kind=="ARTICLE"&&it.conversationId==_assistantCurrentConversationId.value} else null
        val savedPayload=saved?.let{runCatching{JSONObject(it.payload)}.getOrNull()}
        val words=if(savedPayload?.has("targetWords")==true){val array=savedPayload.getJSONArray("targetWords");(0 until array.length()).map{array.getString(it)}} else allCards.value.filter{if(deck!=null)it.deckId==deck.id else it.deckId in _assistantSelectedDeckIds.value}.map{it.word}.distinctBy{it.lowercase()}
        if(words.isEmpty()){appendAssistantMessage("ASSISTANT","所选文件夹没有单词，请先选择文件夹。");return}
        val id=saved?.id?:appendAssistantMessage("ASSISTANT","正在规划包含全部 ${words.size} 个单词的文章…",kind="ARTICLE")
        var article=if(resume)savedPayload?.optString("article").orEmpty() else ""
        var translation=if(resume)savedPayload?.optString("articleTranslation").orEmpty() else ""
        fun save(){
            val missing=com.example.util.ArticleCoverage.missing(article,words)
            val payload=JSONObject().put("type","ARTICLE").put("article",article).put("articleTranslation",translation).put("targetWords",JSONArray(words)).put("targetCardIds",JSONArray(allCards.value.filter{it.word in words && (if(deck!=null)it.deckId==deck.id else it.deckId in _assistantSelectedDeckIds.value)}.map{it.id})).put("missingWords",JSONArray(missing)).put("coverage","${words.size-missing.size}/${words.size}").put("complete",missing.isEmpty())
            val index=assistantAllMessages.indexOfFirst{it.id==id};if(index>=0){assistantAllMessages[index]=assistantAllMessages[index].copy(content="${if(missing.isEmpty())"文章已完成" else "文章草稿／尚未完成"} · 涵盖 ${words.size-missing.size}/${words.size} 字\n\n$article${if(missing.isNotEmpty())"\n\n尚缺：${missing.joinToString()}\n可在聊天室输入「继续补齐文章」。" else ""}",payload=payload.toString());refreshAssistantMessages();persistAssistantState()}
        }
        val requestGeneration=++assistantRequestGeneration
        assistantRequestJob=viewModelScope.launch{
            _assistantBusy.value=true
            try{
                check(settings.value.aiEnabled&&settings.value.usePersonalAiApi){"请先启用个人 AI API"}
                save()
                com.example.util.ArticleCoverage.missing(article,words).chunked(35).forEach{batch->
                    for(attempt in 0..2){
                        val missing=com.example.util.ArticleCoverage.missing(article,batch);if(missing.isEmpty())break
                        _assistantStatus.value="文章涵盖 ${words.size-com.example.util.ArticleCoverage.missing(article,words).size}/${words.size} 字；正在撰写下一章…"
                        val result=GeminiService.runAssistant("""请接续同一篇自然英文故事的一个章节，在英文正文使用目标清单每个完整单词／短语，允许合理词形变化。不可只列单词，也不可省略。只输出 JSON：{"article":"英文正文","articleTranslation":"简体中文翻译"}。用户要求：$request。目标单词：${JSONArray(missing)}。前文最后一段：${article.takeLast(2500)}""")
                        val chapter=result.optString("article").trim();check(chapter.isNotBlank()){"AI 未返回有效文章，已完成内容保留"}
                        article+=(if(article.isBlank())"" else "\n\n")+chapter
                        translation+=(if(translation.isBlank())"" else "\n\n")+result.optString("articleTranslation")
                        save()
                    }
                }
            }catch(error:Exception){save();if(error !is CancellationException)appendAssistantMessage("ASSISTANT","文章尚未完成：${error.localizedMessage}。草稿已保留，可输入「继续补齐文章」。")}
            finally{if(requestGeneration==assistantRequestGeneration){_assistantBusy.value=false;_assistantStatus.value=null;assistantRequestJob=null}}
        }
    }

    private fun updateAssistantMessageAttachments(messageId: String, uris: List<String>) {
        val index = assistantAllMessages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        assistantAllMessages[index] = assistantAllMessages[index].copy(attachmentUris = uris)
        refreshAssistantMessages()
        persistAssistantState()
    }

    fun stopAssistantRequest() {
        assistantRequestGeneration++
        assistantRequestJob?.cancel()
        assistantRequestJob = null
        _assistantStatus.value = null
        _assistantBusy.value = false
    }

    fun cancelAssistantAction() {
        _assistantPendingAction.value = null
        appendAssistantMessage("ASSISTANT", "已取消，没有修改任何数据。", kind = "STATUS")
    }

    fun completeAssistantQuiz(messageId: String, correctCount: Int, totalCount: Int) {
        updateAssistantQuizPayload(messageId) { root ->
            val safeTotal = totalCount.coerceAtLeast(0)
            val safeCorrect = correctCount.coerceIn(0, safeTotal)
            val completedAt = System.currentTimeMillis()
            val attempts = root.optJSONArray("attempts") ?: JSONArray()
            attempts.put(
                JSONObject()
                    .put("correct", safeCorrect)
                    .put("total", safeTotal)
                    .put("completedAt", completedAt)
            )
            root.put("attempts", attempts)
                .put("completed", true)
                .put("currentIndex", safeTotal)
                .put("selectedIndex", -1)
                .put("latestCorrect", safeCorrect)
                .put("latestTotal", safeTotal)
                .put("completedAt", completedAt)
        }
    }

    fun restartAssistantQuiz(messageId: String) {
        updateAssistantQuizPayload(messageId) { root ->
            root.put("completed", false)
                .put("currentIndex", 0)
                .put("selectedIndex", -1)
                .put("correctCount", 0)
                .put("wrongAnswers", JSONArray())
                .put("attempts", JSONArray())
                .remove("latestCorrect")
            root.remove("latestTotal")
            root.remove("completedAt")
        }
    }

    fun saveAssistantQuizProgress(
        messageId: String,
        currentIndex: Int,
        selectedIndex: Int,
        correctCount: Int,
        wrongAnswers: Map<Int, Int>
    ) {
        updateAssistantQuizPayload(messageId) { root ->
            val questions = root.optJSONArray("questions") ?: JSONArray()
            val total = questions.length()
            root.put("completed", false)
                .put("currentIndex", currentIndex.coerceIn(0, total.coerceAtLeast(0)))
                .put("selectedIndex", selectedIndex)
                .put("correctCount", correctCount.coerceIn(0, total.coerceAtLeast(0)))
                .put("updatedAt", System.currentTimeMillis())
                .put(
                    "wrongAnswers",
                    JSONArray(wrongAnswers.entries.sortedBy { it.key }.map {
                        JSONObject().put("questionIndex", it.key).put("selectedIndex", it.value)
                    })
                )
        }
    }

    fun confirmAssistantAction() {
        val action = _assistantPendingAction.value ?: return
        _assistantPendingAction.value = null
        viewModelScope.launch {
            _assistantBusy.value = true
            _assistantStatus.value = "正在执行：${action.title}"
            runCatching {
                when (action.type) {
                    "SORT" -> {
                        val payload = JSONObject(action.payload)
                        val newOrder = payload.getJSONArray("newOrder").longValues()
                        repository.reorderCards(action.deckId, newOrder)
                        AssistantPendingAction(
                            id = UUID.randomUUID().toString(),
                            type = "RESTORE_ORDER",
                            deckId = action.deckId,
                            title = "恢复排序",
                            description = "恢复执行 AI 排序前的单词顺序",
                            payload = JSONObject()
                                .put("order", payload.getJSONArray("oldOrder"))
                                .toString()
                        )
                    }
                    "COUNTABILITY" -> {
                        val updates = JSONArray(action.payload)
                        val currentById = allCards.value.associateBy { it.id }
                        val changed = buildList {
                            for (index in 0 until updates.length()) {
                                val item = updates.optJSONObject(index) ?: continue
                                val card = currentById[item.optLong("cardId")] ?: continue
                                add(card.copy(partOfSpeech = item.optString("newPartOfSpeech")))
                            }
                        }
                        repository.updateCards(changed)
                        AssistantPendingAction(
                            id = UUID.randomUUID().toString(),
                            type = "RESTORE_POS",
                            deckId = action.deckId,
                            title = "恢复词性修改",
                            description = "恢复 AI 补充可数性前的词性",
                            payload = action.payload
                        )
                    }
                    "CREATE_FOLDER" -> {
                        val payload = JSONObject(action.payload)
                        val name = payload.getString("name").trim()
                        val category = payload.optString("category", "通用").trim().ifBlank { "通用" }
                        val duplicate = allDecks.value.any {
                            it.name.equals(name, ignoreCase = true) &&
                                it.category.equals(category, ignoreCase = true)
                        }
                        check(!duplicate) { "「$category」课程中已经有同名文件夹" }
                        val newId = repository.insertDeck(
                            Deck(
                                name = name,
                                category = category,
                                description = payload.optString("description").trim().take(200),
                                colorHex = payload.optString("colorHex")
                                    .takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }
                                    ?: "#426B63"
                            )
                        )
                        AssistantPendingAction(
                            id = UUID.randomUUID().toString(),
                            type = "DELETE_CREATED_FOLDER",
                            deckId = newId,
                            title = "恢复添加文件夹",
                            description = "删除刚创建的「$name」",
                            payload = ""
                        )
                    }
                    "UPDATE_FOLDER" -> {
                        val payload = JSONObject(action.payload)
                        val current = allDecks.value.firstOrNull { it.id == action.deckId }
                            ?: error("找不到要修改的文件夹")
                        val newName = payload.getString("name").trim().take(60)
                        val newCategory = payload.getString("category").trim().take(40)
                        require(newName.isNotBlank() && newCategory.isNotBlank()) { "名称与课程不能空白" }
                        check(allDecks.value.none {
                            it.id != current.id && it.name.equals(newName, true) && it.category.equals(newCategory, true)
                        }) { "目标课程中已经有同名文件夹" }
                        repository.updateDeck(current.copy(name = newName, category = newCategory))
                        AssistantPendingAction(
                            id = UUID.randomUUID().toString(), type = "RESTORE_FOLDER",
                            deckId = current.id, title = "恢复文件夹名称与课程",
                            description = "恢复为「${current.name}」／${current.category}",
                            payload = JSONObject().put("name", current.name).put("category", current.category).toString()
                        )
                    }
                    "CREATE_GRAMMAR" -> {
                        val payload = JSONObject(action.payload)
                        val draft = parseGrammarDraft(payload.getJSONObject("grammar"))
                        val (id, snapshot) = db.withTransaction {
                            val id = createAssistantGrammar(db, payload.getLong("libraryId"), draft)
                            id to assistantGrammarSnapshot(db, id)
                        }
                        AssistantPendingAction(UUID.randomUUID().toString(), "DELETE_CREATED_GRAMMAR", id,
                            "恢复添加语法", "移除刚添加的「${draft.title}」；若已有后续修改或学习则不会删除。",
                            JSONObject().put("snapshot", snapshot).toString())
                    }
                    "UPDATE_GRAMMAR" -> {
                        val payload = JSONObject(action.payload)
                        val current = db.grammarNoteDao().getById(action.deckId)
                            ?: error("找不到要修改的语法")
                        val updated = current.copy(
                            course = payload.optString("course", current.course).trim().ifBlank { current.course },
                            folder = payload.optString("folder", current.folder).trim().ifBlank { current.folder },
                            title = payload.optString("title", current.title).trim().ifBlank { current.title },
                            category = payload.optString("category", current.category).trim().ifBlank { current.category },
                            level = payload.optString("level", current.level).trim().ifBlank { current.level },
                            summary = payload.optString("summary", current.summary).trim().ifBlank { current.summary },
                            tags = payload.optString("tags", current.tags).trim(),
                            updatedAt = System.currentTimeMillis()
                        )
                        db.grammarNoteDao().update(updated)
                        AssistantPendingAction(
                            id = UUID.randomUUID().toString(), type = "RESTORE_GRAMMAR", deckId = current.id,
                            title = "恢复语法修改", description = "恢复「${current.title}」修改前的基本数据",
                            payload = JSONObject().put("course", current.course).put("folder", current.folder)
                                .put("title", current.title).put("category", current.category).put("level", current.level)
                                .put("summary", current.summary).put("tags", current.tags).toString()
                        )
                    }
                    "TRANSFER_CARDS" -> executeAssistantCardTransfer(action)
                    "ADD_CARDS" -> executeAssistantAddCards(action)
                    "ENRICH_CARDS" -> executeAssistantCardEnrichment(action)
                    "UPDATE_CARDS" -> executeAssistantCardUpdate(action)
                    "NORMALIZE_CHINESE" -> executeAssistantChineseNormalization(action)
                    "SPLIT_FOLDER" -> executeAssistantSplitFolder(action)
                    "MERGE_FOLDERS" -> executeAssistantMergeFolders(action)
                    "CREATE_WEAKNESS_FOLDER" -> executeAssistantWeaknessFolder(action)
                    "UPDATE_DAILY_PLAN" -> {
                        val payload = JSONObject(action.payload)
                        val old = JSONObject()
                            .put("dailyGoalCards", settings.value.dailyGoalCards)
                            .put("dailyNewCardLimit", settings.value.dailyNewCardLimit)
                            .put("dailyReviewLimit", settings.value.dailyReviewLimit)
                        settingsRepository.setInt("dailyGoalCards", payload.optInt("dailyGoalCards", settings.value.dailyGoalCards))
                        settingsRepository.setInt("dailyNewCardLimit", payload.optInt("dailyNewCardLimit", settings.value.dailyNewCardLimit))
                        settingsRepository.setInt("dailyReviewLimit", payload.optInt("dailyReviewLimit", settings.value.dailyReviewLimit))
                        VocabWidgetProvider.requestUpdate(getApplication())
                        AssistantPendingAction(
                            id = UUID.randomUUID().toString(), type = "RESTORE_DAILY_PLAN", deckId = -1,
                            title = "恢复每日学习计划", description = "恢复修改前的每日目标与上限",
                            payload = old.toString()
                        )
                    }
                    else -> error("不支持的操作")
                }
            }.onSuccess { undo ->
                if (undo.type == "NONE") {
                    _assistantUndoAction.value = null
                    appendAssistantMessage("ASSISTANT", "已完成：${action.title}", kind = "STATUS")
                } else {
                    _assistantUndoAction.value = undo
                    appendAssistantMessage(
                        "ASSISTANT", "已完成：${action.title}", kind = "ACTION", payload = undo.toJson()
                    )
                }
            }.onFailure { error ->
                appendAssistantMessage(
                    "ASSISTANT",
                    "操作失败，没有应用数据：${error.localizedMessage ?: "未知错误"}",
                    kind = "STATUS"
                )
            }
            _assistantStatus.value = null
            _assistantBusy.value = false
        }
    }

    fun undoAssistantAction() {
        val action = _assistantUndoAction.value ?: return
        _assistantUndoAction.value = null
        viewModelScope.launch {
            _assistantBusy.value = true
            _assistantStatus.value = action.title
            runCatching {
                when (action.type) {
                    "RESTORE_ORDER" -> repository.reorderCards(
                        action.deckId,
                        JSONObject(action.payload).getJSONArray("order").longValues()
                    )
                    "RESTORE_POS" -> {
                        val updates = JSONArray(action.payload)
                        val currentById = allCards.value.associateBy { it.id }
                        val restored = buildList {
                            for (index in 0 until updates.length()) {
                                val item = updates.optJSONObject(index) ?: continue
                                val card = currentById[item.optLong("cardId")] ?: continue
                                add(card.copy(partOfSpeech = item.optString("oldPartOfSpeech")))
                            }
                        }
                        repository.updateCards(restored)
                    }
                    "DELETE_CREATED_FOLDER" -> {
                        val deck = allDecks.value.firstOrNull { it.id == action.deckId }
                            ?: error("找不到刚创建的文件夹")
                        check(allCards.value.none { it.deckId == deck.id }) {
                            "文件夹已经加入单词，为避免遗失数据，无法直接恢复删除"
                        }
                        repository.deleteDeck(deck)
                    }
                    "RESTORE_FOLDER" -> {
                        val deck = allDecks.value.firstOrNull { it.id == action.deckId }
                            ?: error("找不到文件夹")
                        val payload = JSONObject(action.payload)
                        repository.updateDeck(deck.copy(name = payload.getString("name"), category = payload.getString("category")))
                    }
                    "RESTORE_GRAMMAR" -> {
                        val current = db.grammarNoteDao().getById(action.deckId) ?: error("找不到语法")
                        val payload = JSONObject(action.payload)
                        db.grammarNoteDao().update(current.copy(
                            course = payload.getString("course"), folder = payload.getString("folder"),
                            title = payload.getString("title"), category = payload.getString("category"),
                            level = payload.getString("level"), summary = payload.getString("summary"),
                            tags = payload.getString("tags"), updatedAt = System.currentTimeMillis()
                        ))
                    }
                    "DELETE_CREATED_GRAMMAR" -> undoAssistantGrammarCreation(db, action.deckId, JSONObject(action.payload).getString("snapshot"))
                    "RESTORE_CARD_LOCATIONS" -> {
                        val mappings = JSONArray(action.payload)
                        for (index in 0 until mappings.length()) {
                            val item = mappings.optJSONObject(index) ?: continue
                            repository.moveCards(item.getJSONArray("cardIds").longValues(), item.getLong("deckId"))
                        }
                    }
                    "DELETE_CREATED_CARDS" -> {
                        val ids = JSONArray(action.payload).longValues()
                        ids.forEach { repository.deleteCardById(it) }
                    }
                    "RESTORE_CARD_FIELDS" -> {
                        val values = JSONArray(action.payload)
                        val currentById = allCards.value.associateBy { it.id }
                        val restored = buildList {
                            for (index in 0 until values.length()) {
                                val item = values.optJSONObject(index) ?: continue
                                val card = currentById[item.optLong("cardId")] ?: continue
                                add(card.copy(
                                    word = item.optString("word", card.word),
                                    notes = item.optString("notes", card.notes),
                                    phonetic = item.optString("phonetic"),
                                    partOfSpeech = item.optString("partOfSpeech"),
                                    definition = item.optString("definition"),
                                    exampleSentence = item.optString("exampleSentence"),
                                    exampleTranslation = item.optString("exampleTranslation")
                                ))
                            }
                        }
                        repository.updateCards(restored)
                    }
                    "UNDO_SPLIT" -> undoAssistantSplit(JSONObject(action.payload))
                    "UNDO_WEAKNESS_FOLDER" -> undoAssistantCreatedFolder(JSONObject(action.payload))
                    "RESTORE_DAILY_PLAN" -> {
                        val payload = JSONObject(action.payload)
                        settingsRepository.setInt("dailyGoalCards", payload.getInt("dailyGoalCards"))
                        settingsRepository.setInt("dailyNewCardLimit", payload.getInt("dailyNewCardLimit"))
                        settingsRepository.setInt("dailyReviewLimit", payload.getInt("dailyReviewLimit"))
                        VocabWidgetProvider.requestUpdate(getApplication())
                    }
                }
            }.onSuccess {
                appendAssistantMessage("ASSISTANT", "已恢复上一个 AI 操作。", kind = "ACTION_UNDONE")
            }.onFailure { error ->
                appendAssistantMessage("ASSISTANT", "恢复失败：${error.localizedMessage}", kind = "STATUS")
            }
            _assistantStatus.value = null
            _assistantBusy.value = false
        }
    }

    private fun prepareAssistantTransfer(result: JSONObject, message: String) {
        val targetId = result.optLong("targetDeckId")
        val target = allDecks.value.firstOrNull { it.id == targetId }
        val ids = result.optJSONArray("cardIds")?.longValues().orEmpty().distinct()
        val actual = allCards.value.filter { it.id in ids }.filter { it.deckId != targetId }
        val mode = if (result.optString("mode").equals("COPY", true)) "COPY" else "MOVE"
        if (target == null || target.id !in _assistantSelectedDeckIds.value || actual.isEmpty()) {
            appendAssistantMessage("ASSISTANT", "$message\n\n找不到可安全${if (mode == "COPY") "复制" else "移动"}的目标或卡片。")
            return
        }
        _assistantPendingAction.value = AssistantPendingAction(
            id = UUID.randomUUID().toString(), type = "TRANSFER_CARDS", deckId = target.id,
            title = "${if (mode == "COPY") "复制" else "移动"} ${actual.size} 张单词卡",
            description = "目标：${target.name}\n同名单词会跳过，不会产生重复卡片。",
            payload = JSONObject().put("targetDeckId", target.id).put("mode", mode)
                .put("cardIds", JSONArray(actual.map { it.id })).toString()
        )
        appendAssistantMessage("ASSISTANT", "已核对 ${actual.size} 张真实卡片，请确认操作。", kind = "STATUS")
    }

    private fun prepareAssistantAddCards(result: JSONObject, message: String) {
        val target = allDecks.value.firstOrNull { it.id == result.optLong("targetDeckId") }
        val cards = result.optJSONArray("cards") ?: JSONArray()
        if (target == null || target.id !in _assistantSelectedDeckIds.value || cards.length() == 0) {
            appendAssistantMessage("ASSISTANT", "$message\n\n请指定允许编辑的目标文件夹，并提供要加入的单词。")
            return
        }
        val existing = allCards.value.filter { it.deckId == target.id }.mapTo(hashSetOf()) { it.normalizedWord }
        val safe = JSONArray()
        val seen = hashSetOf<String>()
        for (index in 0 until cards.length()) {
            val item = cards.optJSONObject(index) ?: continue
            val normalized = item.optString("word").trim().lowercase()
            if (normalized.isNotBlank() && normalized !in existing && seen.add(normalized)) safe.put(item)
        }
        if (safe.length() == 0) {
            appendAssistantMessage("ASSISTANT", "推荐单词都已存在，没有创建重复卡片。")
            return
        }
        _assistantPendingAction.value = AssistantPendingAction(
            id = UUID.randomUUID().toString(), type = "ADD_CARDS", deckId = target.id,
            title = "加入 ${safe.length()} 个 AI 推荐单词", description = "目标：${target.name}\n只会加入预览中的非重复单词。",
            payload = JSONObject().put("cards", safe).toString()
        )
        appendAssistantMessage("ASSISTANT", "已准备 ${safe.length()} 个模型知识补充单词，请确认后加入。", kind = "STATUS")
    }

    private fun prepareAssistantEnrichment(result: JSONObject, message: String) {
        val source = result.optJSONArray("updates") ?: JSONArray()
        val currentById = allCards.value.associateBy { it.id }
        val safe = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val card = currentById[item.optLong("cardId")] ?: continue
            if (card.deckId !in _assistantSelectedDeckIds.value) continue
            if (card.phonetic.isBlank() || card.partOfSpeech.isBlank() || card.definition.isBlank() ||
                card.exampleSentence.isBlank() || card.exampleTranslation.isBlank()) safe.put(item)
        }
        if (safe.length() == 0) {
            appendAssistantMessage("ASSISTANT", "$message\n\n没有可安全补齐的空白字段。")
            return
        }
        _assistantPendingAction.value = AssistantPendingAction(
            id = UUID.randomUUID().toString(), type = "ENRICH_CARDS", deckId = 0,
            title = "补齐 ${safe.length()} 张单词卡", description = "只填入原本空白的字段，不会覆盖既有内容。",
            payload = JSONObject().put("updates", safe).toString()
        )
        appendAssistantMessage("ASSISTANT", "已核对空白字段，请确认批次补齐。", kind = "STATUS")
    }

    private fun prepareAssistantChineseNormalization(result: JSONObject, message: String) {
        val source = result.optJSONArray("updates") ?: JSONArray()
        val currentById = allCards.value.associateBy { it.id }
        val editableDeckIds = _assistantSelectedDeckIds.value
        val safe = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val card = currentById[item.optLong("cardId")] ?: continue
            if (card.deckId !in editableDeckIds) continue
            val definition = item.optString("definition", card.definition).trim().take(500)
            val exampleTranslation = item.optString("exampleTranslation", card.exampleTranslation).trim().take(600)
            if (definition.isBlank() && card.definition.isNotBlank()) continue
            if (exampleTranslation.isBlank() && card.exampleTranslation.isNotBlank()) continue
            if (definition == card.definition && exampleTranslation == card.exampleTranslation) continue
            safe.put(
                JSONObject()
                    .put("cardId", card.id)
                    .put("word", card.word)
                    .put("definition", definition)
                    .put("exampleTranslation", exampleTranslation)
            )
        }
        if (safe.length() == 0) {
            appendAssistantMessage("ASSISTANT", "$message\n\n没有找到需要转换，或可安全应用的简体中文修改。")
            return
        }
        val deckNames = allDecks.value
            .filter { it.id in editableDeckIds }
            .joinToString("、") { it.name }
        _assistantPendingAction.value = AssistantPendingAction(
            id = UUID.randomUUID().toString(),
            type = "NORMALIZE_CHINESE",
            deckId = 0,
            title = "统一 ${safe.length()} 张单词卡为简体中文",
            description = "范围：${deckNames.ifBlank { "已选文件夹" }}\n只修改中文解释与例句翻译；英文、词性和例句不会变动。",
            payload = safe.toString()
        )
        appendAssistantMessage("ASSISTANT", "已检查所选文件夹并准备简体中文修改，确认后才会应用。", kind = "STATUS")
    }

    private fun prepareAssistantSplit(result: JSONObject, fallbackDeck: Deck?, message: String) {
        val source = allDecks.value.firstOrNull { it.id == result.optLong("sourceDeckId") } ?: fallbackDeck
        val groups = result.optJSONArray("groups") ?: JSONArray()
        if (source == null || source.id !in _assistantSelectedDeckIds.value || groups.length() < 2) {
            appendAssistantMessage("ASSISTANT", "$message\n\n需要一个可编辑的来源文件夹与至少两个有效分类。")
            return
        }
        val validIds = allCards.value.filter { it.deckId == source.id }.mapTo(hashSetOf()) { it.id }
        var count = 0
        for (index in 0 until groups.length()) {
            count += groups.optJSONObject(index)?.optJSONArray("cardIds")?.longValues()?.count { it in validIds } ?: 0
        }
        if (count == 0) {
            appendAssistantMessage("ASSISTANT", "AI 没有返回可验证的卡片分类，因此不会拆分。")
            return
        }
        _assistantPendingAction.value = AssistantPendingAction(
            id = UUID.randomUUID().toString(), type = "SPLIT_FOLDER", deckId = source.id,
            title = "拆分文件夹「${source.name}」", description = "创建 ${groups.length()} 个分类并移动约 $count 张卡片；未分类卡片留在原处。",
            payload = JSONObject().put("groups", groups).toString()
        )
        appendAssistantMessage("ASSISTANT", "分类结果已和真实卡片核对，请确认拆分。", kind = "STATUS")
    }

    private fun prepareAssistantMerge(result: JSONObject, message: String) {
        val targetId = result.optLong("targetDeckId")
        val sourceIds = result.optJSONArray("sourceDeckIds")?.longValues().orEmpty().filter { it != targetId }.distinct()
        val target = allDecks.value.firstOrNull { it.id == targetId }
        if (target == null || sourceIds.isEmpty() || (sourceIds + targetId).any { it !in _assistantSelectedDeckIds.value }) {
            appendAssistantMessage("ASSISTANT", "$message\n\n请指定至少两个允许编辑的文件夹。")
            return
        }
        val affected = allCards.value.count { it.deckId in sourceIds }
        _assistantPendingAction.value = AssistantPendingAction(
            id = UUID.randomUUID().toString(), type = "MERGE_FOLDERS", deckId = target.id,
            title = "合并 ${sourceIds.size + 1} 个文件夹", description =
                "目标：${target.name}\n移动 $affected 张卡片并删除来源文件夹。重复字与学习纪录会整合，此操作无法完整恢复。",
            payload = JSONObject().put("targetDeckId", target.id).put("sourceDeckIds", JSONArray(sourceIds)).toString()
        )
        appendAssistantMessage("ASSISTANT", "已准备合并；请特别确认不可完整恢复的影响。", kind = "STATUS")
    }

    private fun prepareAssistantWeaknessFolder(result: JSONObject, message: String) {
        val name = result.optString("name", "薄弱点复习").trim().take(60).ifBlank { "薄弱点复习" }
        val category = result.optString("category").trim().take(40)
        if (category.isBlank()) {
            appendAssistantMessage("ASSISTANT", "「$name」要放在哪个课程？")
            return
        }
        val weakLogIds = recentStudyLogs.value.filter { it.rating <= 2 }.mapTo(hashSetOf()) { it.cardId }
        val requested = result.optJSONArray("cardIds")?.longValues().orEmpty().toSet()
        val safeIds = allCards.value.filter { it.id in requested && (it.mistakeCount > 0 || it.id in weakLogIds) }.map { it.id }
        if (safeIds.isEmpty()) {
            appendAssistantMessage("ASSISTANT", "$message\n\n目前没有可由真实错题纪录验证的卡片。")
            return
        }
        _assistantPendingAction.value = AssistantPendingAction(
            id = UUID.randomUUID().toString(), type = "CREATE_WEAKNESS_FOLDER", deckId = 0,
            title = "创建薄弱点文件夹「$name」", description = "课程：$category\n复制 ${safeIds.size} 张真实错题卡，原卡片不会移动。",
            payload = JSONObject().put("name", name).put("category", category).put("cardIds", JSONArray(safeIds)).toString()
        )
        appendAssistantMessage("ASSISTANT", "已依错题次数与低评分纪录选出 ${safeIds.size} 张卡片。", kind = "STATUS")
    }

    private fun prepareAssistantDailyPlan(result: JSONObject, message: String) {
        val goal = result.optInt("dailyGoalCards", settings.value.dailyGoalCards).coerceIn(5, 100)
        val newLimit = result.optInt("dailyNewCardLimit", settings.value.dailyNewCardLimit).coerceIn(1, 100)
        val reviewLimit = result.optInt("dailyReviewLimit", settings.value.dailyReviewLimit).coerceIn(10, 500)
        _assistantPendingAction.value = AssistantPendingAction(
            id = UUID.randomUUID().toString(), type = "UPDATE_DAILY_PLAN", deckId = -1,
            title = "调整每日学习计划", description = "每日目标：$goal 张\n新卡上限：$newLimit 张\n复习上限：$reviewLimit 张",
            payload = JSONObject().put("dailyGoalCards", goal).put("dailyNewCardLimit", newLimit).put("dailyReviewLimit", reviewLimit).toString()
        )
        appendAssistantMessage("ASSISTANT", "$message\n\n计划已准备好，确认后才会更新设置。", kind = "STATUS")
    }

    private fun buildVerifiedAuditReport(result: JSONObject): String {
        val cards = allCards.value
        val duplicates = cards.groupBy { it.normalizedWord }
            .filter { (word, values) -> word.isNotBlank() && values.map { it.deckId }.distinct().size > 1 }
            .values.take(20)
        val incomplete = cards.filter {
            it.partOfSpeech.isBlank() || it.definition.isBlank() ||
                it.exampleSentence.isBlank() || it.exampleTranslation.isBlank()
        }.take(30)
        val suspected = result.optJSONArray("suspectedTypos") ?: JSONArray()
        val byId = cards.associateBy { it.id }
        val typoLines = buildList {
            for (index in 0 until suspected.length()) {
                val item = suspected.optJSONObject(index) ?: continue
                val card = byId[item.optLong("cardId")] ?: continue
                val suggestion = item.optString("suggestion").trim().take(100)
                if (suggestion.isBlank() || suggestion.equals(card.word, true)) continue
                add("• ${card.word} → 可能是 $suggestion：${item.optString("reason").trim().take(160)}")
            }
        }.take(20)
        return buildString {
            append("单词库检查结果\n\n")
            append("跨文件夹同字：${duplicates.size} 组")
            if (duplicates.isNotEmpty()) append("\n" + duplicates.joinToString("\n") { group ->
                "• ${group.first().word}（${group.size} 张）"
            })
            append("\n\n数据不完整：${incomplete.size} 张")
            if (incomplete.isNotEmpty()) append("\n" + incomplete.joinToString("\n") { card ->
                val missing = buildList {
                    if (card.partOfSpeech.isBlank()) add("词性")
                    if (card.definition.isBlank()) add("解释")
                    if (card.exampleSentence.isBlank()) add("例句")
                    if (card.exampleTranslation.isBlank()) add("例句翻译")
                }
                "• ${card.word}：缺少${missing.joinToString("、")}"
            })
            append("\n\n疑似拼字问题：${typoLines.size} 张（AI 建议，修改前仍需确认）")
            if (typoLines.isNotEmpty()) append("\n" + typoLines.joinToString("\n"))
        }
    }

    private fun buildVerifiedLearningAnalysis(result: JSONObject): String {
        val logs = recentStudyLogs.value
        if (logs.isEmpty()) return "目前还没有真实学习纪录可以分析。"
        val byId = allCards.value.associateBy { it.id }
        val lowLogs = logs.filter { it.rating <= 2 }
        val hardCards = lowLogs.groupingBy { it.cardId }.eachCount().entries
            .sortedByDescending { it.value }.take(10)
        val partStats = lowLogs.mapNotNull { byId[it.cardId]?.partOfSpeech?.takeIf(String::isNotBlank) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6)
        val verified = buildString {
            append("真实学习纪录分析\n\n")
            append("总复习纪录：${logs.size} 次\n")
            append("Again／Hard：${lowLogs.size} 次\n")
            append("Good／Easy：${logs.size - lowLogs.size} 次")
            if (hardCards.isNotEmpty()) append("\n\n最常答错：\n" + hardCards.joinToString("\n") {
                "• ${byId[it.key]?.word ?: "卡片 #${it.key}"}：${it.value} 次"
            })
            if (partStats.isNotEmpty()) append("\n\n较弱词性：\n" + partStats.joinToString("\n") {
                "• ${it.key}：${it.value} 次低评分"
            })
        }
        val interpretation = formatAssistantDisplayText(result.optString("analysis"))
        return if (interpretation.isBlank()) verified else "$verified\n\nAI 学习建议\n$interpretation"
    }

    private suspend fun executeAssistantCardTransfer(action: AssistantPendingAction): AssistantPendingAction {
        val payload = JSONObject(action.payload)
        val targetDeckId = payload.getLong("targetDeckId")
        val target = allDecks.value.firstOrNull { it.id == targetDeckId } ?: error("找不到目标文件夹")
        check(target.id in _assistantSelectedDeckIds.value) { "目标文件夹不在可编辑范围" }
        val requested = payload.getJSONArray("cardIds").longValues().distinct()
        val currentById = allCards.value.associateBy { it.id }
        val cards = requested.mapNotNull(currentById::get).filter { it.deckId != targetDeckId }
        require(cards.isNotEmpty()) { "没有可处理的单词" }
        val existingWords = allCards.value.filter { it.deckId == targetDeckId }.mapTo(hashSetOf()) { it.normalizedWord }
        val safeCards = cards.filter { it.normalizedWord !in existingWords }.distinctBy { it.normalizedWord }
        require(safeCards.isNotEmpty()) { "目标文件夹已经包含这些单词" }
        return if (payload.optString("mode").equals("COPY", true)) {
            val insertedIds = safeCards.map { repository.insertCard(it.copyForAssistantDeck(targetDeckId)) }
            AssistantPendingAction(
                id = UUID.randomUUID().toString(), type = "DELETE_CREATED_CARDS", deckId = targetDeckId,
                title = "恢复复制单词", description = "删除刚复制的 ${insertedIds.size} 张卡片",
                payload = JSONArray(insertedIds).toString()
            )
        } else {
            val original = safeCards.groupBy { it.deckId }.map { (deckId, values) ->
                JSONObject().put("deckId", deckId).put("cardIds", JSONArray(values.map { it.id }))
            }
            check(original.all { item -> item.getLong("deckId") in _assistantSelectedDeckIds.value }) {
                "部分来源文件夹不在可编辑范围"
            }
            repository.moveCards(safeCards.map { it.id }, targetDeckId)
            AssistantPendingAction(
                id = UUID.randomUUID().toString(), type = "RESTORE_CARD_LOCATIONS", deckId = targetDeckId,
                title = "恢复移动单词", description = "将 ${safeCards.size} 张卡片移回原文件夹",
                payload = JSONArray(original).toString()
            )
        }
    }

    private suspend fun executeAssistantAddCards(action: AssistantPendingAction): AssistantPendingAction {
        val target = allDecks.value.firstOrNull { it.id == action.deckId } ?: error("找不到目标文件夹")
        check(target.id in _assistantSelectedDeckIds.value) { "目标文件夹不在可编辑范围" }
        val source = JSONObject(action.payload).getJSONArray("cards")
        val existing = allCards.value.filter { it.deckId == target.id }.mapTo(hashSetOf()) { it.normalizedWord }
        val inserted = mutableListOf<Long>()
        val seen = hashSetOf<String>()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val word = item.optString("word").trim().take(100)
            val normalized = word.lowercase()
            if (word.isBlank() || normalized in existing || !seen.add(normalized)) continue
            inserted += repository.insertCard(
                Flashcard(
                    deckId = target.id, word = word,
                    phonetic = item.optString("phonetic").trim().take(100),
                    partOfSpeech = item.optString("partOfSpeech").trim().take(80),
                    definition = item.optString("definition").trim().take(500),
                    exampleSentence = item.optString("exampleSentence").trim().take(600),
                    exampleTranslation = item.optString("exampleTranslation").trim().take(600),
                    notes = "AI 知识补充"
                )
            )
        }
        require(inserted.isNotEmpty()) { "没有可添加的非重复单词" }
        return AssistantPendingAction(
            id = UUID.randomUUID().toString(), type = "DELETE_CREATED_CARDS", deckId = target.id,
            title = "恢复加入推荐单词", description = "删除刚加入的 ${inserted.size} 张卡片",
            payload = JSONArray(inserted).toString()
        )
    }

    private suspend fun executeAssistantCardEnrichment(action: AssistantPendingAction): AssistantPendingAction {
        val updates = JSONObject(action.payload).getJSONArray("updates")
        val currentById = allCards.value.associateBy { it.id }
        val old = JSONArray()
        val changed = buildList {
            for (index in 0 until updates.length()) {
                val item = updates.optJSONObject(index) ?: continue
                val card = currentById[item.optLong("cardId")] ?: continue
                if (card.deckId !in _assistantSelectedDeckIds.value) continue
                old.put(JSONObject()
                    .put("cardId", card.id).put("phonetic", card.phonetic)
                    .put("partOfSpeech", card.partOfSpeech).put("definition", card.definition)
                    .put("exampleSentence", card.exampleSentence).put("exampleTranslation", card.exampleTranslation))
                add(card.copy(
                    phonetic = card.phonetic.ifBlank { item.optString("phonetic").trim().take(100) },
                    partOfSpeech = card.partOfSpeech.ifBlank { item.optString("partOfSpeech").trim().take(80) },
                    definition = card.definition.ifBlank { item.optString("definition").trim().take(500) },
                    exampleSentence = card.exampleSentence.ifBlank { item.optString("exampleSentence").trim().take(600) },
                    exampleTranslation = card.exampleTranslation.ifBlank { item.optString("exampleTranslation").trim().take(600) }
                ))
            }
        }.filter { updated -> currentById[updated.id] != updated }
        require(changed.isNotEmpty()) { "没有找到需要补齐的空白字段" }
        repository.updateCards(changed)
        return AssistantPendingAction(
            id = UUID.randomUUID().toString(), type = "RESTORE_CARD_FIELDS", deckId = action.deckId,
            title = "恢复批次补齐", description = "恢复 ${changed.size} 张卡片原本的空白字段",
            payload = old.toString()
        )
    }

    private suspend fun executeAssistantCardUpdate(action: AssistantPendingAction): AssistantPendingAction {
        val updates = JSONObject(action.payload).getJSONArray("updates")
        val old = JSONArray()
        db.withTransaction {
            val cards = db.flashcardDao().getAllCards().first()
            val changed = (0 until updates.length()).map { index ->
                val fields = updates.getJSONObject(index)
                val card = cards.firstOrNull { it.id == fields.getLong("cardId") } ?: error("找不到要修改的单词卡")
                check(card.deckId in _assistantSelectedDeckIds.value) { "单词卡不在允许编辑的文件夹" }
                old.put(card.assistantEditableFields())
                applyAssistantCardUpdate(card, fields)
            }.distinctBy { it.id }
            val finalCards = cards.map { current -> changed.firstOrNull { it.id == current.id } ?: current }
            require(finalCards.groupBy { it.deckId to it.word.trim().lowercase() }.values.none { it.size > 1 }) { "修改会造成文件夹内的重复单词" }
            require(changed.any { it != cards.firstOrNull { original -> original.id == it.id } }) { "没有需要修改的字段" }
            repository.updateCards(changed)
        }
        return AssistantPendingAction(UUID.randomUUID().toString(), "RESTORE_CARD_FIELDS", 0,
            "恢复单词修改", "恢复 ${old.length()} 张卡片修改前的数据", old.toString())
    }

    private suspend fun executeAssistantChineseNormalization(action: AssistantPendingAction): AssistantPendingAction {
        val updates = JSONArray(action.payload)
        val currentById = allCards.value.associateBy { it.id }
        val editableDeckIds = _assistantSelectedDeckIds.value
        val old = JSONArray()
        val changed = buildList {
            for (index in 0 until updates.length()) {
                val item = updates.optJSONObject(index) ?: continue
                val card = currentById[item.optLong("cardId")] ?: continue
                if (card.deckId !in editableDeckIds) continue
                val definition = item.optString("definition", card.definition).trim().take(500)
                val exampleTranslation = item.optString("exampleTranslation", card.exampleTranslation).trim().take(600)
                if (definition.isBlank() && card.definition.isNotBlank()) continue
                if (exampleTranslation.isBlank() && card.exampleTranslation.isNotBlank()) continue
                val updated = card.copy(
                    definition = definition,
                    exampleTranslation = exampleTranslation
                )
                if (updated == card) continue
                old.put(
                    JSONObject()
                        .put("cardId", card.id)
                        .put("phonetic", card.phonetic)
                        .put("partOfSpeech", card.partOfSpeech)
                        .put("definition", card.definition)
                        .put("exampleSentence", card.exampleSentence)
                        .put("exampleTranslation", card.exampleTranslation)
                )
                add(updated)
            }
        }
        require(changed.isNotEmpty()) { "没有找到需要转换的中文内容" }
        repository.updateCards(changed)
        return AssistantPendingAction(
            id = UUID.randomUUID().toString(),
            type = "RESTORE_CARD_FIELDS",
            deckId = 0,
            title = "恢复简体中文统一",
            description = "恢复 ${changed.size} 张卡片修改前的中文内容",
            payload = old.toString()
        )
    }

    private suspend fun executeAssistantSplitFolder(action: AssistantPendingAction): AssistantPendingAction {
        val payload = JSONObject(action.payload)
        val sourceDeck = allDecks.value.firstOrNull { it.id == action.deckId } ?: error("找不到来源文件夹")
        check(sourceDeck.id in _assistantSelectedDeckIds.value) { "来源文件夹不在可编辑范围" }
        val sourceCardIds = allCards.value.filter { it.deckId == sourceDeck.id }.mapTo(hashSetOf()) { it.id }
        val groups = payload.getJSONArray("groups")
        val planned = buildList {
            val used = hashSetOf<Long>()
            for (index in 0 until groups.length()) {
                val group = groups.optJSONObject(index) ?: continue
                val name = group.optString("name").trim().take(60)
                val category = group.optString("category", sourceDeck.category).trim().take(40).ifBlank { sourceDeck.category }
                val ids = group.optJSONArray("cardIds")?.longValues().orEmpty()
                    .filter { it in sourceCardIds && used.add(it) }
                if (name.isNotBlank() && ids.isNotEmpty()) add(Triple(name, category, ids))
            }
        }
        require(planned.size >= 2) { "至少需要两个有效分类才能拆分" }
        planned.forEach { (name, category, _) ->
            check(allDecks.value.none { it.name.equals(name, true) && it.category.equals(category, true) }) {
                "「$category」已有名为「$name」的文件夹"
            }
        }
        val created = JSONArray()
        for ((name, category, ids) in planned) {
            val newId = repository.insertDeck(Deck(name = name, category = category, description = "由 AI 从「${sourceDeck.name}」拆分"))
            repository.moveCards(ids, newId)
            created.put(JSONObject().put("deckId", newId).put("cardIds", JSONArray(ids)))
        }
        return AssistantPendingAction(
            id = UUID.randomUUID().toString(), type = "UNDO_SPLIT", deckId = sourceDeck.id,
            title = "恢复文件夹拆分", description = "把卡片移回「${sourceDeck.name}」并删除新分类",
            payload = JSONObject().put("sourceDeckId", sourceDeck.id).put("created", created).toString()
        )
    }

    private suspend fun executeAssistantMergeFolders(action: AssistantPendingAction): AssistantPendingAction {
        val payload = JSONObject(action.payload)
        val targetId = payload.getLong("targetDeckId")
        val sourceIds = payload.getJSONArray("sourceDeckIds").longValues().filter { it != targetId }.distinct()
        val target = allDecks.value.firstOrNull { it.id == targetId } ?: error("找不到合并目标")
        check((sourceIds + targetId).all { it in _assistantSelectedDeckIds.value }) { "部分文件夹不在可编辑范围" }
        require(sourceIds.isNotEmpty()) { "没有可合并的来源文件夹" }
        sourceIds.forEach { sourceId ->
            val source = allDecks.value.firstOrNull { it.id == sourceId } ?: return@forEach
            repository.moveCards(allCards.value.filter { it.deckId == sourceId }.map { it.id }, target.id)
            repository.deleteDeck(source)
        }
        return AssistantPendingAction(
            id = UUID.randomUUID().toString(), type = "NONE", deckId = target.id,
            title = "", description = "合并包含学习纪录整合，无法完整恢复", payload = ""
        )
    }

    private suspend fun executeAssistantWeaknessFolder(action: AssistantPendingAction): AssistantPendingAction {
        val payload = JSONObject(action.payload)
        val name = payload.getString("name").trim().take(60)
        val category = payload.getString("category").trim().take(40)
        require(name.isNotBlank() && category.isNotBlank()) { "文件夹名称与课程不能空白" }
        check(allDecks.value.none { it.name.equals(name, true) && it.category.equals(category, true) }) { "已有同名薄弱点文件夹" }
        val weakFromLogs = recentStudyLogs.value.filter { it.rating <= 2 }.mapTo(hashSetOf()) { it.cardId }
        val requested = payload.getJSONArray("cardIds").longValues().toSet()
        val weakCards = allCards.value.filter { it.id in requested && (it.mistakeCount > 0 || it.id in weakFromLogs) }
            .distinctBy { it.normalizedWord }
        require(weakCards.isNotEmpty()) { "目前没有符合条件的真实错题" }
        val newId = repository.insertDeck(Deck(name = name, category = category, description = "依真实错题与低评分纪录创建"))
        val inserted = weakCards.map { repository.insertCard(it.copyForAssistantDeck(newId)) }
        return AssistantPendingAction(
            id = UUID.randomUUID().toString(), type = "UNDO_WEAKNESS_FOLDER", deckId = newId,
            title = "恢复薄弱点文件夹", description = "删除刚创建的「$name」与其中副本",
            payload = JSONObject().put("deckId", newId).put("cardIds", JSONArray(inserted)).toString()
        )
    }

    private suspend fun undoAssistantSplit(payload: JSONObject) {
        val sourceDeckId = payload.getLong("sourceDeckId")
        val created = payload.getJSONArray("created")
        for (index in 0 until created.length()) {
            val item = created.optJSONObject(index) ?: continue
            repository.moveCards(item.getJSONArray("cardIds").longValues(), sourceDeckId)
            allDecks.value.firstOrNull { it.id == item.getLong("deckId") }?.let { repository.deleteDeck(it) }
        }
    }

    private suspend fun undoAssistantCreatedFolder(payload: JSONObject) {
        payload.getJSONArray("cardIds").longValues().forEach { repository.deleteCardById(it) }
        allDecks.value.firstOrNull { it.id == payload.getLong("deckId") }?.let { repository.deleteDeck(it) }
    }

    private fun Flashcard.copyForAssistantDeck(targetDeckId: Long): Flashcard = copy(
        id = 0L, deckId = targetDeckId, normalizedWord = word.trim().lowercase(),
        isMastered = false, isSuspended = false, mistakeCount = 0,
        intervalDays = 0, easeFactor = 2.5f, repetitionCount = 0,
        nextReviewTimestamp = System.currentTimeMillis(), lastReviewedTimestamp = null,
        createdAt = System.currentTimeMillis(), sortOrder = System.currentTimeMillis()
    )

    private fun prepareAlphabeticalSort(deck: Deck, descending: Boolean) {
        val cards = allCards.value.filter { it.deckId == deck.id }
        if (cards.isEmpty()) {
            appendAssistantMessage("ASSISTANT", "「${deck.name}」目前没有单词可以排序。")
            return
        }
        val sorted = if (descending) {
            cards.sortedByDescending { it.word.lowercase() }
        } else {
            cards.sortedBy { it.word.lowercase() }
        }
        val payload = JSONObject()
            .put("oldOrder", JSONArray(cards.map { it.id }))
            .put("newOrder", JSONArray(sorted.map { it.id }))
            .toString()
        _assistantPendingAction.value = AssistantPendingAction(
            id = UUID.randomUUID().toString(),
            type = "SORT",
            deckId = deck.id,
            title = "依英文${if (descending) " Z–A" else " A–Z"} 排序",
            description = "文件夹：${deck.name}\n影响 ${cards.size} 张卡片；确认后才会保存。",
            payload = payload
        )
        appendAssistantMessage(
            "ASSISTANT",
            "已读取「${deck.name}」的 ${cards.size} 张卡片，准备好排序，请先确认操作。",
            kind = "STATUS"
        )
    }

    private fun handleAssistantResult(result: JSONObject, fallbackDeck: Deck?) {
        val type = result.optString("type", "REPLY").uppercase()
        val message = formatAssistantDisplayText(result.optString("message")).ifBlank { "已完成分析。" }
        when (type) {
            "CREATE_GRAMMAR" -> {
                val library = grammarLibraries.value.firstOrNull { it.id == result.optLong("libraryId") }
                    ?: grammarLibraries.value.singleOrNull { it.course.equals(result.optString("course"), true) && it.name.equals(result.optString("folder"), true) }
                if (library == null) {
                    appendAssistantMessage("ASSISTANT", "请指定要添加到哪一个既有语法库（课程及语法库名称），没有写入任何数据。")
                } else {
                    val grammar = result.optJSONObject("grammar") ?: result
                    val draft = runCatching { parseGrammarDraft(grammar) }.getOrNull()
                    if (draft == null) appendAssistantMessage("ASSISTANT", "AI 语法数据未通过检查，没有添加。请补上完整内容。")
                    else {
                        _assistantPendingAction.value = AssistantPendingAction(UUID.randomUUID().toString(), "CREATE_GRAMMAR", library.id,
                            "添加语法「${draft.title}」", "课程：${library.course}\n语法库：${library.name}\n${draft.summary}\n${draft.structure}\n${draft.exampleSentence}\n${draft.exampleTranslation}\n句型 ${draft.patterns.size} 个 · 题目 ${draft.questions.size} 题\n确认后才会添加，不会覆盖既有语法。",
                            JSONObject().put("libraryId", library.id).put("grammar", grammar).toString())
                        appendAssistantMessage("ASSISTANT", "已准备好语法与目标语法库，请确认后添加。", kind = "STATUS")
                    }
                }
            }
            "UPDATE_GRAMMAR" -> {
                val note = allGrammarNotes.value.firstOrNull { it.id == result.optLong("grammarId") }
                if (note == null) appendAssistantMessage("ASSISTANT", "找不到要编辑的语法笔记。")
                else {
                    val payload = JSONObject()
                        .put("course", result.optString("course", note.course))
                        .put("folder", result.optString("folder", note.folder))
                        .put("title", result.optString("title", note.title))
                        .put("category", result.optString("category", note.category))
                        .put("level", result.optString("level", note.level))
                        .put("summary", result.optString("summary", note.summary))
                        .put("tags", result.optString("tags", note.tags))
                    _assistantPendingAction.value = AssistantPendingAction(
                        id = UUID.randomUUID().toString(), type = "UPDATE_GRAMMAR", deckId = note.id,
                        title = "更新语法「${note.title}」",
                        description = "课程：${payload.optString("course")}\n文件夹：${payload.optString("folder")}\n标题：${payload.optString("title")}\n确认后才会应用。",
                        payload = payload.toString()
                    )
                    appendAssistantMessage("ASSISTANT", "语法修改内容已准备好，请先确认。", kind = "STATUS")
                }
            }
            "UPDATE_FOLDER" -> {
                val deck = allDecks.value.firstOrNull { it.id == result.optLong("deckId") } ?: fallbackDeck
                if (deck == null || deck.id !in _assistantSelectedDeckIds.value) {
                    appendAssistantMessage("ASSISTANT", "找不到可编辑的文件夹。")
                } else {
                    val name = result.optString("name", deck.name).trim().take(60).ifBlank { deck.name }
                    val category = result.optString("category", deck.category).trim().take(40).ifBlank { deck.category }
                    _assistantPendingAction.value = AssistantPendingAction(
                        id = UUID.randomUUID().toString(), type = "UPDATE_FOLDER", deckId = deck.id,
                        title = "更新文件夹「${deck.name}」",
                        description = "名称：${deck.name} → $name\n课程：${deck.category} → $category",
                        payload = JSONObject().put("name", name).put("category", category).toString()
                    )
                    appendAssistantMessage("ASSISTANT", "修改内容已准备好，确认后才会应用。", kind = "STATUS")
                }
            }
            "TRANSFER_CARDS" -> prepareAssistantTransfer(result, message)
            "ADD_CARDS" -> prepareAssistantAddCards(result, message)
            "ENRICH_CARDS" -> prepareAssistantEnrichment(result, message)
            "UPDATE_CARDS" -> {
                val source = result.optJSONArray("updates") ?: JSONArray()
                val safe = JSONArray()
                val seen = hashSetOf<Long>()
                val preview = mutableListOf<String>()
                for (index in 0 until source.length()) {
                    val item = source.optJSONObject(index) ?: continue
                    val card = allCards.value.firstOrNull { it.id == item.optLong("cardId") } ?: continue
                    if (card.deckId !in _assistantSelectedDeckIds.value || !seen.add(card.id)) continue
                    val updated = runCatching { applyAssistantCardUpdate(card, item) }.getOrNull() ?: continue
                    if (updated == card) continue
                    safe.put(item)
                    val before = card.assistantEditableFields(); val after = updated.assistantEditableFields()
                    val changes = listOf("word", "phonetic", "partOfSpeech", "definition", "exampleSentence", "exampleTranslation", "notes")
                        .filter { before.optString(it) != after.optString(it) }
                        .joinToString("\n") { "$it：${before.optString(it)} → ${after.optString(it)}" }
                    preview += "${card.word}\n$changes"
                }
                if (safe.length() == 0) appendAssistantMessage("ASSISTANT", "$message\n没有找到允许修改的单词字段。")
                else {
                    _assistantPendingAction.value = AssistantPendingAction(UUID.randomUUID().toString(), "UPDATE_CARDS", 0,
                        "修改 ${safe.length()} 张单词卡", preview.joinToString("\n\n"), JSONObject().put("updates", safe).toString())
                    appendAssistantMessage("ASSISTANT", "依指令修改的内容已准备好，请确认前后差异；原有内容只在确认后才会更新。", kind = "STATUS")
                }
            }
            "NORMALIZE_CHINESE" -> prepareAssistantChineseNormalization(result, message)
            "SPLIT_FOLDER" -> prepareAssistantSplit(result, fallbackDeck, message)
            "MERGE_FOLDERS" -> prepareAssistantMerge(result, message)
            "CREATE_WEAKNESS_FOLDER" -> prepareAssistantWeaknessFolder(result, message)
            "UPDATE_DAILY_PLAN" -> prepareAssistantDailyPlan(result, message)
            "AUDIT_REPORT" -> appendAssistantMessage("ASSISTANT", buildVerifiedAuditReport(result), kind = "STATUS")
            "LEARNING_ANALYSIS" -> appendAssistantMessage("ASSISTANT", buildVerifiedLearningAnalysis(result), kind = "STATUS")
            "CREATE_FOLDER" -> {
                val name = result.optString("name").trim().replace("\n", " ").take(60)
                val category = result.optString("category")
                    .trim().replace("\n", " ").take(40)
                if (name.isBlank()) {
                    appendAssistantMessage("ASSISTANT", "请告诉我新文件夹的名称。")
                } else if (category.isBlank()) {
                    val courses = allDecks.value.map { it.category }.filter { it.isNotBlank() }.distinct()
                    appendAssistantMessage(
                        "ASSISTANT",
                        buildString {
                            append("「$name」要放在哪个课程？")
                            if (courses.isNotEmpty()) append("\n目前课程：${courses.joinToString("、")}")
                        }
                    )
                } else if (allDecks.value.any {
                        it.name.equals(name, ignoreCase = true) &&
                            it.category.equals(category, ignoreCase = true)
                    }) {
                    appendAssistantMessage("ASSISTANT", "「$category」课程中已经有名为「$name」的文件夹，因此没有重复创建。")
                } else {
                    val payload = JSONObject()
                        .put("name", name)
                        .put("category", category)
                        .put("description", result.optString("description").trim().take(200))
                        .put("colorHex", result.optString("colorHex", "#426B63"))
                    _assistantPendingAction.value = AssistantPendingAction(
                        id = UUID.randomUUID().toString(),
                        type = "CREATE_FOLDER",
                        deckId = 0L,
                        title = "创建文件夹「$name」",
                        description = "课程：$category\n确认后才会写入单词库。",
                        payload = payload.toString()
                    )
                    appendAssistantMessage("ASSISTANT", "文件夹数据已准备好，请确认是否创建。", kind = "STATUS")
                }
            }
            "SORT" -> {
                val deck = allDecks.value.firstOrNull { it.id == result.optLong("deckId") }
                    ?: fallbackDeck
                if (deck == null || deck.id !in _assistantSelectedDeckIds.value) {
                    appendAssistantMessage("ASSISTANT", "我找不到可编辑的目标文件夹，请在输入框上方调整文件夹范围。")
                }
                else prepareAlphabeticalSort(deck, result.optString("direction").equals("ZA", true))
            }
            "COUNTABILITY" -> {
                val deck = allDecks.value.firstOrNull { it.id == result.optLong("deckId") }
                    ?: fallbackDeck
                val source = result.optJSONArray("updates") ?: JSONArray()
                val deckCardIds = allCards.value.filter { it.deckId == deck?.id }.mapTo(hashSetOf()) { it.id }
                val currentById = allCards.value.associateBy { it.id }
                val validated = JSONArray()
                for (index in 0 until source.length()) {
                    val item = source.optJSONObject(index) ?: continue
                    val cardId = item.optLong("cardId")
                    val suggestedValue = item.optString("partOfSpeech").trim().take(80)
                    val current = currentById[cardId] ?: continue
                    val newValue = mergeCountabilityLabel(current.partOfSpeech, suggestedValue)
                        ?: continue
                    if (cardId !in deckCardIds) continue
                    validated.put(
                        JSONObject()
                            .put("cardId", cardId)
                            .put("word", current.word)
                            .put("oldPartOfSpeech", current.partOfSpeech)
                            .put("newPartOfSpeech", newValue)
                    )
                }
                if (deck == null || deck.id !in _assistantSelectedDeckIds.value || validated.length() == 0) {
                    appendAssistantMessage("ASSISTANT", "$message\n\n没有找到可安全应用的词性修改。")
                } else {
                    _assistantPendingAction.value = AssistantPendingAction(
                        id = UUID.randomUUID().toString(),
                        type = "COUNTABILITY",
                        deckId = deck.id,
                        title = "补充名词可数性",
                        description = "文件夹：${deck.name}\n预计修改 ${validated.length()} 张卡片；原有词性会保留并可恢复。",
                        payload = validated.toString()
                    )
                    appendAssistantMessage("ASSISTANT", message, kind = "STATUS")
                }
            }
            "QUIZ" -> {
                val questions = result.optJSONArray("questions") ?: JSONArray()
                if (questions.length() == 0) appendAssistantMessage("ASSISTANT", message)
                else appendAssistantMessage(
                    "ASSISTANT",
                    message,
                    kind = "QUIZ",
                    payload = JSONObject()
                        .put("questions", questions)
                        .put("completed", false)
                        .put("attempts", JSONArray())
                        .toString()
                )
            }
            "ARTICLE" -> appendAssistantMessage(
                "ASSISTANT",
                formatAssistantDisplayText(result.optString("article")).ifBlank { message },
                kind = "ARTICLE",
                payload = result.toString()
            )
            "CONFUSABLES" -> appendAssistantMessage(
                "ASSISTANT",
                formatConfusableResult(result, fallbackDeck).ifBlank { message },
                kind = "CONFUSABLES"
            )
            else -> appendAssistantMessage("ASSISTANT", message)
        }
    }

    private fun buildAssistantPrompt(request: String, deck: Deck?, cards: List<Flashcard>): String {
        val recent = _assistantMessages.value.takeLast(6).joinToString("\n") {
            val quizResult = assistantQuizResultForPrompt(it)
            "${it.role}: ${it.content.take(500)}${quizResult?.let { result -> "\n$result" }.orEmpty()}"
        }
        val quizHistory = _assistantMessages.value
            .filter { it.kind == "QUIZ" }
            .takeLast(3)
            .mapNotNull(::assistantQuizResultForPrompt)
            .joinToString("\n")
        val deckList = JSONArray(allDecks.value.map {
            JSONObject().put("id", it.id).put("name", it.name).put("course", it.category)
        })
        val cardList = JSONArray(cards.map {
            JSONObject()
                .put("id", it.id)
                .put("deckId", it.deckId)
                .put("word", it.word)
                .put("phonetic", it.phonetic)
                .put("notes", it.notes)
                .put("partOfSpeech", it.partOfSpeech)
                .put("definition", it.definition)
                .put("exampleSentence", it.exampleSentence)
                .put("exampleTranslation", it.exampleTranslation)
                .put("mistakeCount", it.mistakeCount)
                .put("repetitionCount", it.repetitionCount)
                .put("isMastered", it.isMastered)
        })
        val grammarList = JSONArray(allGrammarNotes.value.take(120).map {
            JSONObject().put("id", it.id).put("course", it.course).put("folder", it.folder)
                .put("title", it.title).put("category", it.category).put("level", it.level)
                .put("summary", it.summary).put("tags", it.tags)
        })
        val logSummary = recentStudyLogs.value.groupBy { it.cardId }.entries
            .mapNotNull { (cardId, logs) ->
                allCards.value.firstOrNull { it.id == cardId }?.let { card ->
                    JSONObject().put("cardId", cardId).put("word", card.word)
                        .put("reviews", logs.size).put("lowRatings", logs.count { it.rating <= 2 })
                }
            }.sortedByDescending { it.optInt("lowRatings") }.take(40)
        val selectedIds = _assistantSelectedDeckIds.value
        val selectedDecks = allDecks.value.filter { it.id in selectedIds }
        val chatStyleInstruction = when (settings.value.aiChatStyle) {
            "RELAXED" -> """
                目前聊天风格是「轻松」。用自然、亲切、像熟悉朋友般的简体中文交谈，可以适度幽默；
                用户可以聊生活、兴趣或其他日常话题，不必强行把内容拉回英文，但事实仍须可靠，不能捏造。
            """.trimIndent()
            "STRICT" -> """
                目前聊天风格是「严谨」。只回答英文学习、语言知识、单词卡、学习规划与 Vocab App 操作相关内容；
                对无关的闲聊或其他领域要求要简短、礼貌拒绝。回答前要审慎辨别不确定性，没有可靠依据就明确说明，禁止猜测。
            """.trimIndent()
            else -> """
                目前聊天风格是「一般」。使用清楚、自然、友善的简体中文回答，信息不足时先询问，避免武断或过度冗长。
            """.trimIndent()
        }
        return """
            你是 Vocab App 内的简体中文 AI 助手。一般英文学习与知识问题可以使用你的可靠知识回答；
            涉及用户 App 内实际有哪些数据时，只能根据下方提供的真实数据，不可捏造，也不可声称已修改数据。
            $chatStyleInstruction
            App 会在用户确认后执行写入。整体只返回一个 JSON 对象，不可加 Markdown 代码围栏。
            message、article、analysis 与 explanation 等文字字段可以使用精简 Markdown：
            #～### 标题、**粗体**、- 条列、1. 编号、行内公式 $...$、独立公式 $$...$$。
            物理、化学与数学符号优先使用标准 LaTeX（例如 \\alpha、\\sqrt{x}、\\frac{a}{b}），
            但不要为普通短句加入多余格式，也不要输出 HTML。

            type 只能是 REPLY、CREATE_FOLDER、UPDATE_FOLDER、CREATE_GRAMMAR、UPDATE_GRAMMAR、UPDATE_CARDS、TRANSFER_CARDS、ADD_CARDS、SPLIT_FOLDER、
            MERGE_FOLDERS、ENRICH_CARDS、NORMALIZE_CHINESE、AUDIT_REPORT、CREATE_WEAKNESS_FOLDER、UPDATE_DAILY_PLAN、
            LEARNING_ANALYSIS、SORT、COUNTABILITY、ARTICLE、CONFUSABLES、QUIZ。
            通用格式：{"type":"REPLY","message":"简体中文回复","deckId":0}
            用户要求添加或创建文件夹时使用 CREATE_FOLDER，另加：
            {"name":"文件夹名称","category":"课程名称","description":"简短说明，可空白","colorHex":"#426B63"}
            如果用户和最近对话都没有指定要放入哪个课程，category 必须返回空字符串；
            message 要询问用户要放在哪个课程，绝对不可自行选择「通用」或猜测课程。
            不可声称已创建；App 会显示确认卡并检查同一课程内的重复名称。
            UPDATE_FOLDER：{"type":"UPDATE_FOLDER","deckId":1,"name":"新名称，未改则保留","category":"新课程，未改则保留"}
            UPDATE_GRAMMAR：{"type":"UPDATE_GRAMMAR","grammarId":1,"course":"课程","folder":"文件夹","title":"标题","category":"分类","level":"程度","summary":"摘要","tags":"标签"}
            只有用户明确要求修改既有语法时使用；grammarId 必须来自可读取的语法数据，未要求改动的字段必须原样返回。App 会先显示确认卡。
            CREATE_GRAMMAR：{"type":"CREATE_GRAMMAR","libraryId":1,"grammar":{"title":"语法名称","category":"分类","level":"程度","summary":"核心概念","structure":"句型结构","usage":"使用时机","exampleSentence":"英文例句","exampleTranslation":"例句的简体中文翻译","commonMistakes":"常见错误","comparison":"比较","tags":"标签","patterns":[{"title":"句型","formula":"结构","meaning":"意思","usage":"使用时机","examples":[{"sentence":"英文例句","translation":"简体中文翻译"}]}],"questions":[]}}
            用户要求添加语法时使用 CREATE_GRAMMAR。libraryId 必须来自下方真实语法库，若未指定目标且存在多个可能语法库，先询问，绝不能猜测。
            可以提供 questions，格式为 {"grammarPatternIndex":0,"type":"CLOZE_CHOICE","prompt":"He {{answer}} studied.","answer":"has","options":"has,have,had","explanation":"解析"}；每题恰好一个 {{answer}}，选项必须包含正确答案。
            TRANSFER_CARDS：{"type":"TRANSFER_CARDS","mode":"MOVE或COPY","targetDeckId":2,"cardIds":[1,2]}
            ADD_CARDS：{"type":"ADD_CARDS","targetDeckId":2,"cards":[{"word":"","phonetic":"","partOfSpeech":"","definition":"","exampleSentence":"","exampleTranslation":""}]}
            外部推荐字必须是可靠英文知识，且不可声称原本存在 App。
            SPLIT_FOLDER：{"type":"SPLIT_FOLDER","sourceDeckId":1,"groups":[{"name":"分类名称","category":"课程","cardIds":[1,2]}]}
            cardIds 只能使用提供的真实卡片，每张最多出现在一组；没有足够依据时先询问用户分类方式。
            MERGE_FOLDERS：{"type":"MERGE_FOLDERS","targetDeckId":1,"sourceDeckIds":[2,3]}
            ENRICH_CARDS：{"type":"ENRICH_CARDS","updates":[{"cardId":1,"phonetic":"","partOfSpeech":"","definition":"","exampleSentence":"","exampleTranslation":""}]}
            只能补空白字段，内容必须可靠，不可改写既有字段。
            已有英文例句但缺翻译时，必须忠实翻译原有英文例句，禁止改写或另造中文句子。
            UPDATE_CARDS：{"type":"UPDATE_CARDS","updates":[{"cardId":1,"definition":"依用户指令改写的内容","exampleTranslation":"原有英文例句的翻译"}]}
            只有用户明确要求修改、替换、重写或清除字段时使用，允许覆盖非空白内容；未要求修改的字段不要输出。
            可修改 word、phonetic、partOfSpeech、definition、exampleSentence、exampleTranslation、notes；cardId 必须是真实卡片 ID。
            修改英文例句时若已有中文翻译，必须一并翻译新例句，不能留下旧句的翻译。App 会先显示前后差异供确认，且可恢复。
            NORMALIZE_CHINESE：{"type":"NORMALIZE_CHINESE","updates":[{"cardId":1,"definition":"简体中文解释","exampleTranslation":"简体中文例句翻译"}]}
            只有用户明确要求统一、修正或转换中文文字时使用。逐张检查提供的真实卡片，只把 definition 与 exampleTranslation 的繁体中文转为简体中文；
            必须保留原意、标点和原有内容，不可翻译或改写英文字段，也不可用新的例句取代原文。没有变更的卡片不要放入 updates。
            AUDIT_REPORT：另加 suspectedTypos：[ {"cardId":1,"suggestion":"正确拼字","reason":"理由"} ]；不确定就不要列为拼错。
            CREATE_WEAKNESS_FOLDER：{"type":"CREATE_WEAKNESS_FOLDER","name":"薄弱点复习","category":"课程，未指定留空","cardIds":[真实低评分或错题卡ID]}
            UPDATE_DAILY_PLAN：{"type":"UPDATE_DAILY_PLAN","dailyGoalCards":20,"dailyNewCardLimit":10,"dailyReviewLimit":50,"message":"依真实纪录说明理由"}
            LEARNING_ANALYSIS：另加 analysis，根据真实学习摘要分析，不可捏造次数。
            SORT 另加 direction（AZ 或 ZA）。
            COUNTABILITY 另加 updates：[ {"cardId":1,"partOfSpeech":"n. [C]"} ]。保留原本非名词词性；不确定时不要输出该卡。
            ARTICLE 必须提供自然完整、尽量使用相关单词的英文文章，并返回：
            {"type":"ARTICLE","message":"简短说明","article":"英文文章","articleTranslation":"完整简体中文翻译",
            "targetCardIds":[真实卡片ID],"sentenceTranslations":[{"sentence":"英文原句","translation":"简体中文逐句翻译"}],
            "questions":[{"prompt":"阅读理解或完形填空题目","explanation":"简体中文解析","cardId":1,"options":[{"text":"选项","correct":true}]}]}
            targetCardIds 与 cardId 只能使用提供的真实卡片 ID。默认出 5 题并混合阅读理解与完形填空；每题恰好一个正确答案。
            CONFUSABLES 必须先从提供的真实 App 卡片选出 folderWord，再用你的英文知识找出外部的易混淆字；
            不可把外部补充字说成原本就在文件夹。另加 groups：
            [{"folderWord":"affect","relatedWords":[{"word":"effect","explanation":"两字的词性、意思与用法差异"}]}]
            folderWord 必须逐字对应提供的卡片；relatedWords 可以来自模型知识。另可提供简短 message，但不要把 groups 再序列化到 analysis 字符串。
            QUIZ 另加 questions，每题格式：
            {"prompt":"题目","explanation":"简体中文解释","options":[{"text":"选项","correct":true}]}
            默认产生 15 题，可依可用内容调整为 10～20 题；题目不可重复或只替换顺序凑数。
            每题必须恰好一个 correct=true。涉及 App 内容时必须能由提供的卡片验证；
            若使用模型知识补充的易混淆字，可针对真实英文用法出题，但不可声称外部单词已存在文件夹。

            AI 可以读取下方提供的所有文件夹与卡片，也可以回答文件夹以外的一般英文知识。
            用户允许 AI 写入或修改的文件夹（这只是编辑权限，不是知识或读取限制）：${JSONArray(selectedDecks.map { JSONObject().put("id", it.id).put("name", it.name) })}
            目前明确指定的文件夹：${deck?.let { "${it.name}（id=${it.id}）" } ?: "未指定或多选"}
            可用文件夹：$deckList
            可读取的 App 卡片：$cardList
            可读取及可在确认后编辑的语法笔记：$grammarList
            可添加语法的真实语法库：${JSONArray(grammarLibraries.value.map { JSONObject().put("id", it.id).put("course", it.course).put("name", it.name) })}
            真实学习摘要（依低评分排序）：${JSONArray(logSummary)}
            测试进度摘要（已压缩，这是 App 保存的真实状态）：
            ${quizHistory.ifBlank { "尚无测试纪录" }}
            最近对话：
            $recent

            用户最新要求：$request
        """.trimIndent()
    }

    private fun resolveAssistantDeck(request: String): Deck? {
        val selectedIds = _assistantSelectedDeckIds.value
        val explicit = allDecks.value
            .filter {
                it.name.isNotBlank() && request.contains(it.name, ignoreCase = true)
            }
            .maxByOrNull { it.name.length }
        return explicit
            ?: selectedIds.singleOrNull()?.let { id -> allDecks.value.firstOrNull { it.id == id } }
            ?: assistantContextDeckId?.takeIf { it in selectedIds }
                ?.let { id -> allDecks.value.firstOrNull { it.id == id } }
    }

    private fun selectAssistantContextCards(request: String, contextDeck: Deck?): List<Flashcard> {
        val cards = allCards.value
        val onlyAsksQuizRecord = (request.contains("成绩") || request.contains("错题") || request.contains("做到哪")) &&
            (request.contains("测试") || request.contains("刚刚"))
        if (onlyAsksQuizRecord) return emptyList()
        if (contextDeck != null) return cards.filter { it.deckId == contextDeck.id }.take(120)
        val editableIds = _assistantSelectedDeckIds.value
        val needsAppData = listOf(
            "文件夹", "单词库", "卡片", "这些单词", "我的单词", "排序",
            "词性", "易混淆", "相近", "文章", "测试", "题目"
        ).any { request.contains(it, ignoreCase = true) }
        // A custom scope is an explicit request to let the assistant read those folders.  Do not
        // silently return an empty context merely because the wording did not contain a keyword.
        if (!needsAppData && editableIds.isEmpty()) return emptyList()
        val scopedCards = if (editableIds.isEmpty()) cards else cards.filter { it.deckId in editableIds }
        val terms = request.lowercase()
            .split(Regex("[^a-zA-Z\u4e00-\u9fff]+"))
            .filter { it.length >= 2 }
            .toSet()
        // Take a balanced sample so that one large folder cannot crowd every other selected
        // folder out of the prompt. This also keeps token use predictable.
        if (editableIds.size > 1 && terms.isEmpty()) {
            val perDeck = (80 / editableIds.size).coerceAtLeast(4)
            return editableIds.sorted().flatMap { deckId ->
                scopedCards.filter { it.deckId == deckId }.take(perDeck)
            }.take(80)
        }
        return scopedCards.sortedByDescending { card ->
            val searchable = "${card.word} ${card.definition} ${card.partOfSpeech}".lowercase()
            terms.count { it in searchable } * 10
        }.take(80)
    }

    private fun assistantDetailedStatus(
        request: String,
        contextDeck: Deck?,
        contextCardCount: Int,
        editableDeckCount: Int
    ): String = when {
        request.contains("测试") || request.contains("题目") ->
            "正在整理 ${contextCardCount} 张相关卡片与测试纪录，准备产生约 15 题并检查答案…"
        request.contains("混淆") || request.contains("相近") || request.contains("很像") ->
            "正在核对${contextDeck?.let { "「${it.name}」" } ?: "单词库"}的 $contextCardCount 张卡片，并比对模型中的相近字与用法…"
        request.contains("文章") ->
            "正在挑选 $contextCardCount 个可用单词，规划文章内容并检查语意…"
        request.contains("排序") || request.contains("修改") || request.contains("添加") ->
            "正在验证目标数据与编辑权限；目前允许修改 $editableDeckCount 个文件夹…"
        else -> "正在整理 $contextCardCount 张相关卡片并准备回复…"
    }

    private fun formatAssistantDisplayText(value: String): String {
        val text = value.trim()
        if (text.isBlank()) return ""
        if (!text.startsWith("[") && !text.startsWith("{")) return text
        return runCatching {
            val items = if (text.startsWith("[")) JSONArray(text)
            else JSONArray().put(JSONObject(text))
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val words = item.optJSONArray("words")?.let { array ->
                        buildList {
                            for (wordIndex in 0 until array.length()) {
                                array.optString(wordIndex).trim()
                                    .takeIf { it.isNotBlank() }
                                    ?.let(::add)
                            }
                        }
                    }.orEmpty()
                    val title = words.joinToString("／").ifBlank {
                        item.optString("title").trim()
                    }
                    val explanation = item.optString("explanation").trim()
                        .ifBlank { item.optString("analysis").trim() }
                    val readable = listOf(title, explanation)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                    if (readable.isNotBlank()) add(readable)
                }
            }.joinToString("\n\n").ifBlank { text }
        }.getOrDefault(text)
    }

    private fun updateAssistantQuizPayload(messageId: String, update: (JSONObject) -> Unit) {
        val index = assistantAllMessages.indexOfFirst { it.id == messageId && it.kind == "QUIZ" }
        if (index < 0) return
        val message = assistantAllMessages[index]
        val root = runCatching { JSONObject(message.payload) }.getOrElse {
            val legacyQuestions = runCatching { JSONArray(message.payload) }.getOrDefault(JSONArray())
            JSONObject()
                .put("questions", legacyQuestions)
                .put("completed", false)
                .put("attempts", JSONArray())
        }
        update(root)
        assistantAllMessages[index] = message.copy(payload = root.toString())
        refreshAssistantMessages()
        persistAssistantState()
    }

    private fun assistantQuizResultForPrompt(message: AssistantMessage): String? {
        if (message.kind != "QUIZ" || message.payload.isBlank()) return null
        val root = runCatching { JSONObject(message.payload) }.getOrNull() ?: return null
        val questions = root.optJSONArray("questions") ?: return null
        val wrongAnswers = root.optJSONArray("wrongAnswers") ?: JSONArray()
        val wrongSummary = buildList {
            for (index in 0 until wrongAnswers.length()) {
                val wrong = wrongAnswers.optJSONObject(index) ?: continue
                val question = questions.optJSONObject(wrong.optInt("questionIndex")) ?: continue
                add(question.optString("prompt").replace("\n", " ").take(70))
            }
        }.take(6).joinToString("；")
        return if (root.optBoolean("completed")) {
            "测试状态：已完成，答对 ${root.optInt("latestCorrect")}/${root.optInt("latestTotal")} 题" +
                if (wrongSummary.isNotBlank()) "；错题：$wrongSummary" else "。"
        } else {
            val current = root.optInt("currentIndex", 0).coerceAtMost(questions.length())
            "测试状态：进行中，已完成 $current/${questions.length()} 题，目前答对 ${root.optInt("correctCount", 0)} 题" +
                if (wrongSummary.isNotBlank()) "；目前错题：$wrongSummary" else "。"
        }
    }

    private fun formatConfusableResult(result: JSONObject, targetDeck: Deck?): String {
        val groups = result.optJSONArray("groups") ?: JSONArray()
        if (groups.length() == 0) {
            return formatAssistantDisplayText(result.optString("analysis"))
        }
        val allCardSnapshot = allCards.value
        val sourceCards = targetDeck?.let { deck ->
            allCardSnapshot.filter { it.deckId == deck.id }
        } ?: allCardSnapshot
        return buildList {
            for (index in 0 until groups.length()) {
                val group = groups.optJSONObject(index) ?: continue
                val requestedSource = group.optString("folderWord").trim()
                val sourceCard = sourceCards.firstOrNull {
                    it.word.equals(requestedSource, ignoreCase = true)
                } ?: continue
                val related = group.optJSONArray("relatedWords") ?: JSONArray()
                val relatedLines = buildList {
                    for (relatedIndex in 0 until related.length()) {
                        val item = related.optJSONObject(relatedIndex) ?: continue
                        val word = item.optString("word").trim()
                        if (word.isBlank() || word.equals(sourceCard.word, ignoreCase = true)) continue
                        val existing = allCardSnapshot.firstOrNull {
                            it.word.equals(word, ignoreCase = true)
                        }
                        val sourceLabel = when {
                            existing?.deckId == sourceCard.deckId -> "文件夹内"
                            existing != null -> "App 其他文件夹"
                            else -> "AI 知识补充"
                        }
                        val explanation = item.optString("explanation").trim()
                        add(
                            buildString {
                                append("• $word（$sourceLabel）")
                                if (explanation.isNotBlank()) append("\n  $explanation")
                            }
                        )
                    }
                }
                if (relatedLines.isNotEmpty()) {
                    add(
                        buildString {
                            append("${sourceCard.word}（文件夹内单词）")
                            append("\n")
                            append(relatedLines.joinToString("\n"))
                        }
                    )
                }
            }
        }.joinToString("\n\n")
            .ifBlank { formatAssistantDisplayText(result.optString("analysis")) }
    }

    private fun isAssistantOperationRequest(request: String): Boolean {
        val operationWords = listOf(
            "排序", "排列", "修改", "加上", "补上", "补齐", "创建", "添加",
            "移动", "复制", "删除", "清空", "文章", "测试", "题目", "混淆"
        )
        return operationWords.any { request.contains(it, ignoreCase = true) }
    }

    private fun mergeCountabilityLabel(current: String, suggested: String): String? {
        val marker = Regex("\\[(?:C/U|U/C|C|U)]", RegexOption.IGNORE_CASE)
            .find(suggested)
            ?.value
            ?.uppercase()
            ?.replace("U/C", "C/U")
            ?: return null
        val nounToken = Regex(
            "(?<![A-Za-z])n\\.?\\s*(?:\\[(?:C/U|U/C|C|U)])?",
            RegexOption.IGNORE_CASE
        )
        val base = current.ifBlank { suggested.substringBefore("[").trim() }
        if (!nounToken.containsMatchIn(base)) return null
        return nounToken.replace(base) { match ->
            val noun = if (match.value.trimStart().startsWith("N")) "N." else "n."
            "$noun $marker"
        }.trim()
    }

    private fun appendAssistantMessage(
        role: String,
        content: String,
        kind: String = "TEXT",
        payload: String = "",
        attachmentUris: List<String> = emptyList()
    ): String {
        val conversationId = _assistantCurrentConversationId.value ?: return ""
        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        assistantAllMessages += AssistantMessage(
            id = messageId,
            conversationId = conversationId,
            role = role,
            content = content.trim(),
            createdAt = now,
            kind = kind,
            payload = payload,
            attachmentUris = attachmentUris.take(5)
        )
        _assistantConversations.value = _assistantConversations.value.map {
            if (it.id == conversationId) it.copy(updatedAt = now) else it
        }.sortedByDescending { it.updatedAt }
        refreshAssistantMessages()
        persistAssistantState()
        return messageId
    }

    private fun updateAssistantConversationTitle(conversationId: String, firstMessage: String) {
        _assistantConversations.value = _assistantConversations.value.map { conversation ->
            if (conversation.id == conversationId && conversation.title in setOf("新对话", "无痕对话")) {
                conversation.copy(title = firstMessage.replace("\n", " ").take(24))
            } else conversation
        }
        persistAssistantState()
    }

    private fun refreshAssistantMessages() {
        val currentId = _assistantCurrentConversationId.value
        val currentMessages = assistantAllMessages
            .filter { it.conversationId == currentId }
            .distinctBy { it.id }
            .sortedBy { it.createdAt }
        _assistantMessages.value = currentMessages
        val latestActionEvent = currentMessages.lastOrNull {
            it.kind == "ACTION" || it.kind == "ACTION_UNDONE"
        }
        _assistantUndoAction.value = latestActionEvent
            ?.takeIf { it.kind == "ACTION" }
            ?.payload
            ?.let(::assistantActionFromJson)
    }

    private fun persistAssistantState() {
        val persistedIds = _assistantConversations.value.filterNot { it.incognito }.mapTo(hashSetOf()) { it.id }
        _savedArticles.value = assistantAllMessages.filter { it.conversationId in persistedIds }.savedArticles()
        assistantChatStore.save(_assistantConversations.value, assistantAllMessages)
    }

    private fun JSONArray.longValues(): List<Long> = buildList {
        for (index in 0 until length()) add(optLong(index))
    }.filter { it > 0L }

    private fun AssistantPendingAction.toJson(): String = JSONObject()
        .put("id", id)
        .put("type", type)
        .put("deckId", deckId)
        .put("title", title)
        .put("description", description)
        .put("payload", payload)
        .toString()

    private fun assistantActionFromJson(value: String): AssistantPendingAction? = runCatching {
        val root = JSONObject(value)
        AssistantPendingAction(
            id = root.optString("id"),
            type = root.optString("type"),
            deckId = root.optLong("deckId"),
            title = root.optString("title"),
            description = root.optString("description"),
            payload = root.optString("payload")
        ).takeIf { it.id.isNotBlank() && it.type.isNotBlank() }
    }.getOrNull()

    override fun onCleared() {
        super.onCleared()
        ttsManager.shutdown()
    }
}
