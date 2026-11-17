package com.friday.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.app.core.Markdown
import com.friday.app.core.MdBlock

fun inlineAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    Markdown.inline(text).forEach { span ->
        val style = when {
            span.code -> SpanStyle(fontFamily = FontFamily.Monospace, background = Fx.SurfaceHigher, color = Fx.Cyan, fontSize = 0.92.em())
            else -> SpanStyle(fontWeight = if (span.bold) FontWeight.SemiBold else null, fontStyle = if (span.italic) FontStyle.Italic else null, color = if (span.bold) Color.White else Color.Unspecified)
        }
        withStyle(style) { append(span.text) }
    }
}

private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

/** Renders a chat message's Markdown with readable, script-aware typography. */
@Composable
fun MarkdownText(text: String, fontSize: Int, modifier: Modifier = Modifier, color: Color = Fx.Text) {
    val blocks = remember(text) { Markdown.parse(text) }
    val body = MaterialTheme.typography.bodyLarge.copy(color = color)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(inlineAnnotated(block.text), style = scriptAwareStyle(block.text, body.copy(fontWeight = FontWeight.SemiBold, color = Color.White), fontSize + if (block.level <= 2) 3 else 1))
                is MdBlock.Paragraph -> Text(inlineAnnotated(block.text), style = scriptAwareStyle(block.text, body, fontSize))
                is MdBlock.ListItem -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    Text(block.marker, style = scriptAwareStyle(block.text, body.copy(color = Fx.Cyan, fontWeight = FontWeight.SemiBold), fontSize), modifier = Modifier.widthIn(min = 22.dp))
                    Text(inlineAnnotated(block.text), style = scriptAwareStyle(block.text, body, fontSize), modifier = Modifier.weight(1f))
                }
                is MdBlock.Quote -> Row {
                    Box(Modifier.width(3.dp).height((fontSize * 1.6).dp).background(Fx.OutlineStrong, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text(inlineAnnotated(block.text), style = scriptAwareStyle(block.text, body.copy(color = Fx.TextSecondary, fontStyle = FontStyle.Italic), fontSize))
                }
                is MdBlock.Code -> CodeBlock(block)
                is MdBlock.Table -> TableBlock(block, fontSize)
                MdBlock.Rule -> HorizontalDivider(color = Fx.Outline)
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Fx.CodeBackground).border(1.dp, Fx.Outline, RoundedCornerShape(14.dp))) {
        Row(Modifier.fillMaxWidth().background(Fx.Surface).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(block.language.ifBlank { "code" } + if (!block.closed) " · streaming" else "", style = MaterialTheme.typography.labelMedium, color = Fx.TextSecondary, modifier = Modifier.weight(1f))
            IconButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }) { Icon(FxIcons.Copy, "Copy code", tint = Fx.TextSecondary, modifier = Modifier.size(18.dp)) }
        }
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(14.dp)) {
            Text(block.code, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = Color(0xFFD6E4F5)), softWrap = false)
        }
    }
}

@Composable
private fun TableBlock(block: MdBlock.Table, fontSize: Int) {
    val cellStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = (fontSize - 2).sp)
    val all = listOf(block.header) + block.rows
    val columns = all.maxOf { it.size }
    // One width per column (from its longest cell) so every row lines up.
    val widths = (0 until columns).map { c ->
        val longest = all.maxOf { row -> row.getOrNull(c)?.let { Markdown.toPlainText(it).length } ?: 0 }
        (longest.coerceAtMost(30) * (fontSize - 2) * 0.58f + 26f).coerceIn(84f, 240f).dp
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Fx.Outline, RoundedCornerShape(12.dp)).horizontalScroll(rememberScrollState())) {
        Column {
            all.forEachIndexed { i, row ->
                Row(Modifier.background(if (i == 0) Fx.SurfaceHigh else Color.Transparent)) {
                    (0 until columns).forEach { c ->
                        val cell = row.getOrNull(c).orEmpty()
                        Text(inlineAnnotated(cell), style = scriptAwareStyle(cell, cellStyle.copy(fontWeight = if (i == 0) FontWeight.SemiBold else null), fontSize - 2), modifier = Modifier.width(widths[c]).padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
                if (i < block.rows.size) HorizontalDivider(color = Fx.Outline)
            }
        }
    }
}
