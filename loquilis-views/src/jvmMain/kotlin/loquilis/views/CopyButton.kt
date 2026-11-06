package loquilis.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the button says it worked. */
private const val COPIED_MILLIS = 1500L

/**
 * Puts [text] on the clipboard. This is the one way anything in the window copies a whole item, so
 * every copy looks and behaves the same. [name] is what the button says on hover and to a screen reader.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun CopyButton(name: String, text: String) {
    val palette = LocalPalette.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(text) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MILLIS)
            copied = false
        }
    }

    TooltipArea(
        tooltip = {
            Text(
                name,
                color = palette.ink,
                fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(palette.line).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        },
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .semantics { contentDescription = name }
                .clickable {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(StringSelection(text)))
                        copied = true
                    }
                }
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(14.dp)) {
                val line = Stroke(width = 1.4.dp.toPx())
                val corner = CornerRadius(2.dp.toPx())
                val page = Size(size.width * 0.68f, size.height * 0.68f)
                val tint = if (copied) palette.accent else palette.muted
                drawRoundRect(tint, Offset.Zero, page, corner, line)
                drawRoundRect(tint, Offset(size.width - page.width, size.height - page.height), page, corner, line)
            }
            if (copied) Text("Copied", color = palette.accent, fontSize = 11.sp)
        }
    }
}
