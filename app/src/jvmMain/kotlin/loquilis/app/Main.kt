package loquilis.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.nio.file.Path
import loquilis.views.ConversationState
import loquilis.views.ConversationWindow
import loquilis.views.ReviewTheme

/** Opens the bundled take, or the library file or take folder named as the first argument. */
fun main(args: Array<String>) {
    // Gradle passes this as true to what it starts, which keeps a Mac app out of the Dock and Cmd-Tab.
    // It has to change before AWT loads.
    System.setProperty("apple.awt.UIElement", "false")
    val library = openLibrary(args.firstOrNull()?.let { Path.of(it) })
    // A hook, not a finally: application() ends the process itself when the window closes.
    Runtime.getRuntime().addShutdownHook(Thread(library::close))
    val state = ConversationState(library.store, library.conversationId)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Loquilis",
            state = rememberWindowState(width = 1320.dp, height = 880.dp),
        ) {
            ReviewTheme { ConversationWindow(state) }
        }
    }
}
