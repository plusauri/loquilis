package loquilis.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.nio.file.Path

/** Opens the bundled take, or the take folder named as the first argument. */
fun main(args: Array<String>) {
    val take = args.firstOrNull()?.let { loadTake(Path.of(it)) } ?: loadBundledTake()
    val state = ReviewState(take)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Loquilis review",
            state = rememberWindowState(width = 1320.dp, height = 880.dp),
        ) {
            ReviewTheme { ReviewApp(state) }
        }
    }
}
