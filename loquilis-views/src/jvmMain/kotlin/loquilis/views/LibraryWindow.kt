package loquilis.views

import plusauri.ui.ResizableDialog
import plusauri.ui.DialogFooter
import plusauri.ui.DialogConfirmButton
import plusauri.ui.DialogDismissButton
import plusauri.ui.OverflowTooltipText
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import plusauri.ui.ActionKind
import plusauri.ui.FadingScrollColumn
import plusauri.ui.bleedEnd
import plusauri.ui.ActionPill
import plusauri.ui.ActionPillDensity
import plusauri.ui.HoverTooltip
import plusauri.ui.LabeledSlideToggle
import plusauri.ui.PanelSide
import plusauri.ui.SlideToggleDefaults
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import loquilis.agents.PermissionAsk
import loquilis.model.Conversation
import loquilis.model.Decision
import loquilis.store.ChatStatus
import loquilis.store.DataGoes
import loquilis.store.modelInUse

/** How often a growing conversation is read again. */
private const val REFRESH_MILLIS = 100L
private const val CLOCK_MILLIS = 1000L

/**
 * A library you can talk to: its conversations down the side, the one showing, and a box to write in.
 * [onNewConversation] asks the host where the agent should work. [brand] names the console at the top of
 * the list, and [keys] says which keys ask for which actions.
 */
@Composable
fun LibraryWindow(
    state: LibraryState,
    brand: Brand? = null,
    keys: KeyBindings = DefaultKeys,
    onNewConversation: (agent: String, model: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Closes the new-conversation card and starts with [agent], through its setup when it has one.
    val create: (String) -> Unit = { agent ->
        state.chosen(agent)
        scope.launch { if (state.prepare(agent)) onNewConversation(agent, "") }
    }
    val keyFocus = remember { FocusRequester() }
    // At the start, and again whenever the new-conversation card closes: its Name box held the keys, and with it
    // gone nothing would, so Escape and Return would no longer reach the window's own keys.
    val cardOpen = state.choosingAgent != null
    LaunchedEffect(cardOpen) { if (!cardOpen) keyFocus.requestFocus() }
    Box(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val action = keys.actionFor(event)
                when {
                    action != null -> true.also { state.perform(action) }
                    else -> false
                }
            }
            .focusRequester(keyFocus)
            .focusable(),
    ) {
        LaunchedEffect(state.newConversationAsked) { if (state.newConversationAsked > 0) state.startChoosing() }
        Library(state, brand) { state.startChoosing() }
        if (state.choosingAgent != null) NewConversationCard(state) { agent -> create(agent) }
        state.setup?.let { setup ->
            SetupPanel(setup, onClose = { state.setup = null }, onBack = { state.backToChoosing(setup.agent) }) { model ->
                state.setup = null
                onNewConversation(setup.agent, model)
            }
        }
        if (state.showingSettings) SettingsCard(state) { state.showingSettings = false }
        state.deleting?.let { conversation ->
            ConfirmDelete(conversation, onCancel = { state.deleting = null }) { scope.launch { state.confirmDelete() } }
        }
    }
}

/**
 * Every dialog the console shows: the kit's resizable dialog (title, close, a grip to size it), with a body that
 * scrolls once it stops growing and a [footer] of dialog buttons. [keys] hears the keys pressed inside it, for a
 * dialog whose Return has an answer; Escape and a click outside already call [onDismiss].
 */
