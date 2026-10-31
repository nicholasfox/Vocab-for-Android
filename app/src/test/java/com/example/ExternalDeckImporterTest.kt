package com.example

import com.example.data.importer.ExternalDeckImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalDeckImporterTest {
    @Test
    fun parsesQuizletStyleTabSeparatedText() {
        val parsed = ExternalDeckImporter.parseText(
            "apple\t苹果\napplication\t申请；应用程序\napply\t申请；应用"
        )
        assertEquals(3, parsed.cards.size)
        assertEquals("apple", parsed.cards.first().word)
        assertEquals("苹果", parsed.cards.first().definition)
    }

    @Test
    fun parsesJsonCardsAndDetectsSource() {
        val parsed = ExternalDeckImporter.parseText(
            """{"cards":[{"word":"apple","definition":"苹果","example":"I ate an apple."}]}"""
        )
        assertEquals("I ate an apple.", parsed.cards.single().exampleSentence)
        assertEquals("Quizlet", ExternalDeckImporter.detectSource("https://quizlet.com/123/test")?.name)
    }

    @Test
    fun recognizesOnlyDirectDataFiles() {
        assertTrue(ExternalDeckImporter.isDirectTextUrl("https://example.com/deck.csv?download=1"))
    }
}
