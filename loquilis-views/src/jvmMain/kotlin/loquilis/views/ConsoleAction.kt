package loquilis.views

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

/** What a console can be told to do, by name, so a key, a button or a menu can all ask for the same thing. */
enum class ConsoleAction(val label: String) {
    NewConversation("New conversation"),
    RenameConversation("Rename conversation"),
    DeleteConversation("Delete conversation"),
    ToggleConversationList("Show or hide the conversation list"),
    ToggleInspector("Show or hide the inspector"),
}

/** A key and what is held with it. */
data class KeyChord(
    val key: Key,
    val command: Boolean = false,
    val shift: Boolean = false,
    val control: Boolean = false,
    val option: Boolean = false,
)

/** Which keys ask for which actions. A console passes its own table to change them. */
typealias KeyBindings = Map<KeyChord, ConsoleAction>

/** Shift is held because Command-Delete already clears a line in the box you write in. */
val DefaultKeys: KeyBindings = mapOf(
    KeyChord(Key.N, command = true) to ConsoleAction.NewConversation,
    KeyChord(Key.Backspace, command = true, shift = true) to ConsoleAction.DeleteConversation,
)

internal fun KeyBindings.actionFor(event: KeyEvent): ConsoleAction? =
    this[KeyChord(event.key, event.isMetaPressed, event.isShiftPressed, event.isCtrlPressed, event.isAltPressed)]