@Composable
internal fun ConsoleDialog(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 420.dp,
    keys: (Key) -> Boolean = { false },
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ResizableDialog(
        title = title,
        onDismiss = onDismiss,
        closeLabel = "Close (Esc)",
        defaultWidth = width,
        modifier = Modifier.onPreviewKeyEvent { it.type == KeyEventType.KeyDown && keys(it.key) },
    ) {
        FadingScrollColumn(
            Modifier.weight(1f, fill = false).bleedEnd(14.dp),
            fadeColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            scrollbar = true,
            scrollbarGutter = 14.dp,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
        footer?.let { DialogFooter(content = it) }
    }
}

/** Says what will go and what will stay, because this one cannot be taken back. */
@Composable
private fun ConfirmDelete(conversation: Conversation, onCancel: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalPalette.current
    ConsoleDialog(
        "Delete “${conversation.shownTitle}”?",
        onDismiss = onCancel,
        keys = { key -> (key == Key.Enter).also { if (it) onDelete() } },
        footer = {
            DialogDismissButton("Cancel", onClick = onCancel)
            DialogConfirmButton("Delete", onClick = onDelete, destructive = true)
        },
    ) {
        Text(
            "This removes the conversation and everything recorded for it from this library. It cannot be undone. " +
                "The agent's own session history stays where the agent keeps it.",
            color = palette.muted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun Library(state: LibraryState, brand: Brand?, onNewConversation: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxSize().background(palette.page)) {
        if (state.listPanel.shown) {
            ConversationList(state, brand, onNewConversation, Modifier.width(state.listPanel.width).fillMaxHeight().background(palette.panel))
        }
        PanelSeam(state.listPanel, PanelSide.Leading, "conversation list")
        val showing = state.current
        val id = state.currentId
        if (showing == null || id == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Start a conversation to talk to an agent.", color = palette.muted, fontSize = 14.sp)
            }
        } else {
            key(id) { Follow(state, showing, id) }
            ConversationWindow(showing, state.inspectorPanel) { key(id) { Composer(state, id) } }
        }
    }
}

/** Shows what a chat writes as it writes it, reading at most ten times a second. */
@OptIn(FlowPreview::class)
@Composable
private fun Follow(state: LibraryState, showing: ConversationState, id: String) {
    LaunchedEffect(id) {
        state.chat(id).written.sample(REFRESH_MILLIS).collect {
            val writing = state.chat(id).writing.value
            // A conversation deleted just now can no longer be read, and what replaced it is already showing.
            val refreshed = withContext(Dispatchers.IO) { runCatching { showing.pager.refreshed() }.getOrNull() }
            showing.show(refreshed ?: return@collect)
            // Set with the entries read after it, so a finished reply never leaves the screen for a moment.
            showing.writing = writing
            state.refreshList()
        }
    }
}

@Composable
private fun ConversationList(state: LibraryState, brand: Brand?, onNewConversation: () -> Unit, modifier: Modifier) {
    val palette = LocalPalette.current
    Column(modifier) {
        brand?.let { BrandLine(it) }
        // One button however many agents there are. It opens the card, where the conversation is named too.
        ActionPill(
            "$NEW_CONVERSATION…",
            ActionKind.Add,
            onClick = onNewConversation,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
        )
        OfflineOnlySwitch(state)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val list = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                items(state.conversations, key = { it.id }) { conversation ->
                    val asks by state.chat(conversation.id).asks.collectAsState()
                    val showing = conversation.id == state.currentId
                    val goes = remember(conversation) { state.dataGoes(conversation.id) }
                    ConversationLine(conversation, showing, asks.isNotEmpty(), goes, { state.perform(ConsoleAction.DeleteConversation) }) {
                        state.select(conversation.id)
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
        ActionPill("Settings", ActionKind.Go, onClick = { state.showingSettings = true }, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

/** The console's mark and name. Hovering over them says which build this is. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrandLine(brand: Brand) {
    val palette = LocalPalette.current
    HoverTooltip(
        maxWidth = 360.dp,
        placeBelow = true,
        tooltip = {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((label, value) in brand.about) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(label, color = palette.muted, fontSize = 12.sp, modifier = Modifier.width(84.dp))
                        Text(value, color = palette.ink, fontSize = 12.sp)
                    }
                }
            }
        },
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(brand.mark, contentDescription = null, Modifier.size(24.dp))
            Text(brand.name, color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ConversationLine(
    conversation: Conversation,
    selected: Boolean,
    asking: Boolean,
    goes: DataGoes,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) palette.selection else palette.panel)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Text(conversation.shownTitle, color = palette.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (asking) {
                Text("Waiting for your answer", color = palette.accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                DataGoesLine(goes, place(conversation))
            }
        }
        if (selected) {
            ActionPill("Delete", ActionKind.Remove, onClick = onDelete, density = ActionPillDensity.Thin, modifier = Modifier.padding(start = 6.dp, end = 12.dp))
        }
    }
}

/** The mark for where data goes. */
internal val DataGoes.mark: String
    get() = when (this) {
        is DataGoes.ToCompany -> "☁️"
        DataGoes.StaysHere -> "🔒"
        DataGoes.ModelHere -> "🔧"
        DataGoes.Unknown -> "❔"
    }

/**
 * [text] after where data goes, as a mark on a colored disc so it shows on any background: blue for sent
 * away, green for staying here, gray for not known. The one way the console shows it, wherever it does.
 */
@Composable
internal fun DataGoesLine(goes: DataGoes, text: String) {
    val palette = LocalPalette.current
    val disc = when (goes) {
        is DataGoes.ToCompany -> palette.accent
        DataGoes.StaysHere, DataGoes.ModelHere -> palette.local
        DataGoes.Unknown -> palette.line
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(disc), contentAlignment = Alignment.Center) {
            Text(goes.mark, fontSize = 8.sp, lineHeight = 8.sp)
        }
        // One line wherever it sits, and when that cuts it short a hover shows the rest.
        OverflowTooltipText(text, style = TextStyle(fontSize = 11.sp), color = palette.muted)
    }
}

/** Where data goes, in words a newcomer can act on. Says only what is known. */
internal val DataGoes.words: String
    get() = when (this) {
        is DataGoes.ToCompany -> "Sent to $company"
        DataGoes.StaysHere -> "Stays on this computer"
        DataGoes.ModelHere -> "Model on this computer; its tools can reach the network"
        DataGoes.Unknown -> "Not known where this goes"
    }

internal fun agentLabel(cli: String) = when (cli) {
    "opencode" -> "OpenCode"
    else -> cli.replaceFirstChar(Char::uppercase)
}

/** Where a conversation's agent worked, and which agent it was. */
private fun place(conversation: Conversation) = listOf(
    conversation.workspace_path.substringAfterLast('/'),
    agentLabel(conversation.agent?.cli.orEmpty()),
    conversation.modelInUse.orEmpty(),
).filter { it.isNotEmpty() }.joinToString(" · ")

/** The agent's oldest question, with what it wants to do spelled out and every answer one click away. */
@Composable
private fun AskCard(ask: PermissionAsk, more: Int, onAnswer: (Decision) -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, palette.accent, RoundedCornerShape(10.dp))
            .background(palette.page)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val title = "May the agent use ${ask.tool}?" + if (more > 0) " ($more more after this)" else ""
        Text(title, color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (ask.description.isNotBlank()) Text(ask.description, color = palette.muted, fontSize = 12.sp)
        if (ask.detail.isNotBlank()) {
            Text(
                ask.detail,
                color = palette.ink,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(palette.panel).padding(8.dp),
            )
        }
        // A question with answers, so the dialog buttons: the answer filled, the others dismiss-weight.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DialogConfirmButton("Allow", onClick = { onAnswer(Decision.DECISION_ALLOW_ONCE) })
            DialogDismissButton("Deny", onClick = { onAnswer(Decision.DECISION_DENY) })
            ask.remember?.let { rule ->
                DialogDismissButton("Always allow $rule in this folder", onClick = { onAnswer(Decision.DECISION_ALLOW_ALWAYS) }, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}

/** Send and Stop: the chat's own buttons, bigger than the kit's pills because writing is what the window is for. */
@Composable
private fun ComposerButton(
    label: String,
    background: Color,
    ink: Color,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        leading?.invoke()
        Text(label, color = ink, fontSize = 13.sp)
    }
}

/** A stop square, the sign media players use, so Stop stands out from Send while the agent works. */
@Composable
private fun StopSquare(ink: Color) {
    Box(Modifier.size(9.dp).clip(RoundedCornerShape(1.5.dp)).background(ink))
}

/** Return sends and Shift-Return starts a new line. What the agent never got comes back here. */
@Composable
private fun Composer(state: LibraryState, id: String) {
    val palette = LocalPalette.current
    val chat = state.chat(id)
    val status by chat.status.collectAsState()
    val asks by chat.asks.collectAsState()
    val text = state.drafts[id].orEmpty()
    val canSend = text.isNotBlank()
    // Why the last message could not go. It stays typed, for once it can.
    var refused by remember { mutableStateOf<String?>(null) }
    fun send() {
        if (!canSend) return
        refused = state.send(text)
        if (refused == null) state.drafts.remove(id)
    }

    LaunchedEffect(status) {
        val unsent = (status as? ChatStatus.Failed)?.unsent.orEmpty()
        if (unsent.isNotEmpty() && state.drafts[id].isNullOrEmpty()) state.drafts[id] = unsent.joinToString("\n\n")
    }

    Column(Modifier.fillMaxWidth().background(palette.panel)) {
        Box(Modifier.fillMaxWidth().background(palette.line).padding(top = 1.dp))
        asks.firstOrNull()?.let { ask -> AskCard(ask, asks.size - 1) { chat.answer(ask, it) } }
        val now by produceState(Instant.now(), status) {
            while (status is ChatStatus.Working) {
                value = Instant.now()
                delay(CLOCK_MILLIS)
            }
        }
        refused?.let { line ->
            Text(line, color = palette.failed, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
        statusLine(status, now)?.let { line ->
            Text(
                line,
                color = if (status is ChatStatus.Failed) palette.failed else palette.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { state.drafts[id] = it },
                textStyle = TextStyle(color = palette.ink, fontSize = 14.sp),
                cursorBrush = SolidColor(palette.ink),
                maxLines = 8,
                modifier = Modifier.weight(1f)
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, palette.line, RoundedCornerShape(18.dp))
                    .background(palette.page)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
                    .onPreviewKeyEvent { event ->
                        val isReturn = event.key == Key.Enter && !event.isShiftPressed
                        if (isReturn && event.type == KeyEventType.KeyDown) send()
                        isReturn
                    },
                decorationBox = { field ->
                    Box {
                        if (text.isEmpty()) Text("Message", color = palette.muted, fontSize = 14.sp)
                        field()
                    }
                },
            )
            if (status is ChatStatus.Working) ComposerButton("Stop", palette.failed, Color.White, leading = { StopSquare(Color.White) }) { chat.interrupt() }
            ComposerButton(
                "Send",
                if (canSend) palette.accent else palette.line,
                if (canSend) palette.userInk else palette.muted,
                enabled = canSend,
            ) { send() }
        }
        // Read again as the chat moves on, since the model in use is only known once it answers.
        val goes = remember(id, status) { state.dataGoes(id) }
        val line = remember(id, status, state.offlineOnly) { state.goesLine(id) + if (state.offlineOnly) " · offline only" else "" }
        Box(Modifier.padding(start = 24.dp, end = 16.dp, top = 5.dp, bottom = 9.dp)) { DataGoesLine(goes, line) }
    }
}

internal fun statusLine(status: ChatStatus, now: Instant): String? = when (status) {
    ChatStatus.Idle -> null
    is ChatStatus.Working -> {
        val working = "Working… ${wholeSeconds(Duration.between(status.since, now).seconds.coerceAtLeast(0))}"
        when (status.waiting) {
            0 -> working
            1 -> "$working · 1 message waiting"
            else -> "$working · ${status.waiting} messages waiting"
        }
    }
    is ChatStatus.Failed -> status.reason
}

/**
 * Turns offline only on and off, and while it is on says whether this computer is off the network, looking
 * again every few seconds, so a person can see sending is possible before they write.
 */
@Composable
internal fun OfflineOnlySwitch(state: LibraryState, modifier: Modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
    val palette = LocalPalette.current
    val on = state.offlineOnly
    LaunchedEffect(on) {
        while (on) {
            state.lookAtNetworks()
            delay(NETWORK_LOOK_MILLIS)
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Toggle("Offline only", on) { state.changeOfflineOnly(it) }
        if (on) {
            val joined = state.joined
            if (joined.isEmpty()) {
                DataGoesLine(DataGoes.StaysHere, "Off the network. Local models only.")
            } else {
                Text("On a network (${joined.joinToString()}). Nothing is sent until it is off.", color = palette.failed, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

private const val NETWORK_LOOK_MILLIS = 3_000L

/**
 * A setting that is on or off, as the kit's slide toggle with its label, the one Tutilis uses. Every on-or-off
 * choice the console offers comes through here. Plain on and off, not good and bad, so not red and green.
 */
@Composable
internal fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    LabeledSlideToggle(label, on, onChange, colors = SlideToggleDefaults.primaryColors())
}
