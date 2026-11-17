package com.friday.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextProcessingTest {
    @Test fun `markdown blocks`() {
        val md = """
            # Title
            Intro **bold** and `code`.

            - one
            - two
              - nested
            1. first
            2) second

            ```kotlin
            val x = 1
            ```
            > quoted
            ---
            | A | B |
            |---|---|
            | 1 | 2 |
        """.trimIndent()
        val b = Markdown.parse(md)
        assertEquals(MdBlock.Heading(1, "Title"), b[0])
        assertEquals(MdBlock.Paragraph("Intro **bold** and `code`."), b[1])
        assertEquals(MdBlock.ListItem("one", false, "•", 0), b[2])
        assertEquals(1, (b[4] as MdBlock.ListItem).indent)
        assertEquals(MdBlock.ListItem("second", true, "2.", 0), b[6])
        assertEquals(MdBlock.Code("kotlin", "val x = 1", true), b[7])
        assertEquals(MdBlock.Quote("quoted"), b[8])
        assertEquals(MdBlock.Rule, b[9])
        assertEquals(MdBlock.Table(listOf("A", "B"), listOf(listOf("1", "2"))), b[10])
    }

    @Test fun `streaming unclosed code fence renders as open code block`() {
        val b = Markdown.parse("Here:\n```py\nprint(1)")
        assertEquals(MdBlock.Code("py", "print(1)", false), b.last())
    }

    @Test fun `inline spans`() {
        val s = Markdown.inline("a **b** *c* `d` [e](http://x) snake_case_name 2*3")
        assertEquals("a b c d e snake_case_name 2*3", s.joinToString("") { it.text })
        assertTrue(s.first { it.text == "b" }.bold)
        assertTrue(s.first { it.text == "c" }.italic)
        assertTrue(s.first { it.text == "d" }.code)
        assertFalse(s.any { it.italic && it.text.contains("case") })
    }

    @Test fun `telugu text survives markdown and is detected`() {
        val t = "**నమస్తే** ప్రపంచం"
        assertEquals("నమస్తే ప్రపంచం", Markdown.toPlainText(t))
        assertEquals(AppLanguage.TELUGU, Script.dominantLanguage(t))
        assertEquals(AppLanguage.ENGLISH, Script.dominantLanguage("Use the print() function"))
        assertEquals(AppLanguage.TELUGU, Script.dominantLanguage("Python లో print() ఫంక్షన్ ఉపయోగించండి"))
    }

    @Test fun `speech chunks keep order and size`() {
        val text = (1..20).joinToString(" ") { "Sentence number $it is here." }
        val chunks = SpeechText.chunks(text, maxChars = 120)
        assertTrue(chunks.all { it.length <= 120 })
        assertEquals(text, chunks.joinToString(" "))
        val long = "word ".repeat(200).trim()
        assertTrue(SpeechText.chunks(long, 100).all { it.length <= 100 })
        assertEquals(listOf("నమస్తే. ఎలా ఉన్నారు?"), SpeechText.chunks("నమస్తే. ఎలా ఉన్నారు?"))
    }

    @Test fun `lan policy`() {
        listOf("192.168.1.20", "10.0.0.5", "172.20.1.1", "100.101.1.2", "localhost", "my-pc.local", "fe80::1").forEach { assertTrue(it, LanAddressPolicy.isPrivateHost(it)) }
        listOf("8.8.8.8", "172.32.0.1", "example.com", "192.169.0.1").forEach { assertFalse(it, LanAddressPolicy.isPrivateHost(it)) }
        assertEquals(LanAddressPolicy.Result.Ok("http://192.168.1.20:8765"), LanAddressPolicy.normalize("192.168.1.20"))
        assertEquals(LanAddressPolicy.Result.Ok("http://192.168.1.20:9000"), LanAddressPolicy.normalize("http://192.168.1.20:9000/"))
        assertEquals(LanAddressPolicy.Result.Ok("https://friday.example.com:443"), LanAddressPolicy.normalize("https://friday.example.com"))
        assertTrue(LanAddressPolicy.normalize("http://example.com") is LanAddressPolicy.Result.Invalid)
        assertTrue(LanAddressPolicy.normalize("") is LanAddressPolicy.Result.Invalid)
        assertTrue(LanAddressPolicy.normalize("ftp://192.168.1.2") is LanAddressPolicy.Result.Invalid)
    }

    @Test fun `command replies are localized`() {
        assertTrue(Script.containsTelugu(CommandReplies.language(AppLanguage.TELUGU)))
        assertEquals("Temperature set to 0.7.", CommandReplies.temperature(0.7f, AppLanguage.ENGLISH))
        assertEquals("Temperature set to 1.", CommandReplies.temperature(1f, AppLanguage.ENGLISH))
    }
}
