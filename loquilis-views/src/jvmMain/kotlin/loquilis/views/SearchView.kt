package loquilis.views

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Long enough that a typed word runs one search rather than one for each letter. */
private const val TYPING_PAUSE_MS = 120L

/** Full-text search. Choosing a hit goes to that entry in the view search was opened from. */
@Composable
fun SearchView(state: ConversationState) {
    val palette = LocalPalette.current
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(state.query, state.scope, state.pager) {
        delay(TYPING_PAUSE_MS)
        val query = state.query
        val scope = state.scope
        state.showHits(withContext(Dispatchers.IO) { state.search(query, scope) })
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicTextField(
                value = state.query,
                onValueChange = { state.query = it },
                singleLine = true,
                textStyle = TextStyle(color = palette.ink, fontSize = 14.sp),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { field ->
                    Box(
                        Modifier
                            .border(1.dp, palette.line, RoundedCornerShape(8.dp))
                            .background(palette.page, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        if (state.query.isEmpty()) Text("Search", color = palette.muted, fontSize = 14.sp)
                        field()
                    }
                },
            )
            PickOne(SearchScope.entries, state.scope, { it.label }) { state.scope = it }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (state.hits.isEmpty()) {
                Text(
                    if (state.query.isBlank()) "Every word must match. Put a phrase in double quotes." else "No matches.",
                    color = palette.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    items(state.hits, key = { it.hit.entry_id }) { row ->
                        Hit(row, showTitle = state.scope == SearchScope.Library) { state.openHit(row) }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Hit(row: SearchRow, showTitle: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            if (showTitle) "${row.kind} · ${row.conversationTitle}" else row.kind,
            color = palette.muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            highlighted(row.hit.snippet, palette.selection),
            color = palette.ink,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun highlighted(snippet: String, mark: Color): AnnotatedString = buildAnnotatedString {
    for ((text, matched) in snippetRuns(snippet)) {
        if (matched) withStyle(SpanStyle(background = mark, fontWeight = FontWeight.SemiBold)) { append(text) } else append(text)
    }
}
