package com.example

import com.example.data.api.DirectAiService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectAiServiceJsonTest {

    @Test
    fun completeNestedJsonIsExtractedWithoutTrailingText() {
        val input = """prefix {"cards":[{"word":"apple","definition":"苹果"}]} suffix"""
        val start = input.indexOf('{')

        assertEquals(
            """{"cards":[{"word":"apple","definition":"苹果"}]}""",
            DirectAiService.findCompleteJson(input, start)
        )
    }

    @Test
    fun truncatedJsonIsRejectedBeforeOrgJsonParsing() {
        val input = "{\"cards\":[{\"word\":\"apple"

        assertNull(DirectAiService.findCompleteJson(input, 0))
    }

    @Test
    fun bracesInsideJsonStringDoNotEndPayloadEarly() {
        val input = """{"cards":[{"definition":"使用 { } 符号"}]} trailing"""

        assertEquals(
            """{"cards":[{"definition":"使用 { } 符号"}]}""",
            DirectAiService.findCompleteJson(input, 0)
        )
    }
}
