package loquilis.app

import loquilis.store.LocalChatAgent
import loquilis.views.Brand
import loquilis.views.ConsoleSettings
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Taskbar
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import java.util.prefs.Preferences
import loquilis.agents.OllamaSetup
import loquilis.store.ClaudeAgent
import loquilis.store.GrokAgent
import loquilis.store.OpenCodeAgent
import loquilis.views.LibraryState
import loquilis.views.LibraryWindow
import loquilis.views.ReviewTheme

/**
 * Opens the default library, or what the first argument names: a library file, a take folder, or
 * `--sample` for the bundled take.
 */
fun main(args: Array<String>) {
    // Gradle passes this as true to what it starts, which keeps a Mac app out of the Dock and Cmd-Tab.
    // It has to change before AWT loads.
    System.setProperty("apple.awt.UIElement", "false")
    val icon = readAppIcon()
    showInDock(icon)
    val library = openArgument(args.firstOrNull())
    val kept = Preferences.userRoot().node("loquilis")
    val state = LibraryState(
        library.store,
        mapOf(
            "claude" to ClaudeAgent(),
            "grok" to GrokAgent(),
            // Read at each message, so a change in settings applies to the next one.
            "local" to LocalChatAgent(library.store, keepAlive = { "${readSettings(kept).keepModelMinutes}m" }),
            "opencode" to OpenCodeAgent(),
        ),
        library.conversationId,
        setups = mapOf("local" to OllamaSetup(), "opencode" to OllamaSetup()),
        settings = readSettings(kept),
        onSettings = { writeSettings(kept, it) },
    )
    // A hook, not a finally: application() ends the process itself when the window closes.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            state.close()
            library.close()
        },
    )
    application {
        val mark = remember { BitmapPainter(icon.toComposeImageBitmap()) }
        val brand = remember { Brand("Loquilis", mark, AppVersion.about()) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Loquilis",
            icon = mark,
            state = rememberWindowState(width = 1320.dp, height = 880.dp),
        ) {
            ReviewTheme { LibraryWindow(state, brand) { agent, model ->
                    val folder = if (state.needsFolder(agent)) chooseFolder() else Path.of("")
                    folder?.let { state.newConversation(it, agent, model) }
                } }
        }
    }
}

/** Asks where an agent should work. A Mac's file dialog picks folders only while this property is set. */
private fun chooseFolder(): Path? {
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    val dialog = FileDialog(null as Frame?, "Where should the agent work?", FileDialog.LOAD)
    dialog.isVisible = true
    System.clearProperty("apple.awt.fileDialogForDirectories")
    return dialog.file?.let { Path.of(dialog.directory, it) }
}

/** The shield. Its source is `app/loquilis-shield.svg`. */
private fun readAppIcon(): BufferedImage =
    checkNotNull(ClassLoader.getSystemResourceAsStream("app-icon.png")) { "app-icon.png is missing" }
        .use(ImageIO::read)

/** A Mac takes its Dock tile from here. The window's own icon only reaches Windows and Linux. */
private fun showInDock(icon: BufferedImage) {
    if (!Taskbar.isTaskbarSupported()) return
    val taskbar = Taskbar.getTaskbar()
    if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = icon
}

/** The console's settings as kept in this user's Java preferences, the place a desktop app keeps such things. */
private fun readSettings(kept: Preferences) = ConsoleSettings(
    offlineOnly = kept.getBoolean(OFFLINE_ONLY, false),
    hiddenAgents = kept.get(HIDDEN_AGENTS, "").split(',').filter(String::isNotBlank).toSet(),
    keepModelMinutes = kept.getInt(KEEP_MODEL_MINUTES, ConsoleSettings().keepModelMinutes),
    listShown = kept.getBoolean(LIST_SHOWN, true),
    listWidth = kept.getInt(LIST_WIDTH, ConsoleSettings.LIST_WIDTH),
    inspectorShown = kept.getBoolean(INSPECTOR_SHOWN, true),
    inspectorWidth = kept.getInt(INSPECTOR_WIDTH, ConsoleSettings.INSPECTOR_WIDTH),
)

private fun writeSettings(kept: Preferences, settings: ConsoleSettings) {
    kept.putBoolean(OFFLINE_ONLY, settings.offlineOnly)
    kept.put(HIDDEN_AGENTS, settings.hiddenAgents.sorted().joinToString(","))
    kept.putInt(KEEP_MODEL_MINUTES, settings.keepModelMinutes)
    kept.putBoolean(LIST_SHOWN, settings.listShown)
    kept.putInt(LIST_WIDTH, settings.listWidth)
    kept.putBoolean(INSPECTOR_SHOWN, settings.inspectorShown)
    kept.putInt(INSPECTOR_WIDTH, settings.inspectorWidth)
}

private const val OFFLINE_ONLY = "offline_only"
private const val HIDDEN_AGENTS = "hidden_agents"
private const val KEEP_MODEL_MINUTES = "keep_model_minutes"
private const val LIST_SHOWN = "list_shown"
private const val LIST_WIDTH = "list_width"
private const val INSPECTOR_SHOWN = "inspector_shown"
private const val INSPECTOR_WIDTH = "inspector_width"
