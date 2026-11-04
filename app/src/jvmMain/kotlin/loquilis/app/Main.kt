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
    val library = openLibrary(args.firstOrNull()?.let { Path.of(it) })
    val state = ConversationState(library.store, library.conversationId)
    try {
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Loquilis",
                state = rememberWindowState(width = 1320.dp, height = 880.dp),
            ) {
                ReviewTheme { ConversationWindow(state) }
            }
        }
    } finally {
        library.close()
    }
}
