package loquilis.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import loquilis.core.isStored
import loquilis.model.Content

/** Past this, a body is cut for showing. The store still holds all of it. */
private const val SHOWN_CHARS = 60_000

/** The selected entry as the model holds it, next to the agent's lines it came from. */
@Composable
fun Inspector(state: ConversationState, modifier: Modifier) {
    val palette = LocalPalette.current
    val id = state.selectedId
    val seen by produceState<Inspection?>(null, id, state.pager) {
        value = id?.let { withContext(Dispatchers.IO) { state.pager.inspect(it) } }
    }
    val inspection = seen
    Box(modifier) {
        if (id == null || inspection == null) {
            if (id == null) {
                Text(
                    "Select anything in the conversation to see its entry and the raw lines it came from.",
                    color = palette.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            val entry = inspection.entry
            val scroll = remember(entry.id) { androidx.compose.foundation.ScrollState(0) }
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(kindLabel(entry), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = palette.ink)
                SelectionContainer { Fields(inspection, state) }
                inspection.sections.forEach { Code("${it.label} · ${where(it.content, it.text)}", "Copy ${it.label.lowercase()}", it.text) }
                val raw = inspection.raw
                if (raw.isNotEmpty()) Code("Raw (${raw.size})", "Copy raw lines", raw.joinToString("\n\n", transform = ::pretty))
            }
        }
    }
}

@Composable
private fun Fields(inspection: Inspection, state: ConversationState) {
    val entry = inspection.entry
    val parent = inspection.parent
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Field("seq", entry.seq.toString())
        Field("id", entry.id)
        Field("at", entry.at?.let(ISO::format).orEmpty())
        Field("author", entry.author.name.removePrefix("AUTHOR_").lowercase())
        Field("state", entry.state.name.removePrefix("ENTRY_STATE_").lowercase())
        Field("visibility", entry.visibility.name.removePrefix("VISIBILITY_").lowercase())
        Field("raw", entry.raw?.let { "${it.first_seq}..${it.last_seq}" } ?: "none")
        if (parent != null) {
            Field("parent", "#${parent.seq} ${kindLabel(parent)}", onClick = { state.selectedId = parent.id })
        } else {
            Field("parent", "none")
        }
    }
}

@Composable
private fun Field(name: String, value: String, onClick: (() -> Unit)? = null) {
    val palette = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, color = palette.muted, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        Text(
            value,
            color = if (onClick != null) palette.accent else palette.ink,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        )
    }
}

@Composable
private fun Code(label: String, copyName: String, text: String) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            CopyButton(copyName, text)
        }
        SelectionContainer {
            Text(shown(text), color = palette.ink, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun shown(text: String): String =
    if (text.length <= SHOWN_CHARS) text
    else text.take(SHOWN_CHARS) + "\n\n[cut for display at ${"%,d".format(SHOWN_CHARS)} of ${"%,d".format(text.length)} characters]"

/** Says which path the content took: the 4 KB inline limit is one of the things under review. */
private fun where(content: Content?, text: String): String = when {
    content == null -> "empty"
    content.isStored -> "blob, ${bytes(content.blob?.size ?: 0)}, ${content.blob?.sha256?.take(10)}"
    else -> "inline, ${bytes(text.encodeToByteArray().size.toLong())}"
}

private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

@OptIn(ExperimentalSerializationApi::class)
private val PrettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

private fun pretty(line: String): String =
    runCatching { PrettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(line)) }.getOrDefault(line)
