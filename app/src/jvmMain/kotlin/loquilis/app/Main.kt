package loquilis.app

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Taskbar
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import loquilis.views.ConversationState
import loquilis.views.ConversationWindow
import loquilis.views.ReviewTheme

/** Opens the bundled take, or the library file or take folder named as the first argument. */
fun main(args: Array<String>) {
    // Gradle passes this as true to what it starts, which keeps a Mac app out of the Dock and Cmd-Tab.
    // It has to change before AWT loads.
    System.setProperty("apple.awt.UIElement", "false")
    val icon = readAppIcon()
    showInDock(icon)
    val library = openLibrary(args.firstOrNull()?.let { Path.of(it) })
    // A hook, not a finally: application() ends the process itself when the window closes.
    Runtime.getRuntime().addShutdownHook(Thread(library::close))
    val state = ConversationState(library.store, library.conversationId)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Loquilis",
            icon = remember { BitmapPainter(icon.toComposeImageBitmap()) },
            state = rememberWindowState(width = 1320.dp, height = 880.dp),
        ) {
            ReviewTheme { ConversationWindow(state) }
        }
    }
}

/** The Plusauri mark, until Loquilis has its own. */
private fun readAppIcon(): BufferedImage =
    checkNotNull(ClassLoader.getSystemResourceAsStream("app-icon.png")) { "app-icon.png is missing" }
        .use(ImageIO::read)

/** A Mac takes its Dock tile from here. The window's own icon only reaches Windows and Linux. */
private fun showInDock(icon: BufferedImage) {
    if (!Taskbar.isTaskbarSupported()) return
    val taskbar = Taskbar.getTaskbar()
    if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = icon
}
