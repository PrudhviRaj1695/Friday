package com.friday.app.core

/**
 * Small, dependency-free Markdown parser for chat messages. It is intentionally tolerant of
 * partially streamed text: an unclosed ``` fence is rendered as a code block in progress.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItem(val text: String, val ordered: Boolean, val marker: String, val indent: Int) : MdBlock
    data class Code(val language: String, val code: String, val closed: Boolean) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                flush()
                val lang = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                var closed = false
                i++
                while (i < lines.size) {
                    if (lines[i].trim().startsWith("```")) { closed = true; break }
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks += MdBlock.Code(lang, code.toString(), closed)
                i++
                continue
            }
            if (trimmed.isEmpty()) { flush(); i++; continue }
            if (trimmed.startsWith("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                flush()
                val header = cells(trimmed)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i].trim()); i++ }
                blocks += MdBlock.Table(header, rows)
                continue
            }
            if (rule.matches(line)) { flush(); blocks += MdBlock.Rule; i++; continue }
            val h = heading.find(trimmed)
            if (h != null) { flush(); blocks += MdBlock.Heading(h.groupValues[1].length, h.groupValues[2].trim().trimEnd('#').trim()); i++; continue }
            val b = bullet.find(line)
            if (b != null) { flush(); blocks += MdBlock.ListItem(b.groupValues[2], false, "•", b.groupValues[1].length / 2); i++; continue }
            val o = ordered.find(line)
            if (o != null) { flush(); blocks += MdBlock.ListItem(o.groupValues[3], true, o.groupValues[2] + ".", o.groupValues[1].length / 2); i++; continue }
            if (trimmed.startsWith(">")) { flush(); blocks += MdBlock.Quote(trimmed.trimStart('>').trim()); i++; continue }
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(trimmed)
            i++
        }
        flush()
        return blocks
    }

    private fun cells(row: String) = row.trim().trim('|').split("|").map { it.trim() }

    /** Parses inline emphasis: **bold**, *italic* / _italic_, `code`, [label](url) -> label. */
    fun inline(text: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        fun emit() { if (buf.isNotEmpty()) { spans += MdSpan(buf.toString(), bold, italic); buf.clear() } }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) { emit(); spans += MdSpan(text.substring(i + 1, end), code = true); i = end + 1 } else { buf.append(c); i++ }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> { emit(); bold = !bold; i += 2 }
                (c == '*' || c == '_') && isEmphasisMarker(text, i) -> { emit(); italic = !italic; i++ }
                c == '[' -> {
                    val close = text.indexOf("](", i)
                    val end = if (close > 0) text.indexOf(')', close) else -1
                    if (close > i && end > close) { buf.append(text, i + 1, close); i = end + 1 } else { buf.append(c); i++ }
                }
                else -> { buf.append(c); i++ }
            }
        }
        emit()
        return spans
    }

    private fun isEmphasisMarker(text: String, i: Int): Boolean {
        // Avoid treating snake_case, 2*3 or a lone asterisk as emphasis.
        val prev = if (i > 0) text[i - 1] else ' '
        val next = if (i + 1 < text.length) text[i + 1] else ' '
        val opening = !prev.isLetterOrDigit() && !next.isWhitespace()
        val closing = !prev.isWhitespace() && !next.isLetterOrDigit()
        return (opening || closing) && text.indexOf(text[i], i + 1).let { it > 0 || closing }
    }

    /** Plain text with Markdown syntax removed (used for copy-as-text). */
    fun toPlainText(text: String): String = parse(text).joinToString("\n") { b ->
        when (b) {
            is MdBlock.Heading -> inline(b.text).joinToString("") { it.text }
            is MdBlock.Paragraph -> inline(b.text).joinToString("") { it.text }
            is MdBlock.ListItem -> "  ".repeat(b.indent) + b.marker + " " + inline(b.text).joinToString("") { it.text }
            is MdBlock.Code -> b.code
            is MdBlock.Quote -> inline(b.text).joinToString("") { it.text }
            is MdBlock.Table -> (listOf(b.header) + b.rows).joinToString("\n") { it.joinToString(" | ") }
            MdBlock.Rule -> ""
        }
    }
}
