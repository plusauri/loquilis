package loquilis.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A reply laid out as a page. */
@Composable
internal fun MarkdownView(blocks: List<MarkdownBlock>, ink: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { MarkdownBlockView(it, ink) }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, ink: Color) {
    val palette = LocalPalette.current
    when (block) {
        is MarkdownBlock.Paragraph -> Text(annotated(block.runs), color = ink, fontSize = 14.sp, lineHeight = 20.sp)
        is MarkdownBlock.Heading -> Text(
            annotated(block.runs),
            color = ink,
            fontSize = headingSize(block.level).sp,
            lineHeight = (headingSize(block.level) + 6).sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
        is MarkdownBlock.Code -> Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(palette.page).horizontalScroll(rememberScrollState()).padding(10.dp),
        ) {
            Text(block.text, color = palette.ink, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace, softWrap = false)
        }
        is MarkdownBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(palette.muted))
            Box(Modifier.padding(start = 10.dp)) { MarkdownView(block.blocks, ink) }
        }
        is MarkdownBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(block.markers[index], color = ink, fontSize = 14.sp, lineHeight = 20.sp)
                    MarkdownView(item, ink)
                }
            }
        }
        is MarkdownBlock.Table -> MarkdownTable(block, ink)
        MarkdownBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(palette.muted))
    }
}

private fun headingSize(level: Int) = when (level) {
    1 -> 18
    2 -> 16
    else -> 14
}

@Composable
private fun MarkdownTable(table: MarkdownBlock.Table, ink: Color) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, palette.muted, RoundedCornerShape(6.dp)).background(palette.page)) {
        TableRow(table.header, palette.ink, bold = true)
        table.rows.forEach { row ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
            TableRow(row, palette.ink, bold = false)
        }
    }
}

@Composable
private fun TableRow(cells: List<List<InlineRun>>, ink: Color, bold: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEach { cell ->
            Text(
                annotated(cell),
                color = ink,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun annotated(runs: List<InlineRun>): AnnotatedString {
    val palette = LocalPalette.current
    return buildAnnotatedString {
        runs.forEach { run ->
            val style = SpanStyle(
                fontWeight = if (run.bold) FontWeight.Bold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                fontFamily = if (run.code) FontFamily.Monospace else null,
                background = if (run.code) palette.page else Color.Unspecified,
                textDecoration = if (run.strike) TextDecoration.LineThrough else null,
            )
            val url = run.link
            if (url == null) {
                withStyle(style) { append(run.text) }
            } else {
                val look = style.copy(color = palette.accent, textDecoration = TextDecoration.Underline)
                withLink(LinkAnnotation.Url(url, TextLinkStyles(look))) { append(run.text) }
            }
        }
    }
}
