package com.example.data.assistant

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.entity.*
import kotlinx.coroutines.flow.first

/** Confirmed assistant creations use a real library and one atomic transaction. */
suspend fun createAssistantGrammar(db: AppDatabase, libraryId: Long, draft: GrammarDraft): Long = db.withTransaction {
    val library = db.grammarLibraryDao().getAllOnce().firstOrNull { it.id == libraryId }
        ?: error("找不到目标语法库，请重新指定")
    require(draft.title.isNotBlank()) { "语法名称不能空白" }
    require(listOf(draft.summary, draft.structure, draft.usage).any { it.isNotBlank() }) { "语法必须包含核心概念、结构或使用说明" }
    require(db.grammarNoteDao().getAllOnce().none {
        it.course.equals(library.course, true) && it.folder.equals(library.name, true) && it.title.equals(draft.title.trim(), true)
    }) { "这个语法库已经有同名语法，没有覆盖原有内容" }
    draft.questions.forEach { question ->
        require(question.prompt.windowed(10).count { it == "{{answer}}" } == 1 && question.answer.isNotBlank()) { "语法题目必须有唯一答案位置及答案" }
        val choices = question.options.split(',', '、', '\n').map(String::trim).filter(String::isNotBlank)
        require(choices.isEmpty() || choices.any { it.equals(question.answer.trim(), true) }) { "题目选项缺少正确答案" }
        require(choices.map { it.lowercase() }.distinct().size == choices.size) { "题目选项不能重复" }
    }
    val first = draft.questions.firstOrNull()
    val noteId = db.grammarNoteDao().insert(GrammarNote(
        course = library.course, folder = library.name, title = draft.title.trim(), category = draft.category,
        level = draft.level, summary = draft.summary, structure = draft.structure, usage = draft.usage,
        exampleSentence = draft.exampleSentence, exampleTranslation = draft.exampleTranslation,
        commonMistakes = draft.commonMistakes, comparison = draft.comparison, tags = draft.tags, sourceType = "AI",
        questionTemplate = first?.prompt ?: draft.questionTemplate, answer = first?.answer ?: draft.answer,
        acceptedAnswers = first?.acceptedAnswers ?: draft.acceptedAnswers, options = first?.options ?: draft.options,
        explanation = first?.explanation ?: draft.explanation
    ))
    val patterns = draft.patterns.ifEmpty {
        if (draft.structure.isBlank() && draft.exampleSentence.isBlank()) emptyList() else listOf(GrammarPatternDraft(
            formula = draft.structure, meaning = draft.summary, usage = draft.usage,
            examples = if (draft.exampleSentence.isBlank()) emptyList() else listOf(GrammarExampleDraft(sentence = draft.exampleSentence, translation = draft.exampleTranslation))
        ))
    }
    val ids = patterns.mapIndexed { index, pattern ->
        val id = db.grammarContentDao().insertPattern(GrammarPattern(grammarNoteId = noteId,
            title = pattern.title, formula = pattern.formula, meaning = pattern.meaning, usage = pattern.usage, notes = pattern.notes, sortOrder = index.toLong()))
        pattern.examples.filter { it.sentence.isNotBlank() }.forEachIndexed { exampleIndex, example ->
            db.grammarContentDao().insertExample(GrammarExample(grammarNoteId = noteId, grammarPatternId = id,
                sentence = example.sentence, translation = example.translation, highlightedText = example.highlightedText, sortOrder = exampleIndex.toLong()))
        }
        id
    }
    draft.questions.forEachIndexed { index, q ->
        db.grammarQuestionDao().insertQuestion(GrammarQuestion(grammarNoteId = noteId,
            grammarPatternId = ids.getOrNull(q.grammarPatternIndex) ?: 0, type = q.type, prompt = q.prompt,
            translation = q.translation, answer = q.answer, acceptedAnswers = q.acceptedAnswers, options = q.options,
            explanation = q.explanation, difficulty = q.difficulty.coerceIn(1, 3), sourceType = "AI", sortOrder = index.toLong()))
    }
    noteId
}

suspend fun assistantGrammarSnapshot(db: AppDatabase, noteId: Long): String =
    listOf(db.grammarNoteDao().getById(noteId), db.grammarContentDao().getPatternsForNote(noteId),
        db.grammarContentDao().getExamplesForNote(noteId), db.grammarQuestionDao().getForNote(noteId),
        db.grammarQuestionDao().getAllWeaknesses().first().filter { it.grammarNoteId == noteId }).toString()

suspend fun undoAssistantGrammarCreation(db: AppDatabase, noteId: Long, snapshot: String) = db.withTransaction {
    val note = db.grammarNoteDao().getById(noteId) ?: error("这笔语法已不存在")
    check(assistantGrammarSnapshot(db, noteId) == snapshot) { "语法已被修改或学习，为避免数据遗失，无法直接恢复删除" }
    db.grammarQuestionDao().deleteForNote(noteId)
    db.grammarQuestionDao().deleteWeaknessesForNote(noteId)
    db.grammarContentDao().deleteExamplesForNote(noteId)
    db.grammarContentDao().deletePatternsForNote(noteId)
    db.grammarNoteDao().delete(note)
}
